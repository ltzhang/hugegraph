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
    std::string value;
    std::string errorMsg;
    
    KVTError error = kvt_get(static_cast<uint64_t>(txId), 
                            static_cast<uint64_t>(tableId),
                            keyStr, value, errorMsg);
    
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
    std::string valueStr = ByteArrayToString(env, value);
    std::string errorMsg;
    
    KVTError error = kvt_set(static_cast<uint64_t>(txId),
                            static_cast<uint64_t>(tableId),
                            keyStr, valueStr, errorMsg);
    
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
    std::string errorMsg;
    
    KVTError error = kvt_del(static_cast<uint64_t>(txId),
                            static_cast<uint64_t>(tableId),
                            keyStr, errorMsg);
    
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
    std::string keyStartStr = ByteArrayToString(env, keyStart);
    std::string keyEndStr = ByteArrayToString(env, keyEnd);
    std::vector<std::pair<std::string, std::string>> results;
    std::string errorMsg;
    
    KVTError error = kvt_scan(static_cast<uint64_t>(txId),
                             static_cast<uint64_t>(tableId),
                             keyStartStr, keyEndStr,
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
        env->SetObjectArrayElement(keys, static_cast<jsize>(i), 
                                  StringToByteArray(env, results[i].first));
        env->SetObjectArrayElement(values, static_cast<jsize>(i), 
                                  StringToByteArray(env, results[i].second));
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
            env->SetObjectArrayElement(resultValues, static_cast<jsize>(i), 
                                      StringToByteArray(env, batchResults[i].value));
        }
    }
    env->ReleaseIntArrayElements(resultCodes, resultCodesArray, 0);
    
    env->SetObjectArrayElement(result, 0, errorCode);
    env->SetObjectArrayElement(result, 1, resultCodes);
    env->SetObjectArrayElement(result, 2, resultValues);
    env->SetObjectArrayElement(result, 3, StringToJava(env, errorMsg));
    
    return result;
}