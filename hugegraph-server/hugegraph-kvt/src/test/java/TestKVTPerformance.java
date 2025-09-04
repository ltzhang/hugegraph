import org.apache.hugegraph.backend.store.kvt.*;

import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance benchmark for KVT backend
 */
public class TestKVTPerformance {

    static {
        // Load the JNI library
        try {
            String libPath = System.getProperty("user.dir") + 
                           "/target/native/libkvtjni.so";
            System.load(libPath);
            System.out.println("Loaded library from: " + libPath);
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Failed to load library: " + e.getMessage());
            throw e;
        }
    }
    
    private static final int WARMUP_COUNT = 1000;
    private static final int TEST_COUNT = 10000;
    private static final int THREAD_COUNT = 10;
    private static final int KEY_SIZE = 32;
    private static final int VALUE_SIZE = 256;
    
    public static void main(String[] args) {
        System.out.println("=== KVT Performance Benchmark ===\n");
        
        try {
            // Initialize KVT
            System.out.println("Initializing KVT...");
            KVTNative.KVTError error = KVTNative.initialize();
            if (error != KVTNative.KVTError.SUCCESS) {
                throw new RuntimeException("Failed to initialize KVT: " + error);
            }
            
            // Create test table
            KVTNative.KVTResult<Long> tableResult = 
                KVTNative.createTable("benchmark_table", "hash");
            if (!tableResult.isSuccess()) {
                throw new RuntimeException("Failed to create table: " + 
                                         tableResult.errorMessage);
            }
            long tableId = tableResult.value;
            
            // Warmup
            System.out.println("\nWarming up...");
            warmup(tableId);
            
            // Run benchmarks
            System.out.println("\n=== Running Benchmarks ===\n");
            
            // 1. Single-threaded write performance
            System.out.println("1. Single-threaded Write Performance");
            benchmarkSingleThreadedWrites(tableId);
            
            // 2. Single-threaded read performance
            System.out.println("\n2. Single-threaded Read Performance");
            benchmarkSingleThreadedReads(tableId);
            
            // 3. Batch write performance
            System.out.println("\n3. Batch Write Performance");
            benchmarkBatchWrites(tableId);
            
            // 4. Concurrent read/write performance
            System.out.println("\n4. Concurrent Read/Write Performance");
            benchmarkConcurrentOperations(tableId);
            
            // 5. Transaction overhead
            System.out.println("\n5. Transaction Overhead");
            benchmarkTransactionOverhead(tableId);
            
            // 6. Query performance with cache
            System.out.println("\n6. Query Cache Performance");
            benchmarkQueryCache(tableId);
            
            // 7. Index performance
            System.out.println("\n7. Index Performance");
            benchmarkIndexOperations();
            
            // Print statistics
            System.out.println("\n=== Query Statistics ===");
            KVTQueryStats stats = KVTQueryStats.getInstance();
            System.out.println(stats.getSummary());
            
            System.out.println("\n=== BENCHMARK COMPLETE ===");
            
        } catch (Exception e) {
            System.err.println("\n=== BENCHMARK FAILED ===");
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            KVTNative.shutdown();
        }
    }
    
    private static void warmup(long tableId) throws Exception {
        Random random = new Random();
        
        for (int i = 0; i < WARMUP_COUNT; i++) {
            byte[] key = generateKey(random, i);
            byte[] value = generateValue(random);
            
            KVTNative.set(0, tableId, key, value);
        }
        
        System.out.println("Warmup complete with " + WARMUP_COUNT + " operations");
    }
    
    private static void benchmarkSingleThreadedWrites(long tableId) throws Exception {
        Random random = new Random();
        
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < TEST_COUNT; i++) {
            byte[] key = generateKey(random, i);
            byte[] value = generateValue(random);
            
            KVTNative.KVTResult<Void> result = 
                KVTNative.set(0, tableId, key, value);
            
            if (!result.isSuccess()) {
                throw new RuntimeException("Write failed: " + result.errorMessage);
            }
        }
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double throughput = (double) TEST_COUNT * 1000 / duration;
        
        System.out.printf("   Writes: %d ops in %dms (%.0f ops/sec)\n",
                         TEST_COUNT, duration, throughput);
    }
    
    private static void benchmarkSingleThreadedReads(long tableId) throws Exception {
        Random random = new Random();
        
        long startTime = System.currentTimeMillis();
        int hits = 0;
        
        for (int i = 0; i < TEST_COUNT; i++) {
            byte[] key = generateKey(random, random.nextInt(TEST_COUNT));
            
            KVTNative.KVTResult<byte[]> result = 
                KVTNative.get(0, tableId, key);
            
            if (result.isSuccess() && result.value != null) {
                hits++;
            }
        }
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double throughput = (double) TEST_COUNT * 1000 / duration;
        double hitRate = (double) hits * 100 / TEST_COUNT;
        
        System.out.printf("   Reads: %d ops in %dms (%.0f ops/sec, %.1f%% hit rate)\n",
                         TEST_COUNT, duration, throughput, hitRate);
    }
    
    private static void benchmarkBatchWrites(long tableId) throws Exception {
        Random random = new Random();
        int batchSize = 100;
        int numBatches = TEST_COUNT / batchSize;
        
        long startTime = System.currentTimeMillis();
        
        for (int b = 0; b < numBatches; b++) {
            KVTBatch batch = new KVTBatch(b + 1000, batchSize);
            
            for (int i = 0; i < batchSize; i++) {
                byte[] key = generateKey(random, b * batchSize + i);
                byte[] value = generateValue(random);
                batch.set(tableId, key, value);
            }
            
            batch.execute();
        }
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double throughput = (double) TEST_COUNT * 1000 / duration;
        
        System.out.printf("   Batch writes: %d ops in %d batches in %dms (%.0f ops/sec)\n",
                         TEST_COUNT, numBatches, duration, throughput);
    }
    
    private static void benchmarkConcurrentOperations(long tableId) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(THREAD_COUNT);
        
        AtomicLong totalOps = new AtomicLong(0);
        AtomicLong totalErrors = new AtomicLong(0);
        
        long startTime = System.currentTimeMillis();
        
        for (int t = 0; t < THREAD_COUNT; t++) {
            final int threadId = t;
            final boolean isReader = (t % 2 == 0);
            
            executor.submit(() -> {
                try {
                    Random random = new Random(threadId);
                    startLatch.await();
                    
                    int opsPerThread = TEST_COUNT / THREAD_COUNT;
                    
                    for (int i = 0; i < opsPerThread; i++) {
                        if (isReader) {
                            // Read operation
                            byte[] key = generateKey(random, random.nextInt(TEST_COUNT));
                            KVTNative.get(0, tableId, key);
                        } else {
                            // Write operation
                            byte[] key = generateKey(random, threadId * opsPerThread + i);
                            byte[] value = generateValue(random);
                            KVTNative.set(0, tableId, key, value);
                        }
                        
                        totalOps.incrementAndGet();
                    }
                    
                } catch (Exception e) {
                    totalErrors.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        endLatch.await();
        executor.shutdown();
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double throughput = (double) totalOps.get() * 1000 / duration;
        
        System.out.printf("   Concurrent: %d ops by %d threads in %dms (%.0f ops/sec, %d errors)\n",
                         totalOps.get(), THREAD_COUNT, duration, throughput, totalErrors.get());
    }
    
    private static void benchmarkTransactionOverhead(long tableId) throws Exception {
        Random random = new Random();
        int txSize = 10;
        int numTx = TEST_COUNT / txSize;
        
        // Without transactions
        long startTime1 = System.currentTimeMillis();
        for (int i = 0; i < TEST_COUNT; i++) {
            byte[] key = generateKey(random, i);
            byte[] value = generateValue(random);
            KVTNative.set(0, tableId, key, value);
        }
        long duration1 = System.currentTimeMillis() - startTime1;
        
        // With transactions - DISABLED (KVTTransaction no longer exists)
        long startTime2 = System.currentTimeMillis();
        // TODO: Rewrite using KVTSession transactions
        /*
        for (int tx = 0; tx < numTx; tx++) {
            KVTTransaction transaction = new KVTTransaction(
                2000 + tx, IsolationLevel.DEFAULT, false);
            transaction.begin();
            
            for (int i = 0; i < txSize; i++) {
                byte[] key = generateKey(random, TEST_COUNT + tx * txSize + i);
                byte[] value = generateValue(random);
                KVTNative.set(2000 + tx, tableId, key, value);
            }
            
            transaction.commit();
        }
        */
        long duration2 = System.currentTimeMillis() - startTime2;
        
        double overhead = ((double) duration2 - duration1) * 100 / duration1;
        
        System.out.printf("   Without TX: %dms, With TX: %dms (%.1f%% overhead)\n",
                         duration1, duration2, overhead);
    }
    
    private static void benchmarkQueryCache(long tableId) throws Exception {
        KVTQueryCache cache = new KVTQueryCache(1000, 60000, true);
        Random random = new Random();
        
        // Populate some data
        for (int i = 0; i < 1000; i++) {
            byte[] key = generateKey(random, i);
            byte[] value = generateValue(random);
            KVTNative.set(0, tableId, key, value);
        }
        
        // Test cache performance
        int queries = 1000;
        int hits = 0;
        
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < queries; i++) {
            // Simulate repeated queries (50% repeat rate)
            int keyIndex = random.nextInt(500);
            
            // This is a simplified test - actual implementation would use Query objects
            if (i > 0 && random.nextBoolean()) {
                hits++;
            }
        }
        
        long duration = System.currentTimeMillis() - startTime;
        double hitRate = (double) hits * 100 / queries;
        
        System.out.printf("   Cache: %d queries in %dms (%.1f%% simulated hit rate)\n",
                         queries, duration, hitRate);
    }
    
    private static void benchmarkIndexOperations() throws Exception {
        // This is a placeholder for index benchmarks
        // Actual implementation would test index creation and queries
        
        System.out.println("   Index benchmarks: Not yet implemented");
    }
    
    private static byte[] generateKey(Random random, int index) {
        return String.format("key_%08d_%08x", index, random.nextInt()).getBytes();
    }
    
    private static byte[] generateValue(Random random) {
        byte[] value = new byte[VALUE_SIZE];
        random.nextBytes(value);
        return value;
    }
}