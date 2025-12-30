# KVT Backend Current Status

## Summary
- Core KVT backend integration is present (JNI bridge, sessions, tables, scans, CRUD, transactions).
- Integration now mirrors RocksDB behavior: scan/range queries only, no operator/predicate/aggregation pushdown.
- Condition queries that include non-range filters are rejected (to avoid incorrect results).

## Implemented Capabilities
- JNI bridge for core KVT operations: init/shutdown, transactions, CRUD, scan.
- Table management: create/drop/list, table ID lookup.
- Prefix and range scans aligned with HugeGraph query patterns.
- Batch get path for multiple IDs (chunked JNI arrays).
- Atomic vertex/edge property update via `kvt_process`.

## Not Implemented / Shortcuts To Fix
- KVTBatch uses a placeholder native batch executor and does not perform real writes. This must be implemented before production use.
- Column elimination in `KVTTable.eliminate()` is not implemented.
- `KVTQueryTranslator` still contains a placeholder condition-matching path; it is unused after removing operator pushdown.

## Query Support Notes
- Range and prefix scans are supported.
- Condition queries with filter predicates (non-range) are not supported and throw `NotSupportException`.
- Aggregations (COUNT/SUM/etc.) are performed in Java by iterating results.

## Testing Status
- Test suites exist under `src/test/java` for basic operations, scans, and integration.
- Tests were not run as part of this update.

## Environment Notes
- `kvt_memory.o` is the in-memory test implementation. Production usage assumes a persistent KVT implementation with the same API guarantees.

