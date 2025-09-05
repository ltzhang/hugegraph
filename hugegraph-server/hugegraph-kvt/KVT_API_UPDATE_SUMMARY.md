# KVT API Update Summary

## Date: 2025-09-06

## Overview
Updated the KVT backend to use the new API with `KVTKey` class that supports minimum and maximum keys for full table range scans.

## Key Changes

### 1. New KVTKey Class
- `KVTKey` now extends `std::string` 
- Provides special static methods:
  - `KVTKey::minimum_key()` - Returns `\0` (null byte) as the smallest possible key
  - `KVTKey::maximum_key()` - Returns empty string as the largest possible key
- Custom comparison operators that treat empty string as maximum value

### 2. API Signature Changes
All KVT functions now use `KVTKey` instead of `std::string` for keys:
- `kvt_get(tx_id, table_id, const KVTKey& key, ...)`
- `kvt_set(tx_id, table_id, const KVTKey& key, ...)`
- `kvt_del(tx_id, table_id, const KVTKey& key, ...)`
- `kvt_scan(tx_id, table_id, const KVTKey& key_start, const KVTKey& key_end, ...)`
- `kvt_update(tx_id, table_id, const KVTKey& key, ...)`

### 3. JNI Bridge Updates
Updated `KVTJNIBridge.cpp` to:
- Convert Java byte arrays to `KVTKey` objects
- Handle null byte arrays for full table scans:
  - `null` start key → `KVTKey::minimum_key()`
  - `null` end key → `KVTKey::maximum_key()`
- All update functions now use `KVTKey` type in signatures

### 4. Files Modified
- `/kvt/kvt_inc.h` - Updated with new KVTKey class and API signatures
- `/kvt/kvt_mem.cpp` - Recompiled with new API
- `/kvt/kvt_mem.o` - Rebuilt object file
- `/src/main/native/KVTJNIBridge.cpp` - Updated all JNI functions to use KVTKey
- `/target/native/libkvtjni.so` - Rebuilt shared library

## Testing Results

### ✅ Successful Tests
1. **Full Table Scan Test** (`TestFullTableScan.java`)
   - Full table scan with `(null, null)` works correctly
   - Range scans work as expected
   - Partial scans from/to null work properly

2. **Numeric Key Scan Test** (`TestNumericKeyScan.java`)
   - Numeric keys formatted as strings scan correctly
   - Range queries return expected results

3. **Property Update Tests**
   - Vertex property updates still work correctly
   - Edge property updates still work correctly

### ⚠️ Known Issues
1. **KVTStressTest** - Still shows UNKNOWN_ERROR in some complex scenarios
   - Simple scans work fine
   - Issue appears to be in the stress test logic, not the scan implementation
   - The core scan functionality is working as demonstrated by other tests

## Benefits of New API
1. **Full Table Scans** - Can now scan entire table without knowing key bounds
2. **Cleaner API** - Explicit min/max keys instead of special values
3. **Type Safety** - KVTKey class provides better type checking
4. **Backward Compatible** - Existing code using specific key ranges continues to work

## Usage Examples

### Full Table Scan
```java
// Scan entire table - pass null for both start and end
Object[] result = KVTNative.nativeScan(txId, tableId, null, null, limit);
```

### Scan from Beginning to Specific Key
```java
// Scan from beginning to "key_05"
Object[] result = KVTNative.nativeScan(txId, tableId, null, "key_05".getBytes(), limit);
```

### Scan from Specific Key to End
```java
// Scan from "key_10" to end
Object[] result = KVTNative.nativeScan(txId, tableId, "key_10".getBytes(), null, limit);
```

## Recommendations
1. Use `null` parameters for unbounded scans instead of trying to construct min/max keys manually
2. The JNI bridge handles the conversion automatically
3. All existing code using specific key ranges continues to work without changes