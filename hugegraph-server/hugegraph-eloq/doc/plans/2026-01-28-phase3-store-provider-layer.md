# Phase 3: Store & Provider Layer — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Full `BackendStoreProvider` that HugeGraph can load, creating schema/graph/system stores backed by EloqRocks JNI.

**Architecture:** Implements `AbstractBackendStore` with three inner store classes (Schema, Graph, System) that register EloqTable subclasses and dispatch mutations/queries. A simple `AbstractBackendStoreProvider` subclass creates store instances. Registration in `RegisterUtil` makes `backend=eloq` selectable.

**Tech Stack:** Java 11, HugeGraph core interfaces, EloqSessions/EloqTable from Phase 2, BinarySerializer (existing).

---

## Task 1: Create EloqOptions.java

**Files:**
- Create: `hugegraph-server/hugegraph-eloq/src/main/java/org/apache/hugegraph/backend/store/eloq/EloqOptions.java`

Minimal config holder — EloqRocks currently has no user-configurable options (single-node, in-process). Register as an OptionHolder so `OptionSpace.register("eloq", ...)` works.

```java
package org.apache.hugegraph.backend.store.eloq;

import org.apache.hugegraph.config.OptionHolder;

public class EloqOptions extends OptionHolder {

    private static volatile EloqOptions instance;

    public static synchronized EloqOptions instance() {
        if (instance == null) {
            instance = new EloqOptions();
            instance.registerOptions();
        }
        return instance;
    }

    private EloqOptions() {
        super();
    }
}
```

---

## Task 2: Create EloqFeatures.java

**Files:**
- Create: `hugegraph-server/hugegraph-eloq/src/main/java/org/apache/hugegraph/backend/store/eloq/EloqFeatures.java`

Same capability profile as RocksDB (KV-style, binary serializer, sorted key scans).

```java
package org.apache.hugegraph.backend.store.eloq;

import org.apache.hugegraph.backend.store.BackendFeatures;

public class EloqFeatures implements BackendFeatures {
    @Override public boolean supportsSharedStorage()          { return false; }
    @Override public boolean supportsSnapshot()               { return false; }
    @Override public boolean supportsScanToken()              { return false; }
    @Override public boolean supportsScanKeyPrefix()          { return true;  }
    @Override public boolean supportsScanKeyRange()           { return true;  }
    @Override public boolean supportsQuerySchemaByName()      { return false; }
    @Override public boolean supportsQueryByLabel()           { return false; }
    @Override public boolean supportsQueryWithInCondition()   { return false; }
    @Override public boolean supportsQueryWithRangeCondition(){ return true;  }
    @Override public boolean supportsQueryWithOrderBy()       { return true;  }
    @Override public boolean supportsQueryWithContains()      { return false; }
    @Override public boolean supportsQueryWithContainsKey()   { return false; }
    @Override public boolean supportsQueryByPage()            { return true;  }
    @Override public boolean supportsQuerySortByInputIds()    { return true;  }
    @Override public boolean supportsDeleteEdgeByLabel()      { return false; }
    @Override public boolean supportsUpdateVertexProperty()   { return false; }
    @Override public boolean supportsMergeVertexProperty()    { return false; }
    @Override public boolean supportsUpdateEdgeProperty()     { return false; }
    @Override public boolean supportsTransaction()            { return true;  }
    @Override public boolean supportsNumberType()             { return false; }
    @Override public boolean supportsAggregateProperty()      { return false; }
    @Override public boolean supportsTtl()                    { return false; }
    @Override public boolean supportsOlapProperties()         { return true;  }
}
```

---

## Task 3: Create EloqStore.java

**Files:**
- Create: `hugegraph-server/hugegraph-eloq/src/main/java/org/apache/hugegraph/backend/store/eloq/EloqStore.java`
- Reference: `hugegraph-server/hugegraph-rocksdb/.../RocksDBStore.java`

**Design:** Extends `AbstractBackendStore<EloqSessions.EloqSession>`. Single EloqSessions instance (no multi-disk mapping — EloqRocks is single-node). ReadWriteLock for DDL vs DML. Three inner classes: EloqSchemaStore, EloqGraphStore, EloqSystemStore.

**Key simplifications vs RocksDB:**
- No multi-disk mapping (no DATA_DISKS config)
- No snapshot support
- No thread pool for open
- Single EloqSessions instance serves all tables

**Structure:**

```
EloqStore (abstract)
├── fields: store, database, provider, tables, olapTables, sessions, storeLock
├── open(config): create EloqSessions, open()
├── close(): close sessions
├── init(): create all registered tables
├── clear(): drop all tables
├── truncate(): clear + init
├── mutate(mutation): dispatch to tables
├── query(query): dispatch to table
├── beginTx/commitTx/rollbackTx: delegate to session
├── EloqSchemaStore: registers schema tables + Counters
├── EloqGraphStore: registers data + index tables + OLAP tables
└── EloqSystemStore extends EloqGraphStore: adds Meta table, storedVersion
```

---

## Task 4: Create EloqStoreProvider.java

**Files:**
- Create: `hugegraph-server/hugegraph-eloq/src/main/java/org/apache/hugegraph/backend/store/eloq/EloqStoreProvider.java`

Simple provider:

```java
package org.apache.hugegraph.backend.store.eloq;

import org.apache.hugegraph.backend.store.AbstractBackendStoreProvider;
import org.apache.hugegraph.backend.store.BackendStore;
import org.apache.hugegraph.config.HugeConfig;

public class EloqStoreProvider extends AbstractBackendStoreProvider {

    protected String database() {
        return this.graph().toLowerCase();
    }

    @Override
    protected BackendStore newSchemaStore(HugeConfig config, String store) {
        return new EloqStore.EloqSchemaStore(this, this.database(), store);
    }

    @Override
    protected BackendStore newGraphStore(HugeConfig config, String store) {
        return new EloqStore.EloqGraphStore(this, this.database(), store);
    }

    @Override
    protected BackendStore newSystemStore(HugeConfig config, String store) {
        return new EloqStore.EloqSystemStore(this, this.database(), store);
    }

    @Override
    public String type() {
        return "eloq";
    }

    @Override
    public String driverVersion() {
        return "1.0";
    }
}
```

---

## Task 5: Register in RegisterUtil + backend.properties

**Files:**
- Modify: `hugegraph-server/hugegraph-dist/src/main/java/org/apache/hugegraph/dist/RegisterUtil.java`
- Modify: `hugegraph-server/hugegraph-dist/src/main/resources/backend.properties`

In RegisterUtil.java, add case to switch and registration method:
```java
case "eloq":
    registerEloq();
    break;
```

```java
public static void registerEloq() {
    OptionSpace.register("eloq",
                         "org.apache.hugegraph.backend.store.eloq.EloqOptions");
    BackendProviderFactory.register("eloq",
                                    "org.apache.hugegraph.backend.store.eloq" +
                                    ".EloqStoreProvider");
}
```

In backend.properties, add `eloq` to the list:
```
backends=[cassandra, scylladb, rocksdb, mysql, palo, hbase, postgresql, hstore, eloq]
```

---

## Task 6: Write EloqStoreTest.java

**Files:**
- Create: `hugegraph-server/hugegraph-eloq/src/test/java/org/apache/hugegraph/backend/store/eloq/EloqStoreTest.java`

Test store lifecycle and basic operations through the BackendStore API.

**Tests:**
1. `testStoreOpenClose` — open store, verify opened(), close
2. `testInitAndClear` — init creates tables, clear drops them
3. `testMutateAndQuery` — insert entry via mutate, query it back
4. `testCommitAndRollback` — verify transaction semantics
5. `testSchemaStoreCounters` — test increaseCounter/getCounter
6. `testSystemStoreVersion` — test storedVersion after init

---

## Task 7: Compile and run all tests

```bash
mvn compile -pl hugegraph-server/hugegraph-eloq -q
mvn test -pl hugegraph-server/hugegraph-eloq -Dtest="EloqNativeTest,EloqSessionsTest,EloqTableTest,EloqStoreTest"
```

**Pass criteria:** All tests green (24 existing + new store tests).

---

## Execution order

1. EloqOptions.java (trivial)
2. EloqFeatures.java (trivial)
3. EloqStore.java (main work)
4. EloqStoreProvider.java (trivial)
5. RegisterUtil + backend.properties (2 edits)
6. EloqStoreTest.java
7. Compile + test + commit
