# Bug: EloqRocks LogShippingAgent Assertion Crash on Reopen with Stale Data

## Summary

When the EloqRocks-backed HugeGraph test JVM is killed mid-execution (e.g., by `timeout`, SIGTERM, or SIGKILL) and a new JVM is started against the same data directory (`/tmp/eloq_data/`), the C++ layer crashes with:

```
log_shipping_agent.h:593: int txlog::LogShippingAgent::WriteToStreamInBatch(
    txlog::ItemIterator*, std::atomic<int>&, std::atomic<int>&):
    Assertion `latest_txn_no_ <= global_latest_txn_no' failed.
```

This SIGABRT kills the JVM, producing "The forked VM terminated without properly saying goodbye" in Maven surefire output.

## Symptom

Running `VertexCoreTest` or `EdgeCoreTest` as a full class appears to **hang indefinitely**. In reality, it is one of two things:

1. **Timeout too short**: The full VertexCoreTest (261 methods) takes ~433 seconds with clean data. A 90-second timeout kills the run prematurely.
2. **Crash on reopen**: After a killed run leaves stale data in `/tmp/eloq_data/`, the next JVM crashes during EloqRocks initialization at the `LogShippingAgent` assertion.

The "hang" reported in CLAUDE.md's Phase 4 section is a combination of both: the first run times out, and subsequent runs crash immediately.

## Root Cause

The assertion `latest_txn_no_ <= global_latest_txn_no` in `LogShippingAgent::WriteToStreamInBatch` (file: `data_substrate/log_service/include/log_shipping_agent.h:593`) fails when:

1. A previous JVM wrote transactions to the EloqRocks log, incrementing `latest_txn_no_` in the log records on disk.
2. The JVM was killed before `global_latest_txn_no` was fully persisted/flushed.
3. On the next JVM startup, the log service recovers `latest_txn_no_` from the log records (higher value) but loads `global_latest_txn_no` from its persisted state (lower/stale value).
4. The invariant `latest_txn_no_ <= global_latest_txn_no` is violated, triggering `assert()` -> `SIGABRT` -> JVM crash.

The crash manifests in the dumpstream as:
```
java: /home/.../log_shipping_agent.h:593: ... Assertion `latest_txn_no_ <= global_latest_txn_no' failed.
Aborted (core dumped)
```

After the crash, subsequent operations fail with:
```
BackendException: Failed to start EloqRocks transaction
```
and the shutdown hook fails with:
```
AssertionError: 1 (at TaskManager.shutdown)
```

## What Does NOT Cause the Issue

Through systematic testing, the following were **ruled out**:

| Scenario | Result |
|----------|--------|
| Running all 261 VertexCoreTest methods in a single JVM with clean data | Completes in ~522s (4 failures, 3 errors, 17 skipped — all expected) |
| Running 100 tests, then another 100 tests sequentially (clean shutdown between) | Both runs pass |
| Running 10 batches of 10 tests sequentially without cleaning data | All 10 batches pass |
| Running a single test, then another single test without cleaning | Both pass |
| Killing a JVM with SIGKILL after 20 seconds, then running another test | Second test passes |

The issue specifically requires the data directory to be in an inconsistent state from a previous abnormal termination that left the log service metadata out of sync.

## How to Reproduce

### Reliable reproduction

The crash was reliably observed under these conditions:

```bash
# 1. Run the full test class with a timeout SHORTER than needed (full run takes ~433s)
timeout 90 mvn test -pl hugegraph-server/hugegraph-test -Peloq \
    -Dtest="VertexCoreTest" -DfailIfNoTests=false

# 2. Immediately run any test again (stale data from killed run)
mvn test -pl hugegraph-server/hugegraph-test -Peloq \
    -Dtest="VertexCoreTest#testAddVertex" -DfailIfNoTests=false
# -> Crashes with assertion failure
```

The key conditions are:
- The first run must execute enough transactions before being killed (a few seconds of test execution seems sufficient during the full-class run, but the exact threshold is hard to pin down)
- The data directory `/tmp/eloq_data/` must NOT be cleaned between runs

### Workaround (clean data before each run)

```bash
rm -rf /tmp/eloq_data
mvn test -pl hugegraph-server/hugegraph-test -Peloq \
    -Dtest="VertexCoreTest" -DfailIfNoTests=false
```

### Concurrent access (different failure)

If two JVMs try to open the same data directory simultaneously, the second gets a RocksDB LOCK error instead of the assertion failure:
```
Failed to open the RocksDB log, error: IO error: While lock file:
/tmp/eloq_data/log_service/rocksdb/LOCK: Resource temporarily unavailable
```

## Files Involved

| File | Role |
|------|------|
| `eloqrocks/data_substrate/log_service/include/log_shipping_agent.h:593` | Assertion that fails |
| `eloqrocks/data_substrate/log_service/src/open_log_service.cpp` | Log service initialization |
| `eloqrocks/data_substrate/log_service/src/log_state_rocksdb_impl.cpp` | RocksDB-based log state persistence |
| `eloqrocks/src/eloqrocks.cpp` | EloqRocksDB::Open entry point |
| `src/main/native/EloqJNIBridge.cpp` | JNI bridge that calls EloqRocksDB::Open |
| `src/main/java/.../eloq/EloqSessions.java` | Java-side session manager that calls native init |

## Recommended Fix

The assertion `latest_txn_no_ <= global_latest_txn_no` is too strict for crash recovery scenarios. Options:

1. **Recovery mode**: On startup, if `latest_txn_no_ > global_latest_txn_no`, update `global_latest_txn_no` to match `latest_txn_no_` (reconcile from log records) rather than asserting.
2. **Graceful degradation**: Replace `assert()` with a recovery procedure that truncates or replays the out-of-sync log entries.
3. **Java-side cleanup**: Have the HugeGraph adapter delete the data directory on startup if it detects a previous unclean shutdown (e.g., via a lock file or sentinel file).
4. **Flush on shutdown**: Ensure `global_latest_txn_no` is flushed to persistent storage during the shutdown hook, so even SIGTERM-triggered shutdowns leave consistent state.

## Test Timing Reference

| Batch Size | Elapsed Time | Notes |
|------------|-------------|-------|
| 1 test | ~6s | |
| 10 tests | ~21s | |
| 30 tests | ~45s | |
| 40 tests | ~71s | |
| 50 tests | ~142s | TTL tests at positions 41-50 are slow (~70s) |
| 100 tests | ~198s | 1 failure, 1 error, 8 skipped |
| 261 tests (all) | ~522s | 4 failures, 3 errors, 17 skipped |
