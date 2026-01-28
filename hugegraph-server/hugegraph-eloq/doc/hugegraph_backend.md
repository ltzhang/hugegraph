# HugeGraph Backend Storage: Architecture and Implementation Guide

This document explains how HugeGraph's storage backend abstraction works, what interfaces a new backend must implement, how data flows from graph operations to storage, and where backends can provide optimization. It is written as a standalone reference, independent of any particular new backend.

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [The Three Stores](#2-the-three-stores)
3. [Core Interfaces](#3-core-interfaces)
4. [Data Model: Entries, Columns, and Mutations](#4-data-model-entries-columns-and-mutations)
5. [Serialization: Two Paradigms](#5-serialization-two-paradigms)
6. [Query Model](#6-query-model)
7. [Query Execution Pipeline](#7-query-execution-pipeline)
8. [Backend Features and Operator Pushdown](#8-backend-features-and-operator-pushdown)
9. [Table Lifecycle](#9-table-lifecycle)
10. [Transaction Model](#10-transaction-model)
11. [Implementing a New Backend: Step by Step](#11-implementing-a-new-backend-step-by-step)
12. [Concrete Backend Comparison](#12-concrete-backend-comparison)
13. [Key Source Files](#13-key-source-files)

---

## 1. Architecture Overview

HugeGraph separates graph logic from physical storage through a layered abstraction:

```
┌──────────────────────────────────────────────┐
│  Gremlin / Cypher / REST API                 │
├──────────────────────────────────────────────┤
│  Graph Engine (StandardHugeGraph)            │
│    - Schema management                       │
│    - Transaction management                  │
│    - Query optimization                      │
├──────────────────────────────────────────────┤
│  Serializer (Binary or Table)                │
│    - Graph elements ↔ BackendEntry           │
│    - Query ↔ backend-specific query          │
├──────────────────────────────────────────────┤
│  Backend Store (BackendStore interface)       │
│    - mutate(), query(), beginTx/commitTx     │
│    - Dispatches to per-table handlers        │
├──────────────────────────────────────────────┤
│  Backend Table (BackendTable)                │
│    - insert/delete/query per table type      │
├──────────────────────────────────────────────┤
│  Backend Session (BackendSession)            │
│    - Physical I/O: put/get/scan/delete       │
│    - Transaction commit/rollback             │
├──────────────────────────────────────────────┤
│  Physical Storage Engine                     │
│    (RocksDB, MySQL, Cassandra, etc.)         │
└──────────────────────────────────────────────┘
```

A new backend implements the bottom four layers. The graph engine and serializer are shared.

---

## 2. The Three Stores

HugeGraph organizes every graph's data into three logical stores, each backed by a separate `BackendStore` instance:

| Store | Name Constant | Purpose | Tables |
|-------|---------------|---------|--------|
| **Schema** | `"m"` | Graph schema definitions | VERTEX_LABEL, EDGE_LABEL, PROPERTY_KEY, INDEX_LABEL, SECONDARY_INDEX (for schema), COUNTER |
| **Graph** | `"g"` | Graph data and indexes | VERTEX, EDGE_OUT, EDGE_IN, SECONDARY_INDEX, SEARCH_INDEX, UNIQUE_INDEX, VERTEX_LABEL_INDEX, EDGE_LABEL_INDEX, RANGE_INT_INDEX, RANGE_LONG_INDEX, RANGE_FLOAT_INDEX, RANGE_DOUBLE_INDEX, SHARD_INDEX |
| **System** | `"s"` | System metadata | META (stores version info), COUNTER (ID generation) |

The `BackendStoreProvider` is responsible for creating all three stores.

### Table Inventory

**Schema store tables** hold schema element definitions. Each schema element (e.g., a VertexLabel) is stored as a key-value entry where the key is the element's ID and the value contains the serialized definition.

**Graph store tables** hold the actual graph data:

- **VERTEX** — one entry per vertex, key = vertex ID, value = label + all properties
- **EDGE_OUT** ("oedge") — outgoing edges, key = `[ownerVertex][OUT][edgeLabel][sortValues][otherVertex]`
- **EDGE_IN** ("iedge") — incoming edges, key = `[ownerVertex][IN][edgeLabel][sortValues][otherVertex]`
- **Index tables** — each maps an index value to the set of element IDs that match

**System store** holds a META table for version tracking and COUNTER tables for ID generation.

---

## 3. Core Interfaces

### 3.1 BackendStoreProvider

Factory and lifecycle manager for the three stores.

```java
interface BackendStoreProvider {
    // Identity
    String type();              // e.g., "rocksdb", "mysql", "cassandra"
    String driverVersion();     // e.g., "1.11"
    String graph();             // graph/database name

    // Store creation
    BackendStore loadSchemaStore(HugeConfig config);
    BackendStore loadGraphStore(HugeConfig config);
    BackendStore loadSystemStore(HugeConfig config);

    // Lifecycle
    void open(String name);
    void close();
    void init();                // First-time setup (create tables)
    void clear();               // Drop everything
    void truncate();            // Delete data, keep structure
    boolean initialized();

    // Snapshots
    void createSnapshot();
    void resumeSnapshot();
}
```

Typically you extend `AbstractBackendStoreProvider`, which handles store caching, event dispatch, and coordinated lifecycle calls. You only implement `newSchemaStore()`, `newGraphStore()`, `newSystemStore()`.

### 3.2 BackendStore

The main storage interface. Each store instance manages one logical database (schema, graph, or system).

```java
interface BackendStore {
    // Identity
    String store();                         // store name
    String database();                      // database name
    BackendStoreProvider provider();
    BackendFeatures features();

    // Lifecycle
    void open(HugeConfig config);
    void close();
    void init();                            // Create tables
    void clear(boolean clearSpace);         // Drop tables
    void truncate();                        // Drop + recreate tables
    boolean initialized();                  // Check all tables exist

    // Data operations
    void mutate(BackendMutation mutation);  // Write batch of changes
    Iterator<BackendEntry> query(Query q);  // Read data
    Number queryNumber(Query query);        // Count / aggregate

    // Transaction
    void beginTx();
    void commitTx();
    void rollbackTx();

    // ID generation
    Id nextId(HugeType type);
    void increaseCounter(HugeType type, long increment);
    long getCounter(HugeType type);

    // OLAP (dynamic tables)
    void createOlapTable(Id pkId);
    void removeOlapTable(Id pkId);
    void clearOlapTable(Id pkId);
    boolean existOlapTable(Id pkId);

    // Snapshots
    Map<String, String> createSnapshot(String dir);
    void resumeSnapshot(String dir, boolean delete);
}
```

### 3.3 BackendSession

Represents one transaction/connection session. Backends define their own session subclass.

```java
interface BackendSession {
    void open();
    void close();
    boolean opened();
    boolean closed();
    Object commit();        // Flush pending writes
    void rollback();        // Discard pending writes
    boolean hasChanges();   // Any uncommitted writes?
}
```

The RocksDB backend's session wraps a `WriteBatch`. The MySQL backend's session wraps a JDBC connection. Your session wraps whatever transactional primitive your storage provides.

### 3.4 BackendFeatures

Declares what the backend can do. The graph engine adapts its behavior based on these flags (see [Section 8](#8-backend-features-and-operator-pushdown)).

```java
interface BackendFeatures {
    // Scan capabilities
    boolean supportsScanToken();        // Distributed token scan
    boolean supportsScanKeyPrefix();    // Seek by key prefix
    boolean supportsScanKeyRange();     // Range scan [start, end]

    // Query pushdown capabilities
    boolean supportsQuerySchemaByName();
    boolean supportsQueryByLabel();
    boolean supportsQueryWithInCondition();
    boolean supportsQueryWithRangeCondition();
    boolean supportsQueryWithContains();
    boolean supportsQueryWithContainsKey();
    boolean supportsQueryWithOrderBy();
    boolean supportsQueryByPage();
    boolean supportsQuerySortByInputIds();

    // Write capabilities
    boolean supportsDeleteEdgeByLabel();
    boolean supportsUpdateVertexProperty();
    boolean supportsMergeVertexProperty();
    boolean supportsUpdateEdgeProperty();

    // Infrastructure
    boolean supportsTransaction();
    boolean supportsTtl();
    boolean supportsOlapProperties();
    boolean supportsSnapshot();
    boolean supportsSharedStorage();
}
```

### 3.5 BackendTable

Abstract base for per-table operations. Each table type (Vertex, Edge, SecondaryIndex, etc.) is a subclass.

```java
abstract class BackendTable<Session, Entry> {
    String table();     // Table name (used as column family name, SQL table name, etc.)

    // DDL
    void init(Session session);     // Create table structure
    void clear(Session session);    // Delete all data

    // DML
    void insert(Session session, Entry entry);
    void delete(Session session, Entry entry);
    void append(Session session, Entry entry);      // Add to collection
    void eliminate(Session session, Entry entry);    // Remove from collection

    // Query
    Iterator<BackendEntry> query(Session session, Query query);
    Number queryNumber(Session session, Query query);
}
```

---

## 4. Data Model: Entries, Columns, and Mutations

### BackendEntry

A `BackendEntry` represents one logical row/record in a table. It has:

- **id** — the row key
- **type** — `HugeType` (VERTEX, EDGE_OUT, SECONDARY_INDEX, etc.)
- **columns** — list of `BackendColumn` (name-value byte pairs)
- **ttl** — time-to-live in milliseconds

### BackendColumn

```java
class BackendColumn {
    byte[] name;    // Column name (may be empty for single-column entries)
    byte[] value;   // Column value
}
```

For KV-style backends, a vertex entry typically has one column where the name is the vertex ID bytes and the value is the serialized label + properties. For table-style backends, each named column in the table row maps to a `BackendColumn`.

### BackendMutation

Groups multiple write operations into a batch:

```java
class BackendMutation {
    void add(BackendEntry entry, Action action);
    Iterator<BackendAction> mutation(HugeType type);
}
```

Actions: `INSERT`, `DELETE`, `APPEND`, `ELIMINATE`, `UPDATE_IF_PRESENT`, `UPDATE_IF_ABSENT`.

The mutation system includes **optimization logic**: an INSERT on the same key overrides prior APPEND/ELIMINATE actions; a DELETE removes prior non-INSERT actions. This prevents redundant writes within a transaction.

### Write Flow

```
graph.addVertex(label, properties)
    → GraphTransaction buffers the operation
    → On commit: Serializer converts HugeVertex → BackendEntry
    → BackendMutation collects entries by type
    → BackendStore.mutate(mutation)
        → For each (HugeType, BackendAction):
            → table.insert/delete/append/eliminate(session, entry)
                → session.put/delete/scan(...)
    → BackendStore.commitTx()
        → session.commit()  // Flush to storage
```

---

## 5. Serialization: Two Paradigms

HugeGraph provides two serialization families. A new backend chooses one based on its storage model.

### 5.1 KV-Style (BinarySerializer)

Used by: **RocksDB**, **KVT**, **HBase**

Data is encoded into **binary key-value pairs** where the key structure encodes query semantics.

**Vertex encoding:**
```
Key:   [vertexId]
Value: [labelId][numProperties][propKeyId1 + propValue1][propKeyId2 + propValue2]...[expiredTime?]
```

All properties are packed into a single binary value blob.

**Edge encoding:**
```
Key:   [ownerVertexId][direction(1 byte)][edgeLabelId][subLabelId][sortValues][otherVertexId]
Value: [numProperties][propKeyId + propValue]...[expiredTime?]
```

The hierarchical key means:
- All edges of vertex V share prefix `[V]`
- All outgoing edges share prefix `[V][0x01]`
- All `knows` edges out of V share prefix `[V][0x01][knowsLabelId]`
- This enables efficient prefix scans for adjacency traversal.

**Index encoding (secondary):**
```
Key:   [indexLabelId][fieldValue][elementId]
Value: (empty or overflow data)
```

All elements matching property value V share prefix `[indexLabel][V]`.

**Index encoding (range/numeric):**
```
Key:   [indexLabelId][numericValue(encoded)][elementId]
Value: (empty)
```

Numeric values are encoded in a byte-comparable format, enabling range scans like `age > 30` → scan from `[ageIndex][30]` to `[ageIndex][MAX]`.

**Queries become scan operations** on sorted byte keys:
- "All edges of V" → prefix scan on `[V]`
- "Vertices where name=Alice" → prefix scan on `[nameIndex]["Alice"]`
- "Vertices where age > 30" → range scan from `[ageIndex][30]` to end
- "Vertex by ID" → point get on `[vertexId]`

### 5.2 Table-Style (TableSerializer)

Used by: **MySQL**, **Cassandra**, **PostgreSQL**, **Palo**

Data is stored as **rows with named columns**, and queries become SQL/CQL WHERE clauses.

**Vertex encoding:**
```
Row:
  ID:         vertexId (string)
  LABEL:      labelId (long)
  PROPERTIES: {propKeyId1: "value1", propKeyId2: "value2"} (JSON map)
  EXPIRED_TIME: timestamp
```

**Edge encoding:**
```
Row:
  OWNER_VERTEX: ownerVertexId
  DIRECTION:    direction byte
  LABEL:        edgeLabelId
  SORT_VALUES:  sortValueString
  OTHER_VERTEX: otherVertexId
  PROPERTIES:   {propKeyId: "value"} (JSON map)
```

**Index encoding:**
```
Row:
  INDEX_LABEL_ID: indexLabelId
  FIELD_VALUES:   "Alice"
  ELEMENT_IDS:    [v1, v3, v5]   (collection of matching element IDs)
```

**Queries become SQL/CQL predicates** — the database engine handles filtering:
```sql
SELECT * FROM edges WHERE owner_vertex = ? AND direction = ? AND label = ?
```

### 5.3 Which to Choose?

| Storage Type | Serializer | Entry Type | Why |
|---|---|---|---|
| Sorted KV store (RocksDB, LevelDB) | BinarySerializer | BinaryBackendEntry | Key structure IS the index; prefix/range scans are native |
| Relational DB (MySQL, PostgreSQL) | TableSerializer | TableBackendEntry | SQL optimizer handles WHERE clauses |
| Wide-column store (Cassandra, HBase) | Either works | Depends | Cassandra uses TableSerializer; HBase uses BinarySerializer |

The fundamental question: **does your storage understand key ordering natively?** If yes, KV-style lets you exploit that directly. If your storage has its own query engine (SQL), table-style delegates filtering to it.

---

## 6. Query Model

### 6.1 Query Class Hierarchy

```
Query (base)
├── IdQuery              — query by exact IDs
│   └── OneIdQuery       — optimized single-ID variant
├── IdPrefixQuery        — scan by key prefix (+ start position)
├── IdRangeQuery         — scan by key range [start, end] with inclusive/exclusive flags
└── ConditionQuery       — query by property conditions
    └── BatchConditionQuery
```

### 6.2 Query Properties

Every query carries:
- `resultType` — what HugeType to return (VERTEX, EDGE_OUT, SECONDARY_INDEX, etc.)
- `offset` / `limit` — pagination
- `page` — cursor-based pagination state
- `orders` — sort specification
- `capacity` — max result count
- `conditions` — list of filter predicates (ConditionQuery only)

### 6.3 Condition Types

Conditions combine with AND logic within a single ConditionQuery:

```java
// Relation operators
EQ("=="), GT(">"), GTE(">="), LT("<"), LTE("<="), NEQ("!="),
IN("in"), NOT_IN("notin"),
PREFIX("prefix"),
TEXT_CONTAINS("textcontains"), TEXT_CONTAINS_ANY("textcontainsany"),
CONTAINS("contains"),        // Collection contains element
CONTAINS_VALUE("containsv"), // Map contains value
CONTAINS_KEY("containsk"),   // Map contains key
SCAN("scan")                 // Token-based scan
```

### 6.4 Query Translation by Serializer

The serializer's `writeQuery()` method transforms high-level queries into backend-specific forms:

**BinarySerializer translation examples:**

| Input Query | Output Query |
|---|---|
| ConditionQuery {OWNER_VERTEX=v1, DIRECTION=OUT} | IdPrefixQuery with prefix `[v1][OUT]` |
| ConditionQuery {OWNER_VERTEX=v1, DIRECTION=OUT, LABEL=knows, SORT_VALUES range [a,z]} | IdRangeQuery from `[v1][OUT][knows][a]` to `[v1][OUT][knows][z]` |
| ConditionQuery {INDEX_LABEL=nameIdx, FIELD_VALUES="Alice"} | IdPrefixQuery with prefix `[nameIdx]["Alice"]` |
| ConditionQuery {INDEX_LABEL=ageIdx, FIELD_VALUES range [30, MAX]} | IdRangeQuery from `[ageIdx][30]` to `[ageIdx][MAX]` |
| IdQuery with vertex IDs [v1, v2, v3] | IdQuery with BinaryId-wrapped IDs |

**TableSerializer** mostly passes ConditionQuery through with serialized values, letting the database engine apply WHERE clauses.

---

## 7. Query Execution Pipeline

From a Gremlin query to physical storage reads:

```
g.V().has("age", gt(30)).has("name", "Alice")
    │
    ▼
1. TinkerPop Optimization Strategies
    HugeGraphStep extracts HasContainers
    │
    ▼
2. Condition Building (TraversalUtil)
    HasContainer → Condition.Relation(age > 30), Condition.Relation(name == "Alice")
    → ConditionQuery
    │
    ▼
3. Index Selection (GraphIndexTransaction)
    Checks available indexes:
    - If "age" has a RANGE index → use it for "age > 30"
    - If "name" has a SECONDARY index → use it for "name == Alice"
    - May use single index, joint indexes, or no index
    │
    ▼
4. Index Query Execution
    Query index table → get candidate element IDs
    │
    ▼
5. Element Fetch
    Load full vertex/edge entries by ID from VERTEX/EDGE table
    │
    ▼
6. Post-filtering (in Java)
    ConditionQuery.test(element) re-checks ALL conditions
    Filters expired elements if backend doesn't support TTL
    │
    ▼
7. Result Assembly
    Deserialize BackendEntry → HugeVertex/HugeEdge objects
    Apply offset/limit
```

**Key insight:** Step 6 always happens. Even if the backend pushed down a condition, HugeGraph re-validates in Java for correctness. This means a backend doesn't need to guarantee perfect filtering — it just needs to return a superset. The engine will filter down. But returning a smaller superset is faster.

---

## 8. Backend Features and Operator Pushdown

### 8.1 What "Operator Pushdown" Means Here

When the graph engine has a condition like `age > 30`, it can either:
- **Push down**: tell the backend to only return entries matching `age > 30`
- **Filter in Java**: get all entries, then check `age > 30` on each one

Pushdown is faster because it reduces data transfer and deserialization. The `BackendFeatures` interface tells the engine what the backend can handle natively.

### 8.2 Feature Impact on Query Execution

| Feature Flag | When TRUE | When FALSE |
|---|---|---|
| `supportsQueryByLabel()` | Backend filters by vertex/edge label | Engine scans all, filters in Java |
| `supportsQueryWithInCondition()` | Single query with IN clause | Engine flattens IN → multiple OR queries |
| `supportsQueryWithRangeCondition()` | Backend handles `>`, `<`, `>=`, `<=` | Engine gets all index entries, filters in Java |
| `supportsQueryWithOrderBy()` | Backend returns sorted results | Engine sorts in memory after fetch |
| `supportsQueryByPage()` | Backend handles offset/limit natively | Engine fetches all, applies offset/limit in Java |
| `supportsQuerySortByInputIds()` | Backend preserves ID input order | Engine re-sorts to match input order |
| `supportsTtl()` | Backend skips expired entries | Engine checks `element.expired()` per result |
| `supportsDeleteEdgeByLabel()` | Bulk delete by label pushed to backend | Engine fetches all edges of label, deletes one by one |
| `supportsUpdateVertexProperty()` | Backend updates single property in-place | Engine rewrites entire vertex entry |

### 8.3 Example: RocksDB vs MySQL vs Cassandra

| Capability | RocksDB | MySQL | Cassandra |
|---|---|---|---|
| Scan by key prefix | Yes | N/A (uses SQL WHERE) | N/A (uses CQL) |
| Scan by key range | Yes | Yes (SQL BETWEEN) | No (uses token scan) |
| Query by label | No | Yes | Yes |
| IN condition | No (flattened) | Yes | Yes |
| Range condition | Yes | Yes | Yes |
| Order by | Yes (key order) | Yes (SQL ORDER BY) | Yes (CQL ORDER BY) |
| TTL | No | No | Yes (native Cassandra TTL) |
| Update single property | No (rewrite entire value) | No | Yes |
| Transaction | Yes (WriteBatch) | Yes (SQL transactions) | Partial (batch only) |

### 8.4 How Additional Features Help

**For a sorted KV backend**, the most impactful features to support are:

1. **`supportsScanKeyPrefix()` + `supportsScanKeyRange()`** — these are essential. Without them, every query becomes a full table scan. These enable efficient graph traversal (adjacency queries) and index lookups.

2. **`supportsQueryByPage()`** — avoids loading all results into memory. The backend returns a page of results plus a continuation token.

3. **`supportsQueryWithRangeCondition()`** — enables the backend to use range scans on numeric index tables instead of returning all entries.

4. **`supportsQueryWithOrderBy()`** — if keys are naturally sorted, returning results in key order avoids an in-memory sort.

5. **`supportsTransaction()`** — enables the engine to batch writes and commit atomically.

Features like `supportsQueryByLabel()` and `supportsQueryWithInCondition()` are less critical for KV backends because those queries are typically routed through index tables (which use prefix scans) rather than direct table filtering.

---

## 9. Table Lifecycle

### 9.1 Creation

Tables are created during `BackendStore.init()`, which is called once when a graph is first set up. The store iterates its registered table objects and calls `table.init(session)` for each.

In the RocksDB backend, `init()` creates a RocksDB column family per table. In MySQL, it executes CREATE TABLE DDL. In Cassandra, it creates a Cassandra table.

### 9.2 Destruction

`BackendStore.clear(clearSpace)` drops all tables. In RocksDB, this calls `dropColumnFamilies()`. In MySQL, `DROP TABLE`. This is a real namespace destruction, not just data deletion.

### 9.3 Truncation

`BackendStore.truncate()` = `clear()` + `init()`. Drops all tables then recreates them empty.

### 9.4 Dynamic Tables (OLAP)

OLAP tables are created and destroyed at runtime, not just during init:

```java
void createOlapTable(Id pkId);      // Called when user creates an OLAP property key
void removeOlapTable(Id pkId);      // Called when user removes an OLAP property key
void clearOlapTable(Id pkId);       // Drop + recreate the OLAP table
boolean existOlapTable(Id pkId);    // Check existence
```

The backend must support dynamic table creation/deletion, not just at-init-time DDL.

### 9.5 What the Backend Needs

At minimum, the backend storage needs these table-management operations:

| Operation | Purpose | When Called |
|---|---|---|
| Create table/namespace | Set up a named key space | `init()`, `createOlapTable()` |
| Drop table/namespace | Destroy a named key space | `clear()`, `removeOlapTable()` |
| Check table exists | Verify setup | `initialized()`, `existOlapTable()` |

The drop operation should be efficient (O(1) metadata operation preferred over scanning and deleting all keys).

---

## 10. Transaction Model

### 10.1 Write Path

HugeGraph batches all mutations within a transaction:

```
beginTx()
  ← Graph operations: addVertex, addEdge, removeVertex, etc.
  ← Each operation creates BackendEntry objects via the serializer
  ← Entries accumulated in BackendMutation
commitTx()
  → BackendStore.mutate(mutation)  — dispatch entries to tables
  → BackendStore.commitTx()         — flush to storage
```

The key point: `mutate()` delivers entries to the session (e.g., adds to WriteBatch), and `commitTx()` makes them durable. A backend that has true transaction support (begin/commit/abort) maps naturally here.

### 10.2 Read Path

Reads happen outside the write batch — in the RocksDB backend, `session.get()` and `session.scan()` read directly from the database, not from the pending WriteBatch. The session asserts `!hasChanges()` before reads to prevent read-your-own-writes issues within a batch.

### 10.3 Session Lifecycle

Sessions are pooled per thread. The `BackendSessionPool` manages creation, reuse, and cleanup. Each thread gets its own session (and thus its own transaction context).

---

## 11. Implementing a New Backend: Step by Step

### Step 1: Create the Maven Module

Create a new module (e.g., `hugegraph-mybackend`) under `hugegraph-server/` with a dependency on `hugegraph-core`.

### Step 2: Implement BackendStoreProvider

Extend `AbstractBackendStoreProvider`:

```java
public class MyBackendStoreProvider extends AbstractBackendStoreProvider {
    @Override public String type() { return "mybackend"; }
    @Override public String driverVersion() { return "1.0"; }

    @Override protected BackendStore newSchemaStore(HugeConfig config, String store) {
        return new MyBackendSchemaStore(this, database(), store);
    }
    @Override protected BackendStore newGraphStore(HugeConfig config, String store) {
        return new MyBackendGraphStore(this, database(), store);
    }
    @Override protected BackendStore newSystemStore(HugeConfig config, String store) {
        return new MyBackendSystemStore(this, database(), store);
    }
}
```

### Step 3: Implement BackendStore

Each store (schema, graph, system) manages a set of tables and a session pool. The typical pattern:

```java
public class MyBackendStore implements BackendStore {
    private Map<HugeType, MyBackendTable> tables;
    private MySessionPool sessions;

    // Register tables in constructor:
    // SchemaStore: VERTEX_LABEL, EDGE_LABEL, PROPERTY_KEY, INDEX_LABEL, SECONDARY_INDEX, COUNTER
    // GraphStore:  VERTEX, EDGE_OUT, EDGE_IN, all index types
    // SystemStore: META, COUNTER

    @Override
    public void mutate(BackendMutation mutation) {
        MySession session = this.sessions.session();
        for each (type, action) in mutation:
            MyBackendTable table = this.tables.get(type);
            switch (action.action()):
                INSERT  → table.insert(session, action.entry())
                DELETE  → table.delete(session, action.entry())
                APPEND  → table.append(session, action.entry())
                ELIMINATE → table.eliminate(session, action.entry())
    }

    @Override
    public Iterator<BackendEntry> query(Query query) {
        MySession session = this.sessions.session();
        MyBackendTable table = this.tables.get(tableType(query));
        return table.query(session, query);
    }
}
```

### Step 4: Implement BackendSession

Your session wraps the storage engine's transaction primitive:

```java
public class MySession extends AbstractBackendSession {
    // For a KV store: wrap transaction handle
    // For SQL: wrap JDBC connection + PreparedStatements
    // For network store: wrap client connection

    @Override public Object commit() { /* flush writes */ }
    @Override public void rollback() { /* discard writes */ }
    @Override public boolean hasChanges() { /* check pending writes */ }
}
```

### Step 5: Implement BackendTable

For each table type, implement how entries map to your storage:

**KV-style example (sorted KV store):**
```java
public class MyKvTable extends BackendTable<MySession, BackendEntry> {
    void insert(MySession session, BackendEntry entry) {
        for (BackendColumn col : entry.columns()) {
            session.put(this.table(), col.name, col.value);
        }
    }

    void delete(MySession session, BackendEntry entry) {
        if (entry.columns().isEmpty()) {
            session.delete(this.table(), entry.id().asBytes());
        } else {
            for (BackendColumn col : entry.columns()) {
                session.delete(this.table(), col.name);
            }
        }
    }

    Iterator<BackendEntry> query(MySession session, Query query) {
        if (query instanceof IdPrefixQuery) {
            // Prefix scan
            return session.scan(this.table(), prefix, prefixEnd);
        } else if (query instanceof IdRangeQuery) {
            // Range scan
            return session.scan(this.table(), start, end);
        } else if (query has IDs) {
            // Point lookups
            return session.multiGet(this.table(), ids);
        } else {
            // Full scan
            return session.scan(this.table());
        }
    }
}
```

### Step 6: Implement BackendFeatures

Declare what your backend supports:

```java
public class MyBackendFeatures implements BackendFeatures {
    // For a sorted KV store:
    public boolean supportsScanKeyPrefix()  { return true; }
    public boolean supportsScanKeyRange()   { return true; }
    public boolean supportsQueryWithRangeCondition() { return true; }
    public boolean supportsQueryWithOrderBy()        { return true; }
    public boolean supportsQueryByPage()             { return true; }
    public boolean supportsTransaction()             { return true; }
    public boolean supportsSnapshot()                { return true; }

    // Typically false for KV stores:
    public boolean supportsQueryByLabel()            { return false; }
    public boolean supportsQueryWithInCondition()    { return false; }
    public boolean supportsUpdateVertexProperty()    { return false; }
    public boolean supportsTtl()                     { return false; }
}
```

### Step 7: Register the Provider

In `RegisterUtil` or via SPI (META-INF/services):

```java
BackendProviderFactory.register("mybackend",
    "com.example.MyBackendStoreProvider");
```

Users then configure: `backend=mybackend` in `hugegraph.properties`.

---

## 12. Concrete Backend Comparison

### 12.1 RocksDB (KV-style, embedded)

- **Session**: Wraps `WriteBatch` for writes, direct `RocksDB.get()`/`RocksIterator` for reads
- **Tables**: Each table = one RocksDB column family
- **Serializer**: `BinarySerializer` — all structure encoded in key bytes
- **Transactions**: WriteBatch provides atomicity; no read-your-own-writes within batch
- **Multi-disk**: Different tables can live on different physical disks (separate RocksDB instances)
- **Strengths**: Fast point lookups, efficient prefix/range scans, compact storage
- **Limitations**: Single-node only, no native TTL, no single-property update (rewrites entire value)

### 12.2 EloqRocks (KV-style, embedded, JNI)

- **Session**: Wraps an EloqRocks `TransactionExecution*` handle (C++ pointer cast to `jlong`)
- **Tables**: Each table = an EloqRocks named table (equivalent to a RocksDB column family)
- **Serializer**: `BinarySerializer` — same as RocksDB, all structure encoded in key bytes
- **Transactions**: True ACID transactions via EloqRocks' `StartTx`/`CommitTx`/`AbortTx` with MVCC and conflict detection. Unlike RocksDB's WriteBatch, EloqRocks transactions support read-your-own-writes within a transaction.
- **Bridge**: JNI (Java ↔ C++) via `libeloqjni.so`. Java `byte[]` ↔ C++ `std::string` via raw byte copy.
- **Strengths**: Full ACID transactions with conflict detection, MVCC, sorted key-value semantics, efficient prefix/range scans
- **Limitations**: Single-node only, requires mimalloc LD_PRELOAD (see below), large native library (~237 MB)

#### JNI Native Build Architecture

The EloqRocks backend uses a JNI bridge to call C++ code from Java:

```
Java (EloqNative.java)
  → System.loadLibrary("eloqjni")
    → libeloqjni.so (EloqJNIBridge.cpp)
      → EloqRocksDB::Open() / Service().Put/Get/Delete/Scan
        → data_substrate (tx_service, store_handler)
          → RocksDB (storage engine)
```

**Build chain:**
1. EloqRocks C++ is built with CMake, producing static libraries (`libeloqrocks_lib.a`, `libdata_substrate.a`, `libtxservice.a`, etc.)
2. The JNI Makefile compiles `EloqJNIBridge.cpp` and links it with all static libraries + system shared libraries into `libeloqjni.so`
3. Maven antrun plugin invokes `make` during the compile phase
4. Maven resources plugin copies the .so to the classpath

**Critical requirement:** EloqRocks must be built with `-DCMAKE_POSITION_INDEPENDENT_CODE=ON` because the static `.a` libraries are linked into a shared `.so` object. Without `-fPIC`, the linker fails with relocation errors.

#### mimalloc + JVM Allocator Conflict (LD_PRELOAD)

EloqRocks depends on mimalloc as its memory allocator. Loading `libeloqjni.so` into a running JVM via `dlopen()` (which `System.loadLibrary()` calls internally) creates a critical allocator conflict that causes JVM crashes.

**Root cause:** When the JVM starts, it uses the system's default `malloc`/`free` (glibc) for all internal allocations. When `libeloqjni.so` is loaded later via `dlopen()`, mimalloc's initialization code runs and overrides the global `malloc`/`free` function pointers. This means:
- Memory already allocated by glibc's `malloc` (during JVM startup) is now being freed by mimalloc's `free`
- mimalloc does not recognize glibc-allocated memory and crashes with SIGSEGV when inspecting heap metadata
- Typical crash signatures: `mi_usable_size+0x51`, `_mi_free_block_mt+0x9f`

**Secondary issue:** The `libeloqjni.so` is ~237 MB because it statically links EloqRocks, data_substrate, abseil, and other C++ libraries. This exhausts glibc's default static TLS (Thread-Local Storage) block when loaded via `dlopen()`, producing "cannot allocate memory in static TLS block" errors.

**Approaches tried and their results:**

| Approach | Result | Why It Failed |
|----------|--------|---------------|
| Dynamic `-lmimalloc` (naive) | SIGSEGV in `mi_usable_size` | mimalloc overrides JVM's allocator after JVM already allocated memory |
| Static mimalloc + `--exclude-libs` | SIGSEGV in `_mi_free_block_mt` | Even with symbols hidden, mimalloc's constructor still runs during `dlopen()` and sets up internal state that conflicts with JVM memory |
| `GLIBC_TUNABLES` alone | TLS error fixed, allocator crash remained | Only addresses TLS issue, not the fundamental allocator mismatch |
| **LD_PRELOAD + GLIBC_TUNABLES** | **All tests pass** | mimalloc becomes the global allocator before JVM starts; all allocations are consistent |

**Working solution:** Set two environment variables before the JVM starts:

```bash
export LD_PRELOAD=/usr/local/lib/libmimalloc.so.2
export GLIBC_TUNABLES=glibc.rtld.optional_static_tls=16384
```

- `LD_PRELOAD` ensures mimalloc is the global allocator from process start. The JVM itself, plus all native libraries, use mimalloc consistently. No allocator switch occurs.
- `GLIBC_TUNABLES` increases the static TLS reservation from glibc's default to 16384 bytes, preventing TLS exhaustion when the large `.so` is loaded via `dlopen()`.

These are configured in `pom.xml`'s surefire plugin for tests, and must also be set in any production launcher script (e.g., `start-hugegraph.sh`).

### 12.3 MySQL (Table-style, relational)


- **Session**: Wraps JDBC connection with PreparedStatements
- **Tables**: Each table = one SQL table with CREATE TABLE DDL
- **Serializer**: `TableSerializerV2` — properties stored as JSON in a PROPERTIES column
- **Transactions**: SQL transactions (BEGIN/COMMIT/ROLLBACK)
- **Query**: Builds SQL strings dynamically; all WHERE conditions pushed to MySQL optimizer
- **Strengths**: Full SQL capabilities, range/IN/ORDER BY all native, well-understood tooling
- **Limitations**: Performance at scale, no collection property operations, network overhead

Example SQL for edge query:
```sql
SELECT * FROM edges
WHERE OWNER_VERTEX = ? AND DIRECTION = ? AND LABEL = ?
ORDER BY SORT_VALUES
LIMIT ? OFFSET ?
```

### 12.4 Cassandra (Table-style, distributed)

- **Session**: Wraps Datastax CQL session
- **Tables**: Cassandra tables with partition keys + clustering columns
- **Serializer**: `CassandraSerializer` (extends `TableSerializer`)
- **Transactions**: Batch-level atomicity (not full ACID)
- **Query**: CQL with token-based scanning; `ALLOW FILTERING` for non-key predicates
- **Partition design**: OWNER_VERTEX is partition key for edge table — all edges of a vertex in one partition
- **Strengths**: Horizontal scalability, native TTL, collection types, distributed by default
- **Limitations**: Query model constraints (must query by partition key first), eventual consistency

### 12.5 HStore (Distributed KV, gRPC)

- **Session**: Wraps gRPC client to distributed storage nodes
- **Tables**: Identified by integer codes, owner-based partitioning
- **Serializer**: `BinarySerializer` (same as RocksDB)
- **Query**: Binary protocol with scan/get operations routed to partition owners
- **Strengths**: Distributed with replication, designed for HugeGraph's PD+Store architecture
- **Limitations**: Requires running HugeGraph-PD and HugeGraph-Store separately

---

## 13. Key Source Files

All paths relative to `hugegraph-server/hugegraph-core/src/main/java/org/apache/hugegraph/`:

### Core Interfaces
| File | Purpose |
|---|---|
| `backend/store/BackendStore.java` | Main storage interface |
| `backend/store/BackendStoreProvider.java` | Provider/factory interface |
| `backend/store/AbstractBackendStoreProvider.java` | Base provider with lifecycle coordination |
| `backend/store/BackendSession.java` | Session/transaction interface |
| `backend/store/BackendFeatures.java` | Capability declaration |
| `backend/store/BackendTable.java` | Per-table operations base class |
| `backend/store/BackendEntry.java` | Entry data model + column iterators |
| `backend/store/BackendMutation.java` | Mutation batching with optimization |
| `backend/store/BackendProviderFactory.java` | Provider registry |

### Serialization
| File | Purpose |
|---|---|
| `backend/serializer/AbstractSerializer.java` | Base class; query translation orchestrator |
| `backend/serializer/BinarySerializer.java` | KV-style serialization (key = structure, value = data) |
| `backend/serializer/TableSerializer.java` | Table-style serialization (named columns) |
| `backend/serializer/BinaryBackendEntry.java` | Entry type for KV-style backends |
| `backend/serializer/TableBackendEntry.java` | Entry type for table-style backends |
| `backend/serializer/SerializerFactory.java` | Serializer registry |

### Query Model
| File | Purpose |
|---|---|
| `backend/query/Query.java` | Base query class |
| `backend/query/IdQuery.java` | Query by exact IDs |
| `backend/query/IdPrefixQuery.java` | Prefix scan query |
| `backend/query/IdRangeQuery.java` | Range scan query |
| `backend/query/ConditionQuery.java` | Condition-based query |
| `backend/query/Condition.java` | Condition predicates |

### Query Pipeline
| File | Purpose |
|---|---|
| `backend/tx/GraphTransaction.java` | Query routing, result filtering, TX coordination |
| `backend/tx/GraphIndexTransaction.java` | Index selection and index query execution |
| `traversal/optimize/HugeGraphStep.java` | TinkerPop → HugeGraph query conversion |
| `traversal/optimize/TraversalUtil.java` | HasContainer → Condition conversion |
| `backend/query/ConditionQueryFlatten.java` | IN-clause flattening |

### RocksDB Reference Implementation
All under `hugegraph-server/hugegraph-rocksdb/src/main/java/org/apache/hugegraph/backend/store/rocksdb/`:

| File | Purpose |
|---|---|
| `RocksDBStoreProvider.java` | Provider: creates schema/graph/system stores |
| `RocksDBStore.java` | Store: table dispatch, lifecycle, multi-disk management |
| `RocksDBTable.java` | Table: query routing to session scan/get operations |
| `RocksDBTables.java` | All table definitions (Vertex, Edge, Index types) |
| `RocksDBSessions.java` | Session pool interface with scan type constants |
| `RocksDBStdSessions.java` | Concrete session: WriteBatch, RocksIterator, column families |
| `RocksDBFeatures.java` | Feature declarations |
| `RocksDBOptions.java` | Configuration options (100+) |
