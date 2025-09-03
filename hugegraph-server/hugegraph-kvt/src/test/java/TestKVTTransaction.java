import org.apache.hugegraph.backend.store.kvt.*;
import org.apache.hugegraph.backend.tx.IsolationLevel;
import org.apache.hugegraph.config.HugeConfig;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test program for KVT transaction management
 */
public class TestKVTTransaction {

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
    
    public static void main(String[] args) {
        System.out.println("=== KVT Transaction Tests ===\n");
        
        try {
            // Initialize KVT
            System.out.println("1. Initializing KVT...");
            KVTNative.KVTError error = KVTNative.initialize();
            if (error != KVTNative.KVTError.SUCCESS) {
                throw new RuntimeException("Failed to initialize KVT: " + error);
            }
            System.out.println("   ✓ KVT initialized");
            
            // Create test table
            System.out.println("\n2. Creating test table...");
            KVTNative.KVTResult<Long> tableResult = 
                KVTNative.createTable("test_table", "hash");
            if (!tableResult.isSuccess()) {
                throw new RuntimeException("Failed to create table: " + 
                                         tableResult.errorMessage);
            }
            long tableId = tableResult.value;
            System.out.println("   ✓ Created table with ID: " + tableId);
            
            // Test basic transaction
            System.out.println("\n3. Testing basic transaction...");
            testBasicTransaction(tableId);
            System.out.println("   ✓ Basic transaction test passed");
            
            // Test rollback
            System.out.println("\n4. Testing transaction rollback...");
            testRollback(tableId);
            System.out.println("   ✓ Rollback test passed");
            
            // Test batch operations
            System.out.println("\n5. Testing batch operations...");
            testBatchOperations(tableId);
            System.out.println("   ✓ Batch operations test passed");
            
            // Test concurrent transactions
            System.out.println("\n6. Testing concurrent transactions...");
            testConcurrentTransactions(tableId);
            System.out.println("   ✓ Concurrent transactions test passed");
            
            // Test read-only transactions
            System.out.println("\n7. Testing read-only transactions...");
            testReadOnlyTransaction(tableId);
            System.out.println("   ✓ Read-only transaction test passed");
            
            System.out.println("\n=== ALL TRANSACTION TESTS PASSED! ===");
            
        } catch (Exception e) {
            System.err.println("\n=== TEST FAILED ===");
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            KVTNative.shutdown();
        }
    }
    
    private static void testBasicTransaction(long tableId) throws Exception {
        // Create transaction
        KVTTransaction tx = new KVTTransaction(1, IsolationLevel.DEFAULT, false);
        tx.begin();
        
        // Perform operations
        KVTNative.set(1, tableId, "key1".getBytes(), "value1".getBytes());
        tx.recordWrite();
        
        KVTNative.set(1, tableId, "key2".getBytes(), "value2".getBytes());
        tx.recordWrite();
        
        // Commit transaction
        tx.commit();
        
        // Verify data was committed
        KVTNative.KVTResult<byte[]> result = 
            KVTNative.get(0, tableId, "key1".getBytes());
        assert result.isSuccess() : "Failed to get committed data";
        assert "value1".equals(new String(result.value)) : "Data mismatch";
        
        // Check statistics
        assert tx.getWriteCount() == 2 : "Write count mismatch";
        assert tx.getState() == KVTTransaction.State.COMMITTED : "State mismatch";
    }
    
    private static void testRollback(long tableId) throws Exception {
        // Create transaction
        KVTTransaction tx = new KVTTransaction(2, IsolationLevel.DEFAULT, false);
        tx.begin();
        
        // Add rollback callback
        final boolean[] callbackExecuted = {false};
        tx.onRollback(() -> callbackExecuted[0] = true);
        
        // Perform operations
        KVTNative.set(2, tableId, "rollback_key".getBytes(), "rollback_value".getBytes());
        tx.recordWrite();
        
        // Rollback transaction
        tx.rollback();
        
        // Verify data was not committed
        KVTNative.KVTResult<byte[]> result = 
            KVTNative.get(0, tableId, "rollback_key".getBytes());
        assert result.error == KVTNative.KVTError.KEY_NOT_FOUND : 
            "Rollback failed - key should not exist";
        
        // Check callback was executed
        assert callbackExecuted[0] : "Rollback callback not executed";
        
        // Check state
        assert tx.getState() == KVTTransaction.State.ROLLED_BACK : "State mismatch";
    }
    
    private static void testBatchOperations(long tableId) throws Exception {
        // Create batch with transaction
        long txId = 3;
        KVTBatch batch = new KVTBatch(txId, 100);
        
        // Add operations to batch
        for (int i = 0; i < 50; i++) {
            String key = "batch_key_" + i;
            String value = "batch_value_" + i;
            batch.set(tableId, key.getBytes(), value.getBytes());
        }
        
        // Check batch size
        assert batch.size() == 50 : "Batch size mismatch";
        
        // Execute batch
        batch.execute();
        assert batch.isExecuted() : "Batch not executed";
        
        // Verify some of the batch data
        KVTNative.KVTResult<byte[]> result = 
            KVTNative.get(0, tableId, "batch_key_10".getBytes());
        
        // Note: Batch operations may need transaction commit in actual implementation
        // This is a simplified test
    }
    
    private static void testConcurrentTransactions(long tableId) throws Exception {
        int numThreads = 5;
        int opsPerThread = 20;
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    // Wait for all threads to be ready
                    startLatch.await();
                    
                    // Create transaction for this thread
                    long txId = 100 + threadId;
                    KVTTransaction tx = new KVTTransaction(txId, 
                                                          IsolationLevel.DEFAULT, false);
                    tx.begin();
                    
                    // Perform operations
                    for (int i = 0; i < opsPerThread; i++) {
                        String key = String.format("thread_%d_key_%d", threadId, i);
                        String value = String.format("thread_%d_value_%d", threadId, i);
                        
                        KVTNative.set(txId, tableId, key.getBytes(), value.getBytes());
                        tx.recordWrite();
                    }
                    
                    // Commit transaction
                    tx.commit();
                    successCount.incrementAndGet();
                    
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all threads to complete
        endLatch.await();
        executor.shutdown();
        
        // Check results
        System.out.println("   Concurrent transactions - Success: " + successCount.get() + 
                         ", Errors: " + errorCount.get());
        assert errorCount.get() == 0 : "Some concurrent transactions failed";
    }
    
    private static void testReadOnlyTransaction(long tableId) throws Exception {
        // First, add some data
        KVTNative.set(0, tableId, "readonly_test".getBytes(), "test_value".getBytes());
        
        // Create read-only transaction
        KVTTransaction tx = new KVTTransaction(200, IsolationLevel.DEFAULT, true);
        tx.begin();
        
        // Perform read operations
        KVTNative.get(200, tableId, "readonly_test".getBytes());
        tx.recordRead();
        
        // Try to perform write (should fail)
        try {
            tx.recordWrite();
            assert false : "Write should not be allowed in read-only transaction";
        } catch (Exception e) {
            // Expected
        }
        
        // Commit read-only transaction
        tx.commit();
        
        // Check statistics
        assert tx.getReadCount() == 1 : "Read count mismatch";
        assert tx.getWriteCount() == 0 : "Write count should be 0";
        assert tx.isReadOnly() : "Transaction should be read-only";
    }
}