/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hugegraph.backend.store.kvt;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;

/**
 * Basic connectivity test for KVT JNI bridge
 */
public class KVTBasicTest {

    static {
        // Load the JNI library
        try {
            // Try to load from java.library.path first
            System.loadLibrary("kvtjni");
        } catch (UnsatisfiedLinkError e) {
            // If not found, try to load from the target directory
            String libPath = System.getProperty("user.dir") + 
                           "/target/native/libkvtjni.so";
            System.load(libPath);
        }
    }

    @BeforeClass
    public static void setup() {
        // Initialize KVT system
        KVTNative.KVTError error = KVTNative.initialize();
        assertEquals("Failed to initialize KVT", KVTNative.KVTError.SUCCESS, error);
        System.out.println("KVT initialized successfully");
    }

    @AfterClass
    public static void teardown() {
        // Shutdown KVT system
        KVTNative.shutdown();
        System.out.println("KVT shutdown successfully");
    }

    @Test
    public void testTableOperations() {
        System.out.println("Testing table operations...");
        
        // Create a table
        KVTNative.KVTResult<Long> createResult = 
            KVTNative.createTable("test_table", "hash");
        assertEquals("Failed to create table", KVTNative.KVTError.SUCCESS, createResult.error);
        assertNotNull("Table ID should not be null", createResult.value);
        assertTrue("Table ID should be positive", createResult.value > 0);
        
        long tableId = createResult.value;
        System.out.println("Created table with ID: " + tableId);
        
        // Get table name
        KVTNative.KVTResult<String> nameResult = 
            KVTNative.nativeGetTableName(tableId)[0] instanceof Integer ? 
            null : null; // This needs proper implementation
        
        // Drop table
        KVTNative.KVTResult<Void> dropResult = KVTNative.dropTable(tableId);
        assertEquals("Failed to drop table", KVTNative.KVTError.SUCCESS, dropResult.error);
        System.out.println("Dropped table successfully");
    }

    @Test
    public void testTransactionOperations() {
        System.out.println("Testing transaction operations...");
        
        // Create a table for testing
        KVTNative.KVTResult<Long> createResult = 
            KVTNative.createTable("tx_test_table", "hash");
        assertEquals(KVTNative.KVTError.SUCCESS, createResult.error);
        long tableId = createResult.value;
        
        // Start transaction
        KVTNative.KVTResult<Long> txResult = KVTNative.startTransaction();
        assertEquals("Failed to start transaction", 
                    KVTNative.KVTError.SUCCESS, txResult.error);
        assertNotNull("Transaction ID should not be null", txResult.value);
        long txId = txResult.value;
        System.out.println("Started transaction with ID: " + txId);
        
        // Set a key-value pair
        byte[] key = "test_key".getBytes(StandardCharsets.UTF_8);
        byte[] value = "test_value".getBytes(StandardCharsets.UTF_8);
        
        KVTNative.KVTResult<Void> setResult = 
            KVTNative.set(txId, tableId, key, value);
        assertEquals("Failed to set key-value", 
                    KVTNative.KVTError.SUCCESS, setResult.error);
        
        // Get the value
        KVTNative.KVTResult<byte[]> getResult = 
            KVTNative.get(txId, tableId, key);
        assertEquals("Failed to get value", 
                    KVTNative.KVTError.SUCCESS, getResult.error);
        assertNotNull("Value should not be null", getResult.value);
        assertArrayEquals("Value mismatch", value, getResult.value);
        
        String retrievedValue = new String(getResult.value, StandardCharsets.UTF_8);
        System.out.println("Retrieved value: " + retrievedValue);
        
        // Commit transaction
        KVTNative.KVTResult<Void> commitResult = 
            KVTNative.commitTransaction(txId);
        assertEquals("Failed to commit transaction", 
                    KVTNative.KVTError.SUCCESS, commitResult.error);
        System.out.println("Transaction committed successfully");
        
        // Verify data persisted (auto-commit read)
        KVTNative.KVTResult<byte[]> verifyResult = 
            KVTNative.get(0, tableId, key);
        assertEquals("Failed to verify persisted data", 
                    KVTNative.KVTError.SUCCESS, verifyResult.error);
        assertArrayEquals("Persisted value mismatch", value, verifyResult.value);
        
        // Clean up
        KVTNative.dropTable(tableId);
    }

    @Test
    public void testDeleteOperation() {
        System.out.println("Testing delete operations...");
        
        // Create table
        KVTNative.KVTResult<Long> createResult = 
            KVTNative.createTable("delete_test_table", "hash");
        assertEquals(KVTNative.KVTError.SUCCESS, createResult.error);
        long tableId = createResult.value;
        
        // Start transaction
        KVTNative.KVTResult<Long> txResult = KVTNative.startTransaction();
        assertEquals(KVTNative.KVTError.SUCCESS, txResult.error);
        long txId = txResult.value;
        
        // Set and then delete
        byte[] key = "delete_key".getBytes(StandardCharsets.UTF_8);
        byte[] value = "to_be_deleted".getBytes(StandardCharsets.UTF_8);
        
        KVTNative.set(txId, tableId, key, value);
        
        KVTNative.KVTResult<Void> deleteResult = 
            KVTNative.del(txId, tableId, key);
        assertEquals("Failed to delete key", 
                    KVTNative.KVTError.SUCCESS, deleteResult.error);
        
        // Verify key is deleted
        KVTNative.KVTResult<byte[]> getResult = 
            KVTNative.get(txId, tableId, key);
        assertEquals("Key should be marked as deleted", 
                    KVTNative.KVTError.KEY_IS_DELETED, getResult.error);
        
        // Commit and verify
        KVTNative.commitTransaction(txId);
        
        KVTNative.KVTResult<byte[]> verifyResult = 
            KVTNative.get(0, tableId, key);
        // After commit, deleted keys may return KEY_IS_DELETED or KEY_NOT_FOUND
        assertTrue("Key should be deleted or not found after commit", 
                   verifyResult.error == KVTNative.KVTError.KEY_IS_DELETED ||
                   verifyResult.error == KVTNative.KVTError.KEY_NOT_FOUND);
        
        // Clean up
        KVTNative.dropTable(tableId);
        System.out.println("Delete operation test completed");
    }

    @Test
    public void testRollback() {
        System.out.println("Testing rollback operations...");
        
        // Create table
        KVTNative.KVTResult<Long> createResult = 
            KVTNative.createTable("rollback_test_table", "hash");
        assertEquals(KVTNative.KVTError.SUCCESS, createResult.error);
        long tableId = createResult.value;
        
        // Start transaction
        KVTNative.KVTResult<Long> txResult = KVTNative.startTransaction();
        assertEquals(KVTNative.KVTError.SUCCESS, txResult.error);
        long txId = txResult.value;
        
        // Set a value
        byte[] key = "rollback_key".getBytes(StandardCharsets.UTF_8);
        byte[] value = "should_not_persist".getBytes(StandardCharsets.UTF_8);
        
        KVTNative.set(txId, tableId, key, value);
        
        // Rollback transaction
        KVTNative.KVTResult<Void> rollbackResult = 
            KVTNative.rollbackTransaction(txId);
        assertEquals("Failed to rollback transaction", 
                    KVTNative.KVTError.SUCCESS, rollbackResult.error);
        
        // Verify data was not persisted
        KVTNative.KVTResult<byte[]> verifyResult = 
            KVTNative.get(0, tableId, key);
        assertEquals("Key should not exist after rollback", 
                    KVTNative.KVTError.KEY_NOT_FOUND, verifyResult.error);
        
        // Clean up
        KVTNative.dropTable(tableId);
        System.out.println("Rollback test completed");
    }

    public static void main(String[] args) {
        // Allow running as standalone for debugging
        System.out.println("Running KVT Basic Tests...");
        
        KVTBasicTest test = new KVTBasicTest();
        setup();
        
        try {
            test.testTableOperations();
            test.testTransactionOperations();
            test.testDeleteOperation();
            test.testRollback();
            System.out.println("\nAll tests passed!");
        } catch (Exception e) {
            System.err.println("Test failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            teardown();
        }
    }
}