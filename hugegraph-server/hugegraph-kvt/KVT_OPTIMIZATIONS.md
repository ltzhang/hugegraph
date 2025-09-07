# KVT Query Optimization Recommendations

Based on comparison with RocksDB implementation, here are recommended optimizations for KVT:

## 1. Enable Pushdown Functions (HIGH PRIORITY)
The pushdown infrastructure exists but is not connected to query execution.

### Required Changes:
- Modify `KVTTable.queryBy()` to use pushdown functions when available
- Update `KVTTable.queryNumber()` to use native COUNT aggregation
- Connect `KVTQueryOptimizer` to actual query execution

### Implementation Example:
```java
// In KVTTable.queryNumber()
if (aggregate.func() == AggregateFunc.COUNT) {
    // Use pushdown count instead of iterating
    CountAggregationFunction countFunc = new CountAggregationFunction();
    return session.aggregateRange(tableId, startKey, endKey, countFunc);
}
```

## 2. Implement Countable Interface
Add efficient counting without full iteration:

```java
public interface KVTCountable {
    long count();
}

// Implement in KVTIterator
public class KVTIterator implements BackendColumnIterator, KVTCountable {
    @Override
    public long count() {
        // Use native count or statistics
        return KVTNative.countRange(tableId, startKey, endKey);
    }
}
```

## 3. Add Batch Get Operations
Currently missing efficient multi-get:

```java
// Add to KVTSession
public List<byte[]> multiGet(long tableId, List<byte[]> keys) {
    // Native batch get implementation
    return KVTNative.multiGet(tableId, keys);
}
```

## 4. Optimize Filter Pushdown
Connect existing filter functions to query execution:

```java
// In KVTTable.queryByCond()
if (hasPropertyFilters(conditions)) {
    PropertyFilterFunction filter = buildFilter(conditions);
    return session.scanWithFilter(tableId, startKey, endKey, filter);
}
```

## 5. Add Query Statistics
Implement storage-level statistics for query optimization:

```java
// Add to KVTNative
public static class TableStats {
    public long estimatedKeys;
    public long estimatedSize;
    public byte[] minKey;
    public byte[] maxKey;
}

public static TableStats getTableStats(long tableId);
```

## 6. Implement Lazy Iterator Creation
Avoid creating iterators until needed:

```java
// Use supplier pattern
Supplier<BackendColumnIterator> lazyIterator = () -> {
    return new KVTIterator(session, tableId, startKey, endKey);
};
```

## 7. Add Sampling Support
Use existing sampling pushdown for OLAP queries:

```java
// Connect to query execution
if (query.isOlapQuery() && query.getSampleRate() < 1.0) {
    SamplingFunction sampler = new SamplingFunction(query.getSampleRate());
    return session.scanWithSampling(tableId, sampler);
}
```

## Priority Implementation Order

1. **Enable COUNT pushdown** - Immediate performance gain for aggregations
2. **Connect filter pushdown** - Reduce data transfer for filtered queries  
3. **Add batch get** - Optimize multi-ID queries
4. **Implement Countable** - Efficient counting without iteration
5. **Add statistics** - Better query planning
6. **Enable sampling** - OLAP query optimization
7. **Lazy iterators** - Memory optimization

## Performance Impact Estimates

- COUNT queries: **10-100x faster** (no iteration needed)
- Filtered queries: **2-10x faster** (less data transfer)
- Multi-ID queries: **2-5x faster** (batch operations)
- OLAP sampling: **10-100x faster** (process fraction of data)

## Testing Requirements

- Verify pushdown functions with edge cases
- Test filter combinations and complex conditions
- Benchmark against RocksDB implementation
- Validate ACID properties maintained with pushdown