import org.apache.hugegraph.backend.store.kvt.KVTNative;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This is a stress test for the KVT backend.
 * This follow the design of kvt_stress_test.cpp
 * It is used to test the consistency of the KVT backend.
 

**kvt_stress_test.cpp**: Comprehensive stress testing with invariant checking

## Stress Test Design

The stress test validates transactional consistency using a constraint-based approach:

### Key Concepts
- **Key Range**: 0-9999 (fixed-width string format for proper ordering)
- **Value Range**: 0-999 (string format)
- **Consistency Ranges**: Keys grouped into 100-key ranges (0-99, 100-199, etc.)
- **Invariant**: Sum of values in each 100-key range must be divisible by 100

### Transaction Constraints
Each transaction must maintain the invariant for all modified ranges:
- When deleting a key, must adjust other keys in the same range to maintain sum divisibility
- When adding keys, must ensure the total change is divisible by 100
- Transactions violating constraints are intentionally aborted

### Test Modes
1. **Single Non-Interleaved**: Sequential transaction execution
2. **Single Interleaved**: Multiple concurrent transactions, single-threaded
3. **Multi-Threaded**: True concurrent execution with multiple threads

### Test Process
1. **Initialization**: Populate ~2000 keys ensuring initial consistency
2. **Transaction Generation**: 
   - 1-20 operations per transaction
   - 1-4 consistency ranges per transaction
   - Predetermined commit/abort decision (70% commit ratio)
3. **Execution**: Random operations with constraint tracking
4. **Validation**: Periodic consistency checks via full or range scans
5. **Error Detection**: Immediate test termination on constraint violation

 */

public class KVTStressTest {
    // Configuration constants
    private static final int MAX_KEY = 10000;
    private static final int MAX_VALUE = 1000;
    private static final int RANGE_SIZE = 100;
    private static final int MAX_RANGES = MAX_KEY / RANGE_SIZE;
    private static final int MAX_OPS_PER_TX = 20;
    private static final int MIN_OPS_PER_TX = 1;
    private static final int MAX_RANGES_PER_TX = 4;
    private static final int INITIAL_KEYS = 2000;
    private static final int CONSISTENCY_CHECK_INTERVAL = 100;
    private static final int MAX_CONCURRENT_TXS = 10;
    private static final double COMMIT_RATIO = 0.7; // 70% commit, 30% abort
    private static final int CONSTRAINT_DIVISOR = 100;
    
    // Test modes
    enum TestMode {
        SINGLE_NON_INTERLEAVED,
        SINGLE_INTERLEAVED,
        MULTI_THREADED
    }
    
    // Operation types
    enum OpType {
        OP_GET,
        OP_SET,
        OP_DEL,
        OP_SCAN
    }
    
    // Operation record
    static class Operation {
        OpType opType;
        int key;
        int key2; // For scan end key
        int value;
        int value2; // For scan result count
        
        Operation(OpType opType, int key, int key2, int value, int value2) {
            this.opType = opType;
            this.key = key;
            this.key2 = key2;
            this.value = value;
            this.value2 = value2;
        }
    }
    
    // Transaction context
    static class TransactionContext {
        long txId;
        boolean shouldCommit;
        int numOps;
        List<Integer> ranges = new ArrayList<>();
        Random rng;
        List<Operation> operations = new ArrayList<>();
        
        void appendOp(Operation op) {
            operations.add(op);
        }
        
        void printOpList() {
            System.out.println("TX " + txId + " Op List:");
            int i = 0;
            for (Operation op : operations) {
                System.out.printf("\tOp %d: %s Key: %d Value: %d\n", 
                    i++, op.opType, op.key, op.value);
            }
        }
    }
    
    // Main test class fields
    private final Random mainRng = new Random(42); // Fixed seed for debugging
    private String tableName = "stress_test_table";
    private long tableId;
    private final AtomicInteger transactionCount = new AtomicInteger(0);
    private final AtomicInteger commitCount = new AtomicInteger(0);
    private final AtomicInteger abortCount = new AtomicInteger(0);
    private final AtomicBoolean testRunning = new AtomicBoolean(true);
    
    // Helper methods
    private int getRangeId(int key) {
        return key / RANGE_SIZE;
    }
    
    private String keyToString(int key) {
        // Zero-pad to 5 digits for proper ordering
        return String.format("%05d", key);
    }
    
    private String valueToString(int value) {
        return String.valueOf(value);
    }
    
    private int stringToValue(String str) {
        return Integer.parseInt(str);
    }
    
    private int stringToKey(String str) {
        return Integer.parseInt(str);
    }
    
    private int clampValue(int value) {
        return value % MAX_VALUE;
    }
    
    // Initialize the stress test
    public boolean initialize() {
        System.out.println("Initializing stress test...");
        
        // Initialize KVT
        KVTNative.KVTError initError = KVTNative.initialize();
        if (initError != KVTNative.KVTError.SUCCESS) {
            System.err.println("Failed to initialize KVT: " + initError);
            System.exit(1);
        }
        
        // Create table
        KVTNative.KVTResult<Long> tableResult = KVTNative.createTable(tableName, "range");
        if (!tableResult.isSuccess()) {
            System.err.println("Failed to create table: " + tableResult.error);
            System.exit(1);
        }
        tableId = tableResult.value;
        
        // Populate initial data
        populateInitialData();
        
        // Check initial consistency
        checkConsistency("initial");
        
        System.out.println("Initialization complete!");
        return true;
    }
    
    // Populate initial data ensuring constraint satisfaction
    private void populateInitialData() {
        System.out.println("Populating initial data...");
        int keysEachRange = INITIAL_KEYS / MAX_RANGES;
        
        for (int rangeId = 0; rangeId < MAX_RANGES; rangeId++) {
            int rangeSum = 0;
            Map<Integer, Integer> keyValues = new HashMap<>();
            
            // Generate random key-value pairs for this range
            for (int i = 0; i < keysEachRange; i++) {
                int key = rangeId * RANGE_SIZE + mainRng.nextInt(RANGE_SIZE);
                int value = mainRng.nextInt(MAX_VALUE);
                
                if (keyValues.containsKey(key)) {
                    i--; // Try again with different key
                    continue;
                }
                
                keyValues.put(key, value);
                rangeSum += value;
            }
            
            // Adjust first key to make sum divisible by CONSTRAINT_DIVISOR
            if (!keyValues.isEmpty()) {
                int diff = CONSTRAINT_DIVISOR - (rangeSum % CONSTRAINT_DIVISOR);
                if (diff != CONSTRAINT_DIVISOR) {
                    Map.Entry<Integer, Integer> firstEntry = keyValues.entrySet().iterator().next();
                    keyValues.put(firstEntry.getKey(), firstEntry.getValue() + diff);
                }
            }
            
            // Write all key-value pairs
            for (Map.Entry<Integer, Integer> entry : keyValues.entrySet()) {
                KVTNative.KVTResult<Void> result = KVTNative.set(0, tableId, 
                    keyToString(entry.getKey()).getBytes(), 
                    valueToString(entry.getValue()).getBytes());
                if (!result.isSuccess()) {
                    System.err.println("Failed to populate initial data: " + result.error);
                    System.exit(1);
                }
            }
        }
    }
    
    // Check consistency constraints
    private boolean checkConsistency(String context) {
        System.out.println("Checking consistency: " + context);
        
        // Scan entire key range
        Object[] scanResult = KVTNative.nativeScan(0, tableId, 
            keyToString(0).getBytes(), keyToString(MAX_KEY).getBytes(), MAX_KEY);
        
        KVTNative.KVTError scanError = KVTNative.KVTError.fromCode((Integer)scanResult[0]);
        if (scanError != KVTNative.KVTError.SUCCESS) {
            System.err.println("Failed to scan database: " + scanError);
            System.exit(1);
        }
        
        byte[][] keys = (byte[][])scanResult[1];
        byte[][] values = (byte[][])scanResult[2];
        
        // Check constraints - results are sorted
        int currentRange = -1;
        int currentRangeSum = 0;
        
        for (int i = 0; i < keys.length; i++) {
            int key = stringToKey(new String(keys[i]));
            int value = stringToValue(new String(values[i]));
            int rangeId = getRangeId(key);
            
            // If we moved to a new range, check the previous range's sum
            if (rangeId != currentRange) {
                if (currentRange != -1 && currentRangeSum > 0 && currentRangeSum % CONSTRAINT_DIVISOR != 0) {
                    System.err.println("CONSISTENCY VIOLATION in range " + currentRange + 
                        ": sum=" + currentRangeSum + " (not divisible by " + CONSTRAINT_DIVISOR + ")");
                    
                    // Debug: print all keys in the violated range
                    System.err.println("Keys in violated range " + currentRange + ":");
                    for (int j = 0; j < keys.length; j++) {
                        int debugKey = stringToKey(new String(keys[j]));
                        if (getRangeId(debugKey) == currentRange) {
                            int debugValue = stringToValue(new String(values[j]));
                            System.err.println("  Key " + debugKey + " = " + debugValue);
                        }
                    }
                    System.exit(1);
                }
                
                // Start new range
                currentRange = rangeId;
                currentRangeSum = 0;
            }
            
            currentRangeSum += value;
        }
        
        // Check the last range
        if (currentRange != -1 && currentRangeSum > 0 && currentRangeSum % CONSTRAINT_DIVISOR != 0) {
            System.err.println("CONSISTENCY VIOLATION in range " + currentRange + 
                ": sum=" + currentRangeSum + " (not divisible by " + CONSTRAINT_DIVISOR + ")");
            System.exit(1);
        }
        
        return true;
    }
    
    // Create a new transaction context
    private TransactionContext createTransactionContext() {
        TransactionContext ctx = new TransactionContext();
        
        // Start transaction
        KVTNative.KVTResult<Long> txResult = KVTNative.startTransaction();
        if (!txResult.isSuccess()) {
            System.err.println("Failed to start transaction: " + txResult.error);
            System.exit(1);
        }
        ctx.txId = txResult.value;
        
        // Determine if should commit or abort
        ctx.shouldCommit = mainRng.nextDouble() < COMMIT_RATIO;
        ctx.numOps = MIN_OPS_PER_TX + mainRng.nextInt(MAX_OPS_PER_TX - MIN_OPS_PER_TX + 1);
        
        // Select random ranges
        int numRanges = 1 + mainRng.nextInt(MAX_RANGES_PER_TX);
        for (int i = 0; i < numRanges; i++) {
            ctx.ranges.add(mainRng.nextInt(MAX_RANGES));
        }
        
        // Create new RNG for this transaction
        ctx.rng = new Random(mainRng.nextInt(1000000));
        
        return ctx;
    }
    
    // Try to get a key from the range
    private int tryGetKey(TransactionContext ctx, int rangeId, boolean checkPrevious) {
        // If previous op was a GET, reuse that key
        if (checkPrevious && !ctx.operations.isEmpty() && 
            ctx.operations.get(ctx.operations.size() - 1).opType == OpType.OP_GET) {
            return ctx.operations.get(ctx.operations.size() - 1).key;
        }
        
        // Try to find an existing key
        for (int i = 0; i < 10; i++) {
            int key = rangeId * RANGE_SIZE + ctx.rng.nextInt(RANGE_SIZE);
            
            KVTNative.KVTResult<byte[]> getResult = KVTNative.get(ctx.txId, tableId, 
                keyToString(key).getBytes());
            
            if (getResult.isSuccess()) {
                int value = stringToValue(new String(getResult.value));
                ctx.appendOp(new Operation(OpType.OP_GET, key, 0, value, 0));
                return key;
            } else if (getResult.error == KVTNative.KVTError.KEY_IS_LOCKED ||
                       getResult.error == KVTNative.KVTError.KEY_NOT_FOUND ||
                       getResult.error == KVTNative.KVTError.KEY_IS_DELETED) {
                continue; // Try another key
            } else {
                System.err.println("Failed to get key: " + getResult.error);
                System.exit(1);
            }
        }
        
        return -1; // Could not find a key
    }
    
    // Execute a single operation
    private void executeSingleOperation(TransactionContext ctx) {
        int rangeIdx = ctx.rng.nextInt(ctx.ranges.size());
        int rangeId = ctx.ranges.get(rangeIdx);
        
        double prob = ctx.rng.nextDouble();
        
        if (prob < 0.4) { // GET operation (40%)
            tryGetKey(ctx, rangeId, false);
            
        } else if (prob < 0.8) { // SET operation (40%)
            int key = tryGetKey(ctx, rangeId, true);
            if (key == -1) return; // Skip if no key found
            
            int newValue = clampValue(ctx.rng.nextInt(MAX_VALUE));
            KVTNative.KVTResult<Void> result = KVTNative.set(ctx.txId, tableId,
                keyToString(key).getBytes(), valueToString(newValue).getBytes());
            
            if (!result.isSuccess()) {
                System.err.println("Failed to set key: " + result.error);
                System.exit(1);
            }
            ctx.appendOp(new Operation(OpType.OP_SET, key, 0, newValue, 0));
            
        } else if (prob < 0.9) { // DELETE operation (10%)
            int key = tryGetKey(ctx, rangeId, true);
            if (key == -1) return; // Skip if no key found
            
            KVTNative.KVTResult<Void> result = KVTNative.del(ctx.txId, tableId,
                keyToString(key).getBytes());
            
            if (!result.isSuccess()) {
                System.err.println("Failed to delete key: " + result.error);
                System.exit(1);
            }
            ctx.appendOp(new Operation(OpType.OP_DEL, key, 0, 0, 0));
            
        } else { // SCAN operation (10%)
            int startKey = ctx.rng.nextInt(MAX_KEY);
            int endKey = ctx.rng.nextInt(MAX_KEY);
            if (startKey > endKey) {
                int tmp = startKey;
                startKey = endKey;
                endKey = tmp;
            }
            
            Object[] scanResult = KVTNative.nativeScan(ctx.txId, tableId,
                keyToString(startKey).getBytes(), keyToString(endKey).getBytes(), RANGE_SIZE);
            
            KVTNative.KVTError scanError = KVTNative.KVTError.fromCode((Integer)scanResult[0]);
            if (scanError != KVTNative.KVTError.SUCCESS) {
                System.err.println("Failed to scan: " + scanError);
                System.exit(1);
            }
            
            byte[][] resultKeys = (byte[][])scanResult[1];
            int resultCount = resultKeys != null ? resultKeys.length : 0;
            ctx.appendOp(new Operation(OpType.OP_SCAN, startKey, endKey, 0, resultCount));
        }
    }
    
    // Fix constraints to ensure divisibility rule
    private void fixConstraint(TransactionContext ctx) {
        // Calculate delta sum for each modified range
        Map<Integer, Integer> rangeDeltaSum = new HashMap<>();
        for (int rangeId : ctx.ranges) {
            rangeDeltaSum.put(rangeId, 0);
        }
        
        // Calculate deltas from operations
        for (int i = 0; i < ctx.operations.size(); i++) {
            Operation op = ctx.operations.get(i);
            
            if (op.opType == OpType.OP_SET) {
                // A SET is always preceded by a GET
                if (i > 0 && ctx.operations.get(i-1).opType == OpType.OP_GET) {
                    int prevValue = ctx.operations.get(i-1).value;
                    int newValue = op.value;
                    int rangeId = op.key / RANGE_SIZE;
                    rangeDeltaSum.put(rangeId, rangeDeltaSum.get(rangeId) + (newValue - prevValue));
                }
            } else if (op.opType == OpType.OP_DEL) {
                // A DEL is always preceded by a GET
                if (i > 0 && ctx.operations.get(i-1).opType == OpType.OP_GET) {
                    int prevValue = ctx.operations.get(i-1).value;
                    int rangeId = op.key / RANGE_SIZE;
                    rangeDeltaSum.put(rangeId, rangeDeltaSum.get(rangeId) - prevValue);
                }
            }
        }
        
        // Fix each range to maintain constraint
        for (Map.Entry<Integer, Integer> entry : rangeDeltaSum.entrySet()) {
            int rangeId = entry.getKey();
            int deltaSum = entry.getValue();
            int diff = (CONSTRAINT_DIVISOR - (deltaSum % CONSTRAINT_DIVISOR)) % CONSTRAINT_DIVISOR;
            
            if (diff != 0) {
                // Need to adjust a key in this range
                boolean done = false;
                int attempts = 0;
                
                while (!done && attempts < 100) {
                    attempts++;
                    int key = rangeId * RANGE_SIZE + ctx.rng.nextInt(RANGE_SIZE);
                    
                    KVTNative.KVTResult<byte[]> getResult = KVTNative.get(ctx.txId, tableId,
                        keyToString(key).getBytes());
                    
                    if (getResult.isSuccess()) {
                        // Key exists, update it
                        int existingValue = stringToValue(new String(getResult.value));
                        ctx.appendOp(new Operation(OpType.OP_GET, key, 0, existingValue, 0));
                        
                        int newValue = clampValue(existingValue + diff);
                        KVTNative.KVTResult<Void> setResult = KVTNative.set(ctx.txId, tableId,
                            keyToString(key).getBytes(), valueToString(newValue).getBytes());
                        
                        if (setResult.isSuccess()) {
                            ctx.appendOp(new Operation(OpType.OP_SET, key, 0, newValue, 0));
                            done = true;
                        }
                    } else if (getResult.error == KVTNative.KVTError.KEY_NOT_FOUND ||
                              getResult.error == KVTNative.KVTError.KEY_IS_DELETED) {
                        // Key doesn't exist, create it
                        int newValue = diff;
                        KVTNative.KVTResult<Void> setResult = KVTNative.set(ctx.txId, tableId,
                            keyToString(key).getBytes(), valueToString(newValue).getBytes());
                        
                        if (setResult.isSuccess()) {
                            ctx.appendOp(new Operation(OpType.OP_SET, key, 0, newValue, 0));
                            done = true;
                        }
                    }
                }
                
                if (!done) {
                    System.err.println("Failed to fix constraint for range " + rangeId);
                    // Force abort this transaction
                    ctx.shouldCommit = false;
                }
            }
        }
    }
    
    // Execute a complete transaction
    private void executeTransaction(TransactionContext ctx) {
        // Execute operations
        for (int i = 0; i < ctx.numOps; i++) {
            executeSingleOperation(ctx);
        }
        
        // Fix constraints if we plan to commit
        if (ctx.shouldCommit) {
            fixConstraint(ctx);
        }
        
        // Commit or abort
        if (ctx.shouldCommit) {
            KVTNative.KVTResult<Void> result = KVTNative.commitTransaction(ctx.txId);
            if (result.isSuccess()) {
                commitCount.incrementAndGet();
            } else {
                // Commit failed, count as abort
                abortCount.incrementAndGet();
                if (result.error != KVTNative.KVTError.TRANSACTION_HAS_STALE_DATA) {
                    System.err.println("Unexpected commit failure: " + result.error);
                }
            }
        } else {
            KVTNative.KVTResult<Void> result = KVTNative.rollbackTransaction(ctx.txId);
            if (!result.isSuccess()) {
                System.err.println("Failed to rollback transaction: " + result.error);
            }
            abortCount.incrementAndGet();
        }
        
        transactionCount.incrementAndGet();
    }
    
    // Run the stress test
    public void runTest(TestMode mode, int numTransactions) {
        System.out.println("Running stress test in mode: " + mode);
        System.out.println("Target transactions: " + numTransactions);
        
        long startTime = System.currentTimeMillis();
        
        switch (mode) {
            case SINGLE_NON_INTERLEAVED:
                runSingleNonInterleaved(numTransactions);
                break;
            case SINGLE_INTERLEAVED:
                runSingleInterleaved(numTransactions);
                break;
            case MULTI_THREADED:
                runMultiThreaded(numTransactions);
                break;
        }
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        // Final consistency check
        checkConsistency("final");
        
        // Print statistics
        System.out.println("\n=== STRESS TEST COMPLETED SUCCESSFULLY ===");
        System.out.println("Duration: " + duration + " ms");
        System.out.println("Total transactions: " + transactionCount.get());
        System.out.println("Committed: " + commitCount.get());
        System.out.println("Aborted: " + abortCount.get());
        System.out.println("Throughput: " + (transactionCount.get() * 1000.0 / duration) + " tx/s");
    }
    
    // Single-threaded, non-interleaved execution
    private void runSingleNonInterleaved(int numTransactions) {
        for (int i = 0; i < numTransactions; i++) {
            TransactionContext ctx = createTransactionContext();
            executeTransaction(ctx);
            
            // Periodic consistency check
            if ((i + 1) % CONSISTENCY_CHECK_INTERVAL == 0) {
                checkConsistency("transaction " + (i + 1));
                System.out.println("Progress: " + (i + 1) + "/" + numTransactions);
            }
        }
    }
    
    // Single-threaded, interleaved execution
    private void runSingleInterleaved(int numTransactions) {
        List<TransactionContext> activeTransactions = new ArrayList<>();
        int completedTransactions = 0;
        
        while (completedTransactions < numTransactions) {
            // Start new transactions if below limit
            while (activeTransactions.size() < MAX_CONCURRENT_TXS && 
                   completedTransactions + activeTransactions.size() < numTransactions) {
                activeTransactions.add(createTransactionContext());
            }
            
            // Execute one operation from a random transaction
            if (!activeTransactions.isEmpty()) {
                int txIdx = mainRng.nextInt(activeTransactions.size());
                TransactionContext ctx = activeTransactions.get(txIdx);
                
                if (ctx.operations.size() < ctx.numOps) {
                    executeSingleOperation(ctx);
                } else {
                    // Transaction complete, commit/abort
                    if (ctx.shouldCommit) {
                        fixConstraint(ctx);
                        KVTNative.KVTResult<Void> result = KVTNative.commitTransaction(ctx.txId);
                        if (result.isSuccess()) {
                            commitCount.incrementAndGet();
                        } else {
                            abortCount.incrementAndGet();
                        }
                    } else {
                        KVTNative.rollbackTransaction(ctx.txId);
                        abortCount.incrementAndGet();
                    }
                    
                    transactionCount.incrementAndGet();
                    completedTransactions++;
                    activeTransactions.remove(txIdx);
                    
                    // Periodic consistency check
                    if (completedTransactions % CONSISTENCY_CHECK_INTERVAL == 0) {
                        checkConsistency("transaction " + completedTransactions);
                        System.out.println("Progress: " + completedTransactions + "/" + numTransactions);
                    }
                }
            }
        }
    }
    
    // Multi-threaded execution
    private void runMultiThreaded(int numTransactions) {
        int numThreads = Math.min(4, Runtime.getRuntime().availableProcessors());
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numTransactions);
        
        for (int i = 0; i < numTransactions; i++) {
            final int txNum = i;
            executor.submit(() -> {
                try {
                    TransactionContext ctx = createTransactionContext();
                    executeTransaction(ctx);
                    
                    // Periodic consistency check (synchronized)
                    if ((txNum + 1) % CONSISTENCY_CHECK_INTERVAL == 0) {
                        synchronized (this) {
                            checkConsistency("transaction " + (txNum + 1));
                            System.out.println("Progress: " + transactionCount.get() + "/" + numTransactions);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Transaction failed: " + e.getMessage());
                    e.printStackTrace();
                    System.exit(1);
                } finally {
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await();
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            System.err.println("Test interrupted");
            System.exit(1);
        }
    }
    
    // Cleanup
    public void cleanup() {
        System.out.println("Cleaning up...");
        
        // Drop table
        KVTNative.KVTResult<Void> dropResult = KVTNative.dropTable(tableId);
        if (!dropResult.isSuccess()) {
            System.err.println("Failed to drop table: " + dropResult.error);
        }
        
        // Shutdown KVT
        KVTNative.shutdown();
        System.out.println("Cleanup complete");
    }
    
    // Main entry point
    public static void main(String[] args) {
        System.out.println("=== KVT STRESS TEST ===\n");
        
        // Parse arguments
        TestMode mode = TestMode.SINGLE_NON_INTERLEAVED;
        int numTransactions = 1000;
        
        if (args.length >= 1) {
            numTransactions = Integer.parseInt(args[0]);
        }
        if (args.length >= 2) {
            switch (args[1]) {
                case "non-interleaved":
                    mode = TestMode.SINGLE_NON_INTERLEAVED;
                    break;
                case "interleaved":
                    mode = TestMode.SINGLE_INTERLEAVED;
                    break;
                case "multi-threaded":
                    mode = TestMode.MULTI_THREADED;
                    break;
                default:
                    System.err.println("Invalid mode. Use: non-interleaved, interleaved, or multi-threaded");
                    System.exit(1);
            }
        }
        
        KVTStressTest test = new KVTStressTest();
        
        try {
            test.initialize();
            test.runTest(mode, numTransactions);
            test.cleanup();
            
            System.out.println("\n✓ All tests passed successfully!");
            
        } catch (Exception e) {
            System.err.println("\n✗ Stress test failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}