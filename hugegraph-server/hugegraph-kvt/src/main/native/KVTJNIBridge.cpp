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
#include <string>
#include <vector>
#include <cstring>
#include <stdexcept>
#include <iostream>
#include "org_apache_hugegraph_backend_store_kvt_KVTNative.h"
#include "../../../kvt/kvt_inc.h"


// Helper function to convert Java string to C++ string
std::string JavaToString(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) {
        return "";
    }
    const char* cstr = env->GetStringUTFChars(jstr, nullptr);
    std::string result(cstr);
    env->ReleaseStringUTFChars(jstr, cstr);
    return result;
}

// Helper function to convert C++ string to Java string
jstring StringToJava(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

// Helper function to convert Java byte array to C++ string
std::string ByteArrayToString(JNIEnv* env, jbyteArray arr) {
    if (arr == nullptr) {
        return "";
    }
    jsize len = env->GetArrayLength(arr);
    if (len == 0) {
        return "";
    }
    jbyte* bytes = env->GetByteArrayElements(arr, nullptr);
    std::string result(reinterpret_cast<const char*>(bytes), len);
    env->ReleaseByteArrayElements(arr, bytes, JNI_ABORT);
    return result;
}

// Helper function to convert C++ string to Java byte array
jbyteArray StringToByteArray(JNIEnv* env, const std::string& str) {
    if (str.empty()) {
        return nullptr;
    }
    jsize len = static_cast<jsize>(str.size());
    jbyteArray result = env->NewByteArray(len);
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, len, 
            reinterpret_cast<const jbyte*>(str.data()));
    }
    return result;
}

// Helper function to create result array with error code and message
jobjectArray CreateErrorResult(JNIEnv* env, KVTError error, const std::string& errorMsg) {
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(2, objectClass, nullptr);
    
    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID intValueOf = env->GetStaticMethodID(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
    jobject errorCode = env->CallStaticObjectMethod(integerClass, intValueOf, static_cast<jint>(error));
    
    env->SetObjectArrayElement(result, 0, errorCode);
    env->SetObjectArrayElement(result, 1, StringToJava(env, errorMsg));
    
    return result;
}

/*
 * Class:     org_apache_hugegraph_backend_store_kvt_KVTNative
 * Method:    nativeInitialize
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_org_apache_hugegraph_backend_store_kvt_KVTNative_nativeInitialize
  (JNIEnv *env, jclass cls) {
    KVTError error = kvt_initialize();
    return static_cast<jint>(error);
}

/*
 * Class:     org_apache_hugegraph_backend_store_kvt_KVTNative
 * Method:    nativeShutdown
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_org_apache_hugegraph_backend_store_kvt_KVTNative_nativeShutdown
  (JNIEnv *env, jclass cls) {
    kvt_shutdown();
}

/*
 * Class:     org_apache_hugegraph_backend_store_kvt_KVTNative
 * Method:    nativeCreateTable
 * Signature: (Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/Object;
 */
JNIEXPORT jobjectArray JNICALL Java_org_apache_hugegraph_backend_store_kvt_KVTNative_nativeCreateTable
  (JNIEnv *env, jclass cls, jstring tableName, jstring partitionMethod) {
    std::string tableNameStr = JavaToString(env, tableName);
    std::string partitionMethodStr = JavaToString(env, partitionMethod);
    
    uint64_t tableId = 0;
    std::string errorMsg;
    KVTError error = kvt_create_table(tableNameStr, partitionMethodStr, tableId, errorMsg);
    
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(3, objectClass, nullptr);
    
    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID intValueOf = env->GetStaticMethodID(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
    jobject errorCode = env->CallStaticObjectMethod(integerClass, intValueOf, static_cast<jint>(error));
    
    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longValueOf = env->GetStaticMethodID(longClass, "valueOf", "(J)Ljava/lang/Long;");
    jobject tableIdObj = env->CallStaticObjectMethod(longClass, longValueOf, static_cast<jlong>(tableId));
    
    env->SetObjectArrayElement(result, 0, errorCode);
    env->SetObjectArrayElement(result, 1, tableIdObj);
    env->SetObjectArrayElement(result, 2, StringToJava(env, errorMsg));
    
    return result;
}

/*
 * Class:     org_apache_hugegraph_backend_store_kvt_KVTNative
 * Method:    nativeDropTable
 * Signature: (J)[Ljava/lang/Object;
 */
JNIEXPORT jobjectArray JNICALL Java_org_apache_hugegraph_backend_store_kvt_KVTNative_nativeDropTable
  (JNIEnv *env, jclass cls, jlong tableId) {
    std::string errorMsg;
    KVTError error = kvt_drop_table(static_cast<uint64_t>(tableId), errorMsg);
    return CreateErrorResult(env, error, errorMsg);
}

/*
 * Class:     org_apache_hugegraph_backend_store_kvt_KVTNative
 * Method:    nativeGetTableName
 * Signature: (J)[Ljava/lang/Object;
 */
JNIEXPORT jobjectArray JNICALL Java_org_apache_hugegraph_backend_store_kvt_KVTNative_nativeGetTableName
  (JNIEnv *env, jclass cls, jlong tableId) {
    std::string tableName;
    std::string errorMsg;
    KVTError error = kvt_get_table_name(static_cast<uint64_t>(tableId), tableName, errorMsg);
    
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(3, objectClass, nullptr);
    
    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID intValueOf = env->GetStaticMethodID(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
    jobject errorCode = env->CallStaticObjectMethod(integerClass, intValueOf, static_cast<jint>(error));
    
    env->SetObjectArrayElement(result, 0, errorCode);
    env->SetObjectArrayElement(result, 1, StringToJava(env, tableName));
    env->SetObjectArrayElement(result, 2, StringToJava(env, errorMsg));
    
    return result;
}

/*
 * Class:     org_apache_hugegraph_backend_store_kvt_KVTNative
 * Method:    nativeGetTableId
 * Signature: (Ljava/lang/String;)[Ljava/lang/Object;
 */
JNIEXPORT jobjectArray JNICALL Java_org_apache_hugegraph_backend_store_kvt_KVTNative_nativeGetTableId
  (JNIEnv *env, jclass cls, jstring tableName) {
    std::string tableNameStr = JavaToString(env, tableName);
    uint64_t tableId = 0;
    std::string errorMsg;
    KVTError error = kvt_get_table_id(tableNameStr, tableId, errorMsg);
    
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(3, objectClass, nullptr);
    
    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID intValueOf = env->GetStaticMethodID(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
    jobject errorCode = env->CallStaticObjectMethod(integerClass, intValueOf, static_cast<jint>(error));
    
    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longValueOf = env->GetStaticMethodID(longClass, "valueOf", "(J)Ljava/lang/Long;");
    jobject tableIdObj = env->CallStaticObjectMethod(longClass, longValueOf, static_cast<jlong>(tableId));
    
    env->SetObjectArrayElement(result, 0, errorCode);
    env->SetObjectArrayElement(result, 1, tableIdObj);
    env->SetObjectArrayElement(result, 2, StringToJava(env, errorMsg));
    
    return result;
}

/*
 * Class:     org_apache_hugegraph_backend_store_kvt_KVTNative
 * Method:    nativeStartTransaction
 * Signature: ()[Ljava/lang/Object;
 */
JNIEXPORT jobjectArray JNICALL Java_org_apache_hugegraph_backend_store_kvt_KVTNative_nativeStartTransaction
  (JNIEnv *env, jclass cls) {
    uint64_t txId = 0;
    std::string errorMsg;
    KVTError error = kvt_start_transaction(txId, errorMsg);
    
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(3, objectClass, nullptr);
    
    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID intValueOf = env->GetStaticMethodID(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
    jobject errorCode = env->CallStaticObjectMethod(integerClass, intValueOf, static_cast<jint>(error));
    
    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longValueOf = env->GetStaticMethodID(longClass, "valueOf", "(J)Ljava/lang/Long;");
    jobject txIdObj = env->CallStaticObjectMethod(longClass, longValueOf, static_cast<jlong>(txId));
    
    env->SetObjectArrayElement(result, 0, errorCode);
    env->SetObjectArrayElement(result, 1, txIdObj);
    env->SetObjectArrayElement(result, 2, StringToJava(env, errorMsg));
    
    return result;
}

/*
 * Class:     org_apache_hugegraph_backend_store_kvt_KVTNative
 * Method:    nativeGet
 * Signature: (JJ[B)[Ljava/lang/Object;
 */
JNIEXPORT jobjectArray JNICALL Java_org_apache_hugegraph_backend_store_kvt_KVTNative_nativeGet
  (JNIEnv *env, jclass cls, jlong txId, jlong tableId, jbyteArray key) {
    std::string keyStr = ByteArrayToString(env, key);
    KVTKey kvtKey(keyStr);
    std::string value;
    std::string errorMsg;
    
    KVTError error = kvt_get(static_cast<uint64_t>(txId), 
                            static_cast<uint64_t>(tableId),
                            kvtKey, value, errorMsg);
    
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(3, objectClass, nullptr);
    
    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID intValueOf = env->GetStaticMethodID(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
    jobject errorCode = env->CallStaticObjectMethod(integerClass, intValueOf, static_cast<jint>(error));
    
    env->SetObjectArrayElement(result, 0, errorCode);
    env->SetObjectArrayElement(result, 1, StringToByteArray(env, value));
    env->SetObjectArrayElement(result, 2, StringToJava(env, errorMsg));
    
    return result;
}

/*
 * Class:     org_apache_hugegraph_backend_store_kvt_KVTNative
 * Method:    nativeSet
 * Signature: (JJ[B[B)[Ljava/lang/Object;
 */
JNIEXPORT jobjectArray JNICALL Java_org_apache_hugegraph_backend_store_kvt_KVTNative_nativeSet
  (JNIEnv *env, jclass cls, jlong txId, jlong tableId, jbyteArray key, jbyteArray value) {
    std::string keyStr = ByteArrayToString(env, key);
    KVTKey kvtKey(keyStr);
    std::string valueStr = ByteArrayToString(env, value);
    std::string errorMsg;
    
    KVTError error = kvt_set(static_cast<uint64_t>(txId),
                            static_cast<uint64_t>(tableId),
                            kvtKey, valueStr, errorMsg);
    
    return CreateErrorResult(env, error, errorMsg);
}

/*
 * Class:     org_apache_hugegraph_backend_store_kvt_KVTNative
 * Method:    nativeDel
 * Signature: (JJ[B)[Ljava/lang/Object;
 */
JNIEXPORT jobjectArray JNICALL Java_org_apache_hugegraph_backend_store_kvt_KVTNative_nativeDel
  (JNIEnv *env, jclass cls, jlong txId, jlong tableId, jbyteArray key) {
    std::string keyStr = ByteArrayToString(env, key);
    KVTKey kvtKey(keyStr);
    std::string errorMsg;
    
    KVTError error = kvt_del(static_cast<uint64_t>(txId),
                            static_cast<uint64_t>(tableId),
                            kvtKey, errorMsg);
    
    return CreateErrorResult(env, error, errorMsg);
}

/*
 * Class:     org_apache_hugegraph_backend_store_kvt_KVTNative
 * Method:    nativeScan
 * Signature: (JJ[B[BI)[Ljava/lang/Object;
 */
JNIEXPORT jobjectArray JNICALL Java_org_apache_hugegraph_backend_store_kvt_KVTNative_nativeScan
  (JNIEnv *env, jclass cls, jlong txId, jlong tableId, 
   jbyteArray keyStart, jbyteArray keyEnd, jint limit) {
    // Handle null arrays for full table scan
    KVTKey keyStartKey;
    KVTKey keyEndKey;
    
    if (keyStart == nullptr) {
        // For start of full table scan, use a very small key value
        // Empty string doesn't work with lower_bound in the KVT implementation
        // Use a single null byte which should be smaller than any normal key
        keyStartKey = KVTKey(std::string(1, '\0'));
    } else if (env->GetArrayLength(keyStart) == 0) {
        // Empty byte array also means scan from beginning
        keyStartKey = KVTKey(std::string(1, '\0'));
    } else {
        std::string keyStartStr = ByteArrayToString(env, keyStart);
        keyStartKey = KVTKey(keyStartStr);
    }
    
    if (keyEnd == nullptr) {
        // For end of full table scan, use high value string
        // Use string of 0xFF bytes which should be larger than any normal key
        keyEndKey = KVTKey(std::string(100, '\xFF'));
    } else {
        std::string keyEndStr = ByteArrayToString(env, keyEnd);
        keyEndKey = KVTKey(keyEndStr);
    }
    
    std::vector<std::pair<KVTKey, std::string>> results;
    std::string errorMsg;
    
    KVTError error = kvt_scan(static_cast<uint64_t>(txId),
                             static_cast<uint64_t>(tableId),
                             keyStartKey, keyEndKey,
                             static_cast<size_t>(limit),
                             results, errorMsg);
    
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(4, objectClass, nullptr);
    
    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID intValueOf = env->GetStaticMethodID(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
    jobject errorCode = env->CallStaticObjectMethod(integerClass, intValueOf, static_cast<jint>(error));
    
    // Create key and value arrays
    jclass byteArrayClass = env->FindClass("[B");
    jobjectArray keys = env->NewObjectArray(static_cast<jsize>(results.size()), byteArrayClass, nullptr);
    jobjectArray values = env->NewObjectArray(static_cast<jsize>(results.size()), byteArrayClass, nullptr);
    
    for (size_t i = 0; i < results.size(); ++i) {
        jbyteArray keyArray = StringToByteArray(env, results[i].first);
        jbyteArray valueArray = StringToByteArray(env, results[i].second);
        
        env->SetObjectArrayElement(keys, static_cast<jsize>(i), keyArray);
        env->SetObjectArrayElement(values, static_cast<jsize>(i), valueArray);
        
        // Delete local references to prevent memory leak in large result sets
        env->DeleteLocalRef(keyArray);
        env->DeleteLocalRef(valueArray);
    }
    
    env->SetObjectArrayElement(result, 0, errorCode);
    env->SetObjectArrayElement(result, 1, keys);
    env->SetObjectArrayElement(result, 2, values);
    env->SetObjectArrayElement(result, 3, StringToJava(env, errorMsg));
    
    return result;
}

/*
 * Class:     org_apache_hugegraph_backend_store_kvt_KVTNative
 * Method:    nativeCommitTransaction
 * Signature: (J)[Ljava/lang/Object;
 */
JNIEXPORT jobjectArray JNICALL Java_org_apache_hugegraph_backend_store_kvt_KVTNative_nativeCommitTransaction
  (JNIEnv *env, jclass cls, jlong txId) {
    std::string errorMsg;
    KVTError error = kvt_commit_transaction(static_cast<uint64_t>(txId), errorMsg);
    return CreateErrorResult(env, error, errorMsg);
}

/*
 * Class:     org_apache_hugegraph_backend_store_kvt_KVTNative
 * Method:    nativeRollbackTransaction
 * Signature: (J)[Ljava/lang/Object;
 */
JNIEXPORT jobjectArray JNICALL Java_org_apache_hugegraph_backend_store_kvt_KVTNative_nativeRollbackTransaction
  (JNIEnv *env, jclass cls, jlong txId) {
    std::string errorMsg;
    KVTError error = kvt_rollback_transaction(static_cast<uint64_t>(txId), errorMsg);
    return CreateErrorResult(env, error, errorMsg);
}

/*
 * Class:     org_apache_hugegraph_backend_store_kvt_KVTNative
 * Method:    nativeListTables
 * Signature: ()[Ljava/lang/Object;
 */
JNIEXPORT jobjectArray JNICALL Java_org_apache_hugegraph_backend_store_kvt_KVTNative_nativeListTables
  (JNIEnv *env, jclass cls) {
    std::vector<std::pair<std::string, uint64_t>> results;
    std::string errorMsg;
    KVTError error = kvt_list_tables(results, errorMsg);
    
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(4, objectClass, nullptr);
    
    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID intValueOf = env->GetStaticMethodID(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
    jobject errorCode = env->CallStaticObjectMethod(integerClass, intValueOf, static_cast<jint>(error));
    
    // Create arrays for table names and IDs
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray tableNames = env->NewObjectArray(static_cast<jsize>(results.size()), stringClass, nullptr);
    
    jclass longClass = env->FindClass("java/lang/Long");
    jobjectArray tableIds = env->NewObjectArray(static_cast<jsize>(results.size()), longClass, nullptr);
    jmethodID longValueOf = env->GetStaticMethodID(longClass, "valueOf", "(J)Ljava/lang/Long;");
    
    for (size_t i = 0; i < results.size(); ++i) {
        env->SetObjectArrayElement(tableNames, static_cast<jsize>(i), 
                                  StringToJava(env, results[i].first));
        jobject tableId = env->CallStaticObjectMethod(longClass, longValueOf, 
                                                      static_cast<jlong>(results[i].second));
        env->SetObjectArrayElement(tableIds, static_cast<jsize>(i), tableId);
    }
    
    env->SetObjectArrayElement(result, 0, errorCode);
    env->SetObjectArrayElement(result, 1, tableNames);
    env->SetObjectArrayElement(result, 2, tableIds);
    env->SetObjectArrayElement(result, 3, StringToJava(env, errorMsg));
    
    return result;
}

/*
 * Class:     org_apache_hugegraph_backend_store_kvt_KVTNative
 * Method:    nativeBatchExecute
 * Signature: (J[I[J[[B[[B)[Ljava/lang/Object;
 */
JNIEXPORT jobjectArray JNICALL Java_org_apache_hugegraph_backend_store_kvt_KVTNative_nativeBatchExecute
  (JNIEnv *env, jclass cls, jlong txId, jintArray opTypes, 
   jlongArray tableIds, jobjectArray keys, jobjectArray values) {
    
    // Get array lengths
    jsize numOps = env->GetArrayLength(opTypes);
    
    // Get the arrays
    jint* opTypesArray = env->GetIntArrayElements(opTypes, nullptr);
    jlong* tableIdsArray = env->GetLongArrayElements(tableIds, nullptr);
    
    // Build the batch operations
    KVTBatchOps batchOps;
    batchOps.reserve(numOps);
    
    for (jsize i = 0; i < numOps; ++i) {
        KVTOp op;
        op.op = static_cast<KVT_OPType>(opTypesArray[i]);
        op.table_id = static_cast<uint64_t>(tableIdsArray[i]);
        
        // Get key
        jbyteArray keyArray = (jbyteArray)env->GetObjectArrayElement(keys, i);
        if (keyArray != nullptr) {
            op.key = ByteArrayToString(env, keyArray);
            env->DeleteLocalRef(keyArray);
        }
        
        // Get value (may be null for GET and DEL operations)
        if (values != nullptr) {
            jbyteArray valueArray = (jbyteArray)env->GetObjectArrayElement(values, i);
            if (valueArray != nullptr) {
                op.value = ByteArrayToString(env, valueArray);
                env->DeleteLocalRef(valueArray);
            }
        }
        
        batchOps.push_back(op);
    }
    
    // Release the arrays
    env->ReleaseIntArrayElements(opTypes, opTypesArray, JNI_ABORT);
    env->ReleaseLongArrayElements(tableIds, tableIdsArray, JNI_ABORT);
    
    // Execute the batch
    KVTBatchResults batchResults;
    std::string errorMsg;
    KVTError error = kvt_batch_execute(static_cast<uint64_t>(txId), 
                                       batchOps, batchResults, errorMsg);
    
    // Prepare the result
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(4, objectClass, nullptr);
    
    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID intValueOf = env->GetStaticMethodID(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
    jobject errorCode = env->CallStaticObjectMethod(integerClass, intValueOf, static_cast<jint>(error));
    
    // Create arrays for result codes and values
    jintArray resultCodes = env->NewIntArray(static_cast<jsize>(batchResults.size()));
    jclass byteArrayClass = env->FindClass("[B");
    jobjectArray resultValues = env->NewObjectArray(static_cast<jsize>(batchResults.size()), 
                                                    byteArrayClass, nullptr);
    
    // Fill the result arrays
    jint* resultCodesArray = env->GetIntArrayElements(resultCodes, nullptr);
    for (size_t i = 0; i < batchResults.size(); ++i) {
        resultCodesArray[i] = static_cast<jint>(batchResults[i].error);
        
        // For GET operations, include the value if successful
        if (batchOps[i].op == OP_GET && batchResults[i].error == KVTError::SUCCESS) {
            jbyteArray valueArray = StringToByteArray(env, batchResults[i].value);
            env->SetObjectArrayElement(resultValues, static_cast<jsize>(i), valueArray);
            env->DeleteLocalRef(valueArray);
        }
    }
    env->ReleaseIntArrayElements(resultCodes, resultCodesArray, 0);
    
    env->SetObjectArrayElement(result, 0, errorCode);
    env->SetObjectArrayElement(result, 1, resultCodes);
    env->SetObjectArrayElement(result, 2, resultValues);
    env->SetObjectArrayElement(result, 3, StringToJava(env, errorMsg));
    
    return result;
}

/**
 * Helper function to decode variable-length integer (vInt)
 * Matches HugeGraph's BytesBuffer.readVInt() format
 */
size_t decodeVInt(const unsigned char* data, size_t& bytes_read) {
    unsigned char leading = data[0];
    size_t value = leading & 0x7F;
    bytes_read = 1;
    
    if ((leading & 0x80) == 0) {
        // Single byte value
        return value;
    }
    
    // Multi-byte value - keep reading continuation bytes
    for (int i = 1; i < 5; i++) {
        unsigned char b = data[bytes_read];
        bytes_read++;
        
        if ((b & 0x80) == 0) {
            // Final byte (no continuation bit)
            value = (value << 7) | b;
            return value;
        } else {
            // Continuation byte
            value = (value << 7) | (b & 0x7F);
        }
    }
    
    throw std::runtime_error("Invalid vInt encoding - too many bytes");
}

/**
 * Helper function to encode variable-length integer (vInt)
 * Matches HugeGraph's BytesBuffer.writeVInt() format
 */
void encodeVInt(size_t value, std::string& output) {
    // HugeGraph writes high-order bytes first with continuation bit
    // Format: most significant bytes first, each with 0x80 bit set except the last
    
    if (value > 0x0fffffff) {
        output.push_back(0x80 | ((value >> 28) & 0x7f));
    }
    if (value > 0x1fffff) {
        output.push_back(0x80 | ((value >> 21) & 0x7f));
    }
    if (value > 0x3fff) {
        output.push_back(0x80 | ((value >> 14) & 0x7f));
    }
    if (value > 0x7f) {
        output.push_back(0x80 | ((value >> 7) & 0x7f));
    }
    output.push_back(value & 0x7f);
}

/*
 * Helper function to update vertex properties using the new KVT process interface.
 * This function deserializes the existing vertex data, merges in the new property,
 * and serializes it back.
 * 
 * Format expected:
 * - input.value: [id_bytes][column_data...]
 * - input.parameter: [property_key_len][property_key][property_value_len][property_value]
 * 
 * Column format: [name_len_vint][name_bytes][value_len_vint][value_bytes]
 */
bool hg_update_vertex_property(
    KVTProcessInput& input,
    KVTProcessOutput& output) {
    
    try {
        // Check required inputs
        if (!input.value || !input.parameter) {
            output.return_value = "Missing required input value or parameter";
            return false;
        }
        
        const std::string& original_value = *input.value;
        const std::string& parameter = *input.parameter;
        
        // If original value is empty, this is a new vertex (shouldn't happen for property update)
        if (original_value.empty()) {
            output.return_value = "Cannot update property on non-existent vertex";
            return false;
        }
        
        // Parse the parameter to get the property update
        // Format: [property_name_len_vint][property_name][property_value_len_vint][property_value]
        size_t param_pos = 0;
        if (parameter.size() < 2) {
            output.return_value = "Invalid property update parameter";
            return false;
        }
        
        // Read property name length (variable int encoding)
        const unsigned char* param_data = reinterpret_cast<const unsigned char*>(parameter.data());
        size_t bytes_read = 0;
        size_t prop_name_len = decodeVInt(param_data + param_pos, bytes_read);
        param_pos += bytes_read;
        
        if (param_pos + prop_name_len > parameter.size()) {
            output.return_value = "Invalid property name length";
            return false;
        }
        
        std::string prop_name(parameter.data() + param_pos, prop_name_len);
        param_pos += prop_name_len;
        
        // Read property value length
        if (param_pos >= parameter.size()) {
            output.return_value = "Missing property value";
            return false;
        }
        
        size_t prop_value_len = decodeVInt(param_data + param_pos, bytes_read);
        param_pos += bytes_read;
        
        if (param_pos + prop_value_len > parameter.size()) {
            output.return_value = "Invalid property value length";
            return false;
        }
        
        std::string prop_value(parameter.data() + param_pos, prop_value_len);
        
        // Parse the original value and rebuild it with the updated property
        const unsigned char* orig_data = reinterpret_cast<const unsigned char*>(original_value.data());
        size_t orig_pos = 0;
        
        // Parse all columns and update the target property
        std::vector<std::pair<std::string, std::string>> columns;
        bool property_found = false;
        
        // Find where columns start (after the ID bytes)
        // Simple heuristic: look for first valid column structure
        size_t id_end_pos = 0;
        for (size_t scan = 0; scan < original_value.size() - 2; scan++) {
            size_t test_len = orig_data[scan];
            if (test_len > 0 && test_len < 100 && scan + 1 + test_len < original_value.size()) {
                size_t next = scan + 1 + test_len;
                if (next < original_value.size()) {
                    // Looks like a valid column
                    id_end_pos = scan;
                    break;
                }
            }
        }
        
        // Build the new value
        std::string new_value;
        // Copy ID bytes
        new_value.append(original_value.data(), id_end_pos);
        
        // Parse existing columns
        orig_pos = id_end_pos;
        while (orig_pos < original_value.size()) {
            // Read column name length
            size_t col_name_len = 0;
            try {
                col_name_len = decodeVInt(orig_data + orig_pos, bytes_read);
                orig_pos += bytes_read;
            } catch (...) {
                break; // End of columns
            }
            
            if (orig_pos + col_name_len > original_value.size()) break;
            
            std::string col_name(original_value.data() + orig_pos, col_name_len);
            orig_pos += col_name_len;
            
            if (orig_pos >= original_value.size()) break;
            
            // Read column value length
            size_t col_value_len = decodeVInt(orig_data + orig_pos, bytes_read);
            orig_pos += bytes_read;
            
            if (orig_pos + col_value_len > original_value.size()) break;
            
            std::string col_value(original_value.data() + orig_pos, col_value_len);
            orig_pos += col_value_len;
            
            // Update property if it matches
            if (col_name == prop_name) {
                columns.push_back({col_name, prop_value});
                property_found = true;
            } else {
                columns.push_back({col_name, col_value});
            }
        }
        
        // Add property if not found
        if (!property_found) {
            columns.push_back({prop_name, prop_value});
        }
        
        // Write all columns back
        for (const auto& col : columns) {
            encodeVInt(col.first.size(), new_value);
            new_value.append(col.first);
            encodeVInt(col.second.size(), new_value);
            new_value.append(col.second);
        }
        
        // Set outputs
        output.update_value = new_value;
        output.return_value = "Vertex property updated successfully";
        output.delete_key = false;
        
        return true;
        
    } catch (const std::exception& e) {
        output.return_value = std::string("Error updating property: ") + e.what();
        return false;
    }
}

/*
 * Class:     org_apache_hugegraph_backend_store_kvt_KVTNative
 * Method:    nativeVertexPropertyUpdate
 * Signature: (JJ[B[B)[Ljava/lang/Object;
 */
extern "C" JNIEXPORT jobjectArray JNICALL Java_org_apache_hugegraph_backend_store_kvt_KVTNative_nativeVertexPropertyUpdate
  (JNIEnv *env, jclass cls, jlong txId, jlong tableId, jbyteArray key, jbyteArray propertyUpdate) {
    
    std::string keyStr = ByteArrayToString(env, key);
    std::string paramStr = ByteArrayToString(env, propertyUpdate);
    std::string resultValue;
    std::string errorMsg;
    
    // Create the process function as KVTProcessFunc
    KVTProcessFunc processFunc = hg_update_vertex_property;
    
    // Call kvt_process with our custom function
    KVTKey kvtKey(keyStr);
    KVTError error = kvt_process(
        static_cast<uint64_t>(txId),
        static_cast<uint64_t>(tableId),
        kvtKey,
        processFunc,
        paramStr,
        resultValue,
        errorMsg);
    
    // Create result array: [errorCode, resultValue, errorMsg]
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(3, objectClass, nullptr);
    
    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID intValueOf = env->GetStaticMethodID(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
    jobject errorCode = env->CallStaticObjectMethod(integerClass, intValueOf, static_cast<jint>(error));
    
    env->SetObjectArrayElement(result, 0, errorCode);
    env->SetObjectArrayElement(result, 1, StringToByteArray(env, resultValue));
    env->SetObjectArrayElement(result, 2, StringToJava(env, errorMsg));
    
    return result;
}

/*
 * Helper function to update edge properties using the new KVT process interface.
 * This function deserializes the existing edge data, merges in the new property,
 * and serializes it back.
 * 
 * Format expected:
 * - input.value: [id_bytes][property_data...]
 * - input.parameter: [property_key_len][property_key][property_value_len][property_value]
 */
bool hg_update_edge_property(
    KVTProcessInput& input,
    KVTProcessOutput& output) {
    
    try {
        // Check required inputs
        if (!input.value || !input.parameter) {
            output.return_value = "Missing required input value or parameter";
            return false;
        }
        
        const std::string& original_value = *input.value;
        const std::string& parameter = *input.parameter;
        
        // If original value is empty, this is a new edge (shouldn't happen for property update)
        if (original_value.empty()) {
            output.return_value = "Cannot update property on non-existent edge";
            return false;
        }
        
        // Parse the parameter to get the property update
        // Format: [property_name_len_vint][property_name][property_value_len_vint][property_value]
        size_t param_pos = 0;
        if (parameter.size() < 2) {
            output.return_value = "Invalid property update parameter";
            return false;
        }
        
        // Read property name length (variable int encoding)
        const unsigned char* param_data = reinterpret_cast<const unsigned char*>(parameter.data());
        size_t bytes_read = 0;
        size_t prop_name_len = decodeVInt(param_data + param_pos, bytes_read);
        param_pos += bytes_read;
        
        if (param_pos + prop_name_len > parameter.size()) {
            output.return_value = "Invalid property name length";
            return false;
        }
        
        std::string prop_name(parameter.data() + param_pos, prop_name_len);
        param_pos += prop_name_len;
        
        // Read property value length
        if (param_pos >= parameter.size()) {
            output.return_value = "Missing property value";
            return false;
        }
        
        size_t prop_value_len = decodeVInt(param_data + param_pos, bytes_read);
        param_pos += bytes_read;
        
        if (param_pos + prop_value_len > parameter.size()) {
            output.return_value = "Invalid property value length";
            return false;
        }
        
        std::string prop_value(parameter.data() + param_pos, prop_value_len);
        
        // Parse the original value and rebuild it with the updated property
        const unsigned char* orig_data = reinterpret_cast<const unsigned char*>(original_value.data());
        size_t orig_pos = 0;
        
        // Parse all columns and update the target property
        std::vector<std::pair<std::string, std::string>> columns;
        bool property_found = false;
        
        // Find where columns start (after the ID bytes)
        // Simple heuristic: look for first valid column structure
        size_t id_end_pos = 0;
        for (size_t scan = 0; scan < original_value.size() - 2; scan++) {
            size_t test_len = orig_data[scan];
            if (test_len > 0 && test_len < 100 && scan + 1 + test_len < original_value.size()) {
                size_t next = scan + 1 + test_len;
                if (next < original_value.size()) {
                    // Looks like a valid column
                    id_end_pos = scan;
                    break;
                }
            }
        }
        
        // Build the new value
        std::string new_value;
        // Copy ID bytes
        new_value.append(original_value.data(), id_end_pos);
        
        // Parse existing columns
        orig_pos = id_end_pos;
        while (orig_pos < original_value.size()) {
            // Read column name length
            size_t col_name_len = 0;
            try {
                col_name_len = decodeVInt(orig_data + orig_pos, bytes_read);
                orig_pos += bytes_read;
            } catch (...) {
                break; // End of columns
            }
            
            if (orig_pos + col_name_len > original_value.size()) break;
            
            std::string col_name(original_value.data() + orig_pos, col_name_len);
            orig_pos += col_name_len;
            
            if (orig_pos >= original_value.size()) break;
            
            // Read column value length
            size_t col_value_len = decodeVInt(orig_data + orig_pos, bytes_read);
            orig_pos += bytes_read;
            
            if (orig_pos + col_value_len > original_value.size()) break;
            
            std::string col_value(original_value.data() + orig_pos, col_value_len);
            orig_pos += col_value_len;
            
            // Update property if it matches
            if (col_name == prop_name) {
                columns.push_back({col_name, prop_value});
                property_found = true;
            } else {
                columns.push_back({col_name, col_value});
            }
        }
        
        // Add property if not found
        if (!property_found) {
            columns.push_back({prop_name, prop_value});
        }
        
        // Write all columns back
        for (const auto& col : columns) {
            encodeVInt(col.first.size(), new_value);
            new_value.append(col.first);
            encodeVInt(col.second.size(), new_value);
            new_value.append(col.second);
        }
        
        // Set outputs
        output.update_value = new_value;
        output.return_value = "Edge property updated successfully";
        output.delete_key = false;
        
        return true;
        
    } catch (const std::exception& e) {
        output.return_value = std::string("Error updating edge property: ") + e.what();
        return false;
    }
}

/*
 * Class:     org_apache_hugegraph_backend_store_kvt_KVTNative
 * Method:    nativeEdgePropertyUpdate
 * Signature: (JJ[B[B)[Ljava/lang/Object;
 */
extern "C" JNIEXPORT jobjectArray JNICALL Java_org_apache_hugegraph_backend_store_kvt_KVTNative_nativeEdgePropertyUpdate
  (JNIEnv *env, jclass cls, jlong txId, jlong tableId, jbyteArray key, jbyteArray propertyUpdate) {
    
    std::string keyStr = ByteArrayToString(env, key);
    std::string paramStr = ByteArrayToString(env, propertyUpdate);
    std::string resultValue;
    std::string errorMsg;
    
    // Create the process function as KVTProcessFunc
    KVTProcessFunc processFunc = hg_update_edge_property;
    
    // Call kvt_process with our custom function
    KVTKey kvtKey(keyStr);
    KVTError error = kvt_process(
        static_cast<uint64_t>(txId),
        static_cast<uint64_t>(tableId),
        kvtKey,
        processFunc,
        paramStr,
        resultValue,
        errorMsg);
    
    // Create result array: [errorCode, resultValue, errorMsg]
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(3, objectClass, nullptr);
    
    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID intValueOf = env->GetStaticMethodID(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
    jobject errorCode = env->CallStaticObjectMethod(integerClass, intValueOf, static_cast<jint>(error));
    
    env->SetObjectArrayElement(result, 0, errorCode);
    env->SetObjectArrayElement(result, 1, StringToByteArray(env, resultValue));
    env->SetObjectArrayElement(result, 2, StringToJava(env, errorMsg));
    
    return result;
}


/*
 * Class:     org_apache_hugegraph_backend_store_kvt_KVTNative
 * Method:    nativeBatchGet
 * Signature: (JJ[[B)[Ljava/lang/Object;
 */
extern "C" JNIEXPORT jobjectArray JNICALL Java_org_apache_hugegraph_backend_store_kvt_KVTNative_nativeBatchGet
  (JNIEnv *env, jclass cls, jlong txId, jlong tableId, jobjectArray keys) {
    
    jsize numKeys = env->GetArrayLength(keys);
    std::vector<KVTOp> batch_ops;
    batch_ops.reserve(numKeys);
    
    // Build batch GET operations
    for (jsize i = 0; i < numKeys; i++) {
        jbyteArray keyArray = (jbyteArray) env->GetObjectArrayElement(keys, i);
        std::string keyStr = ByteArrayToString(env, keyArray);
        env->DeleteLocalRef(keyArray);
        
        KVTOp op;
        op.op = OP_GET;
        op.table_id = static_cast<uint64_t>(tableId);
        op.key = KVTKey(keyStr);
        batch_ops.push_back(op);
    }
    
    // Execute batch operation
    KVTBatchResults batch_results;
    std::string errorMsg;
    KVTError error = kvt_batch_execute(
        static_cast<uint64_t>(txId),
        batch_ops,
        batch_results,
        errorMsg);
    
    // Create result array: [errorCode, errorMsg, values[]]
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(3, objectClass, nullptr);
    
    jclass integerClass = env->FindClass("java/lang/Integer");
    jmethodID intValueOf = env->GetStaticMethodID(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
    jobject errorCode = env->CallStaticObjectMethod(integerClass, intValueOf, static_cast<jint>(error));
    
    env->SetObjectArrayElement(result, 0, errorCode);
    env->SetObjectArrayElement(result, 1, StringToJava(env, errorMsg));
    
    if (error == KVTError::SUCCESS || error == KVTError::BATCH_NOT_FULLY_SUCCESS) {
        // Create byte array of values
        jclass byteArrayClass = env->FindClass("[B");
        jobjectArray values = env->NewObjectArray(numKeys, byteArrayClass, nullptr);
        
        for (jsize i = 0; i < numKeys && i < batch_results.size(); i++) {
            if (batch_results[i].error == KVTError::SUCCESS) {
                jbyteArray valueArray = StringToByteArray(env, batch_results[i].value);
                env->SetObjectArrayElement(values, i, valueArray);
                env->DeleteLocalRef(valueArray);
            }
        }
        env->SetObjectArrayElement(result, 2, values);
    }
    
    return result;
}
