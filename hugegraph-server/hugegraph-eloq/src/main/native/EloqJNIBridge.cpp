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

#include <cstring>
#include <iostream>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "eloqrocks.h"
#include "rocks_service.h"

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

// Convenience accessor — returns the RocksService from the open DB.
static EloqRocks::RocksService &Service()
{
    return g_db->Service();
}

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
 * Uses the table cache to avoid repeated OpenTableByName calls.
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
    auto handle = Service().OpenTableByName(name);
    if (!handle.IsValid())
    {
        return nullptr;
    }
    g_table_cache[name] = std::move(handle);
    return &g_table_cache[name];
}

/**
 * Convert a transaction handle (jlong) to a TransactionExecution pointer.
 * Returns nullptr for handle == 0 (auto-commit mode).
 */
static txservice::TransactionExecution *HandleToTx(jlong handle)
{
    if (handle == 0L)
    {
        return nullptr;
    }
    return reinterpret_cast<txservice::TransactionExecution *>(handle);
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
    // Open() handles: DataSubstrate::Init → EnableEngine → RocksService::Init
    //                 → DataSubstrate::Start → RocksService::Start
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
    if (Service().HasTable(name))
    {
        return JNI_TRUE;
    }

    auto handle = Service().CreateTable(name);
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
    auto handle = Service().OpenTableByName(name);
    if (!handle.IsValid())
    {
        // Table doesn't exist — treat as success
        return JNI_TRUE;
    }

    bool ok = Service().DropTable(handle);

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
    return Service().HasTable(name) ? JNI_TRUE : JNI_FALSE;
}

// ---- Transaction Management ----

JNIEXPORT jlong JNICALL
Java_org_apache_hugegraph_backend_store_eloq_EloqNative_nativeStartTx(
    JNIEnv *env, jclass cls)
{
    auto *txm = Service().StartTx();
    if (txm == nullptr)
    {
        return 0L;
    }
    return reinterpret_cast<jlong>(txm);
}

JNIEXPORT jboolean JNICALL
Java_org_apache_hugegraph_backend_store_eloq_EloqNative_nativeCommitTx(
    JNIEnv *env, jclass cls, jlong txHandle)
{
    auto *txm = HandleToTx(txHandle);
    if (txm == nullptr)
    {
        return JNI_FALSE;
    }
    return Service().CommitTx(txm) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_apache_hugegraph_backend_store_eloq_EloqNative_nativeAbortTx(
    JNIEnv *env, jclass cls, jlong txHandle)
{
    auto *txm = HandleToTx(txHandle);
    if (txm == nullptr)
    {
        return JNI_FALSE;
    }
    return Service().AbortTx(txm) ? JNI_TRUE : JNI_FALSE;
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
    auto *txm = HandleToTx(txHandle);

    return Service().Put(*th, key, value, txm) ? JNI_TRUE : JNI_FALSE;
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
    auto *txm = HandleToTx(txHandle);

    if (!Service().Get(*th, key, value, txm))
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
    auto *txm = HandleToTx(txHandle);

    return Service().Delete(*th, key, txm) ? JNI_TRUE : JNI_FALSE;
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
    auto *txm = HandleToTx(txHandle);

    bool ok = Service().Scan(*th, startKey, endKey, results, txm,
                             startInclusive == JNI_TRUE,
                             endInclusive == JNI_TRUE,
                             static_cast<size_t>(limit));
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

}  // extern "C"
