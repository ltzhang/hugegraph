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

#include <jni.h>

#include <atomic>
#include <cstring>
#include <iostream>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "eloqrocks.h"

// ============================================================================
// Static state
// ============================================================================

// Owns the database lifecycle (Open → Close)
static std::unique_ptr<EloqRocks::EloqRocksDB> g_db;
static std::mutex g_init_mutex;

// Cache of opened table handles (name → TableHandle).
// Protected by g_table_mutex.
static std::unordered_map<std::string, EloqRocks::TableHandle> g_table_cache;
static std::mutex g_table_mutex;

// Transaction handle storage (id → TxHandle).
// TxHandle is move-only, so we store in a map and return IDs to Java.
static std::unordered_map<uint64_t, EloqRocks::TxHandle> g_tx_map;
static std::mutex g_tx_mutex;
static std::atomic<uint64_t> g_tx_id_counter{1};  // 0 means "no transaction"

// ============================================================================
// Helper functions
// ============================================================================

/**
 * Convert a Java String to a C++ std::string.
 * Returns empty string if jstr is null.
 */
static std::string JavaToString(JNIEnv *env, jstring jstr)
{
    if (jstr == nullptr)
    {
        return "";
    }
    const char *chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

/**
 * Convert a Java byte[] to a C++ std::string (binary-safe).
 * Returns empty string if jarr is null.
 */
static std::string ByteArrayToString(JNIEnv *env, jbyteArray jarr)
{
    if (jarr == nullptr)
    {
        return "";
    }
    jsize len = env->GetArrayLength(jarr);
    std::string result(len, '\0');
    env->GetByteArrayRegion(jarr, 0, len,
                            reinterpret_cast<jbyte *>(&result[0]));
    return result;
}

/**
 * Convert a C++ std::string to a Java byte[] (binary-safe).
 */
static jbyteArray StringToByteArray(JNIEnv *env, const std::string &str)
{
    jbyteArray jarr = env->NewByteArray(static_cast<jsize>(str.size()));
    env->SetByteArrayRegion(jarr, 0, static_cast<jsize>(str.size()),
                            reinterpret_cast<const jbyte *>(str.data()));
    return jarr;
}

/**
 * Look up or open a table handle by name.
 * Uses the table cache to avoid repeated OpenTable calls.
 */
static EloqRocks::TableHandle *GetTableHandle(const std::string &name)
{
    std::lock_guard<std::mutex> lock(g_table_mutex);
    auto it = g_table_cache.find(name);
    if (it != g_table_cache.end() && it->second.IsValid())
    {
        return &it->second;
    }
    // Try to open the table
    auto handle = g_db->OpenTable(name);
    if (!handle.IsValid())
    {
        return nullptr;
    }
    g_table_cache[name] = std::move(handle);
    return &g_table_cache[name];
}

// ============================================================================
// JNI exports
// ============================================================================

extern "C"
{

// ---- Lifecycle ----

JNIEXPORT jboolean JNICALL
Java_org_apache_hugegraph_backend_store_eloq_EloqNative_nativeInit(
    JNIEnv *env, jclass cls, jstring jConfigPath)
{
    std::lock_guard<std::mutex> lock(g_init_mutex);
    if (g_db && g_db->IsOpen())
    {
        return JNI_TRUE;
    }

    std::string configPath = JavaToString(env, jConfigPath);

    // Use the EloqRocksDB library API for initialization.
    EloqRocks::EloqRocksConfig cfg;
    cfg.config_file = configPath;
    cfg.log_level = 2;        // ERROR and FATAL only
    cfg.log_to_stderr = true;

    // InitLogging is safe to call even if gflags/glog were already inited
    // by the host process — but since we're inside JNI, we call it here.
    int argc = 1;
    char arg0[] = "eloqjni";
    char *argv[] = {arg0, nullptr};
    EloqRocks::InitLogging(&argc, argv, cfg);

    g_db = EloqRocks::EloqRocksDB::Open(cfg);
    if (!g_db)
    {
        std::cerr << "[EloqJNI] EloqRocksDB::Open failed" << std::endl;
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_apache_hugegraph_backend_store_eloq_EloqNative_nativeShutdown(
    JNIEnv *env, jclass cls)
{
    std::lock_guard<std::mutex> lock(g_init_mutex);
    if (!g_db || !g_db->IsOpen())
    {
        return;
    }

    // Clear transaction map (abort any outstanding transactions)
    {
        std::lock_guard<std::mutex> txlock(g_tx_mutex);
        for (auto &kv : g_tx_map)
        {
            if (kv.second.IsValid())
            {
                g_db->AbortTx(kv.second);
            }
        }
        g_tx_map.clear();
    }

    // Clear table cache
    {
        std::lock_guard<std::mutex> tlock(g_table_mutex);
        g_table_cache.clear();
    }

    g_db->Close();
    g_db.reset();
}

// ---- Table Management ----

JNIEXPORT jboolean JNICALL
Java_org_apache_hugegraph_backend_store_eloq_EloqNative_nativeCreateTable(
    JNIEnv *env, jclass cls, jstring jName)
{
    std::string name = JavaToString(env, jName);

    // If table already exists, treat as success
    if (g_db->HasTable(name))
    {
        return JNI_TRUE;
    }

    auto handle = g_db->CreateTable(name);
    if (!handle.IsValid())
    {
        return JNI_FALSE;
    }

    // Cache the handle
    {
        std::lock_guard<std::mutex> lock(g_table_mutex);
        g_table_cache[name] = std::move(handle);
    }

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_org_apache_hugegraph_backend_store_eloq_EloqNative_nativeDropTable(
    JNIEnv *env, jclass cls, jstring jName)
{
    std::string name = JavaToString(env, jName);

    // Get or open the table handle to drop it
    auto handle = g_db->OpenTable(name);
    if (!handle.IsValid())
    {
        // Table doesn't exist — treat as success
        return JNI_TRUE;
    }

    bool ok = g_db->DropTable(handle);

    // Remove from cache
    {
        std::lock_guard<std::mutex> lock(g_table_mutex);
        g_table_cache.erase(name);
    }

    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_apache_hugegraph_backend_store_eloq_EloqNative_nativeHasTable(
    JNIEnv *env, jclass cls, jstring jName)
{
    std::string name = JavaToString(env, jName);
    return g_db->HasTable(name) ? JNI_TRUE : JNI_FALSE;
}

// ---- Transaction Management ----

JNIEXPORT jlong JNICALL
Java_org_apache_hugegraph_backend_store_eloq_EloqNative_nativeStartTx(
    JNIEnv *env, jclass cls)
{
    auto tx = g_db->StartTx();
    if (!tx.IsValid())
    {
        return 0L;
    }

    uint64_t txId = g_tx_id_counter.fetch_add(1);
    {
        std::lock_guard<std::mutex> lock(g_tx_mutex);
        g_tx_map[txId] = std::move(tx);
    }
    return static_cast<jlong>(txId);
}

JNIEXPORT jboolean JNICALL
Java_org_apache_hugegraph_backend_store_eloq_EloqNative_nativeCommitTx(
    JNIEnv *env, jclass cls, jlong txHandle)
{
    uint64_t txId = static_cast<uint64_t>(txHandle);
    if (txId == 0)
    {
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(g_tx_mutex);
    auto it = g_tx_map.find(txId);
    if (it == g_tx_map.end())
    {
        std::cerr << "[EloqJNI] CommitTx: tx not found: " << txId << std::endl;
        return JNI_FALSE;
    }

    bool ok = g_db->CommitTx(it->second);
    g_tx_map.erase(it);  // Remove regardless of commit success
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_apache_hugegraph_backend_store_eloq_EloqNative_nativeAbortTx(
    JNIEnv *env, jclass cls, jlong txHandle)
{
    uint64_t txId = static_cast<uint64_t>(txHandle);
    if (txId == 0)
    {
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(g_tx_mutex);
    auto it = g_tx_map.find(txId);
    if (it == g_tx_map.end())
    {
        std::cerr << "[EloqJNI] AbortTx: tx not found: " << txId << std::endl;
        return JNI_FALSE;
    }

    bool ok = g_db->AbortTx(it->second);
    g_tx_map.erase(it);  // Remove regardless of abort success
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ---- Data Operations ----

JNIEXPORT jboolean JNICALL
Java_org_apache_hugegraph_backend_store_eloq_EloqNative_nativePut(
    JNIEnv *env, jclass cls, jlong txHandle, jstring jTable,
    jbyteArray jKey, jbyteArray jValue)
{
    std::string tableName = JavaToString(env, jTable);
    auto *th = GetTableHandle(tableName);
    if (th == nullptr)
    {
        std::cerr << "[EloqJNI] Put: table not found: " << tableName
                  << std::endl;
        return JNI_FALSE;
    }

    std::string key = ByteArrayToString(env, jKey);
    std::string value = ByteArrayToString(env, jValue);

    uint64_t txId = static_cast<uint64_t>(txHandle);
    if (txId == 0)
    {
        // Auto-commit mode
        return g_db->Put(*th, key, value) ? JNI_TRUE : JNI_FALSE;
    }

    // Transactional mode
    std::lock_guard<std::mutex> lock(g_tx_mutex);
    auto it = g_tx_map.find(txId);
    if (it == g_tx_map.end())
    {
        std::cerr << "[EloqJNI] Put: tx not found: " << txId << std::endl;
        return JNI_FALSE;
    }
    return g_db->Put(*th, key, value, it->second) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_org_apache_hugegraph_backend_store_eloq_EloqNative_nativeGet(
    JNIEnv *env, jclass cls, jlong txHandle, jstring jTable,
    jbyteArray jKey)
{
    std::string tableName = JavaToString(env, jTable);
    auto *th = GetTableHandle(tableName);
    if (th == nullptr)
    {
        return nullptr;
    }

    std::string key = ByteArrayToString(env, jKey);
    std::string value;

    uint64_t txId = static_cast<uint64_t>(txHandle);
    bool found = false;

    if (txId == 0)
    {
        // Auto-commit mode
        found = g_db->Get(*th, key, value);
    }
    else
    {
        // Transactional mode
        std::lock_guard<std::mutex> lock(g_tx_mutex);
        auto it = g_tx_map.find(txId);
        if (it == g_tx_map.end())
        {
            std::cerr << "[EloqJNI] Get: tx not found: " << txId << std::endl;
            return nullptr;
        }
        found = g_db->Get(*th, key, value, it->second);
    }

    if (!found)
    {
        return nullptr;  // Key not found
    }

    return StringToByteArray(env, value);
}

JNIEXPORT jboolean JNICALL
Java_org_apache_hugegraph_backend_store_eloq_EloqNative_nativeDelete(
    JNIEnv *env, jclass cls, jlong txHandle, jstring jTable,
    jbyteArray jKey)
{
    std::string tableName = JavaToString(env, jTable);
    auto *th = GetTableHandle(tableName);
    if (th == nullptr)
    {
        std::cerr << "[EloqJNI] Delete: table not found: " << tableName
                  << std::endl;
        return JNI_FALSE;
    }

    std::string key = ByteArrayToString(env, jKey);

    uint64_t txId = static_cast<uint64_t>(txHandle);
    if (txId == 0)
    {
        // Auto-commit mode
        return g_db->Delete(*th, key) ? JNI_TRUE : JNI_FALSE;
    }

    // Transactional mode
    std::lock_guard<std::mutex> lock(g_tx_mutex);
    auto it = g_tx_map.find(txId);
    if (it == g_tx_map.end())
    {
        std::cerr << "[EloqJNI] Delete: tx not found: " << txId << std::endl;
        return JNI_FALSE;
    }
    return g_db->Delete(*th, key, it->second) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_org_apache_hugegraph_backend_store_eloq_EloqNative_nativeScan(
    JNIEnv *env, jclass cls, jlong txHandle, jstring jTable,
    jbyteArray jStartKey, jbyteArray jEndKey,
    jboolean startInclusive, jboolean endInclusive, jint limit)
{
    std::string tableName = JavaToString(env, jTable);
    auto *th = GetTableHandle(tableName);
    if (th == nullptr)
    {
        return nullptr;
    }

    // Convert keys: null → empty string (means infinity in EloqRocks)
    std::string startKey = (jStartKey != nullptr)
                               ? ByteArrayToString(env, jStartKey)
                               : "";
    std::string endKey = (jEndKey != nullptr)
                             ? ByteArrayToString(env, jEndKey)
                             : "";

    std::vector<std::pair<std::string, std::string>> results;
    uint64_t txId = static_cast<uint64_t>(txHandle);
    bool ok = false;

    if (txId == 0)
    {
        // Auto-commit mode
        ok = g_db->Scan(*th, startKey, endKey, results,
                        startInclusive == JNI_TRUE,
                        endInclusive == JNI_TRUE,
                        static_cast<size_t>(limit));
    }
    else
    {
        // Transactional mode
        std::lock_guard<std::mutex> lock(g_tx_mutex);
        auto it = g_tx_map.find(txId);
        if (it == g_tx_map.end())
        {
            std::cerr << "[EloqJNI] Scan: tx not found: " << txId << std::endl;
            return nullptr;
        }
        ok = g_db->Scan(*th, startKey, endKey, results, it->second,
                        startInclusive == JNI_TRUE,
                        endInclusive == JNI_TRUE,
                        static_cast<size_t>(limit));
    }

    if (!ok)
    {
        return nullptr;
    }

    // Build result: byte[2][][] where [0]=keys, [1]=values
    jclass byteArrayClass = env->FindClass("[B");
    jsize count = static_cast<jsize>(results.size());

    // Outer array: 2 elements
    jclass byteArrayArrayClass = env->FindClass("[[B");
    jobjectArray outer = env->NewObjectArray(2, byteArrayArrayClass, nullptr);

    // Keys array
    jobjectArray keysArray = env->NewObjectArray(count, byteArrayClass, nullptr);
    // Values array
    jobjectArray valsArray = env->NewObjectArray(count, byteArrayClass, nullptr);

    for (jsize i = 0; i < count; i++)
    {
        env->SetObjectArrayElement(keysArray, i,
                                   StringToByteArray(env, results[i].first));
        env->SetObjectArrayElement(valsArray, i,
                                   StringToByteArray(env, results[i].second));
    }

    env->SetObjectArrayElement(outer, 0, keysArray);
    env->SetObjectArrayElement(outer, 1, valsArray);

    return outer;
}

// ---- Batch Operations ----

/**
 * Batch write: execute a list of Put/Delete operations atomically.
 *
 * Java signature:
 *   nativeBatchWrite(byte[] opTypes, String[] tables,
 *                    byte[][] keys, byte[][] values) -> boolean
 *
 * opTypes[i]: 0 = Put, 1 = Delete
 * tables[i]:  table name for operation i
 * keys[i]:    key bytes for operation i
 * values[i]:  value bytes for operation i (ignored for Delete)
 */
JNIEXPORT jboolean JNICALL
Java_org_apache_hugegraph_backend_store_eloq_EloqNative_nativeBatchWrite(
    JNIEnv *env, jclass cls,
    jbyteArray jOpTypes, jobjectArray jTables,
    jobjectArray jKeys, jobjectArray jValues)
{
    jsize count = env->GetArrayLength(jOpTypes);
    if (count == 0)
    {
        return JNI_TRUE;
    }

    // Get opTypes as a raw byte array
    jbyte *opTypes = env->GetByteArrayElements(jOpTypes, nullptr);

    // Build the BatchWriteOp vector
    std::vector<EloqRocks::BatchWriteOp> ops;
    ops.reserve(count);

    for (jsize i = 0; i < count; i++)
    {
        EloqRocks::BatchWriteOp bop;
        bop.op = (opTypes[i] == 0) ? EloqRocks::BatchOpType::Put
                                   : EloqRocks::BatchOpType::Delete;

        // Table name
        jstring jTable = (jstring)env->GetObjectArrayElement(jTables, i);
        std::string tableName = JavaToString(env, jTable);
        env->DeleteLocalRef(jTable);

        auto *th = GetTableHandle(tableName);
        if (th == nullptr)
        {
            std::cerr << "[EloqJNI] BatchWrite: table not found: "
                      << tableName << std::endl;
            env->ReleaseByteArrayElements(jOpTypes, opTypes, JNI_ABORT);
            return JNI_FALSE;
        }
        bop.table = th;

        // Key
        jbyteArray jKey = (jbyteArray)env->GetObjectArrayElement(jKeys, i);
        bop.key = ByteArrayToString(env, jKey);
        env->DeleteLocalRef(jKey);

        // Value (only needed for Put)
        if (bop.op == EloqRocks::BatchOpType::Put)
        {
            jbyteArray jVal = (jbyteArray)env->GetObjectArrayElement(jValues, i);
            bop.value = ByteArrayToString(env, jVal);
            env->DeleteLocalRef(jVal);
        }

        ops.push_back(std::move(bop));
    }

    env->ReleaseByteArrayElements(jOpTypes, opTypes, JNI_ABORT);

    // Execute the batch (handles its own transaction internally)
    return g_db->BatchWrite(ops) ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
