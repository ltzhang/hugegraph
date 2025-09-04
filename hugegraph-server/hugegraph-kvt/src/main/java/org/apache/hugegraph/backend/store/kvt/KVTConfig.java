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

import org.apache.hugegraph.config.ConfigOption;
import org.apache.hugegraph.config.OptionHolder;

import static org.apache.hugegraph.config.OptionChecker.disallowEmpty;
import static org.apache.hugegraph.config.OptionChecker.allowValues;
import static org.apache.hugegraph.config.OptionChecker.rangeInt;

/**
 * Configuration options for KVT backend.
 */
public class KVTConfig extends OptionHolder {

    private KVTConfig() {
        super();
    }

    private static volatile KVTConfig instance;

    public static synchronized KVTConfig instance() {
        if (instance == null) {
            instance = new KVTConfig();
            instance.registerOptions();
        }
        return instance;
    }

    // Native library settings
    public static final ConfigOption<String> NATIVE_LIB_PATH =
            new ConfigOption<>(
                    "kvt.native.lib.path",
                    "Path to KVT native library",
                    disallowEmpty(),
                    "${HUGEGRAPH_HOME}/lib/native/libkvtjni.so"
            );

    // Transaction settings
    public static final ConfigOption<String> TRANSACTION_ISOLATION_LEVEL =
            new ConfigOption<>(
                    "kvt.transaction.isolation_level",
                    "Transaction isolation level",
                    allowValues("DEFAULT", "READ_UNCOMMITTED", "READ_COMMITTED", 
                              "REPEATABLE_READ", "SERIALIZABLE"),
                    "DEFAULT"
            );

    public static final ConfigOption<Long> TRANSACTION_TIMEOUT =
            new ConfigOption<>(
                    "kvt.transaction.timeout",
                    "Transaction timeout in milliseconds",
                    rangeInt(1000L, 3600000L),
                    30000L
            );

    public static final ConfigOption<Integer> TRANSACTION_RETRY_TIMES =
            new ConfigOption<>(
                    "kvt.transaction.retry.times",
                    "Number of transaction retry attempts",
                    rangeInt(0, 10),
                    3
            );

    // Batch settings
    public static final ConfigOption<Boolean> BATCH_ENABLED =
            new ConfigOption<>(
                    "kvt.batch.enabled",
                    "Enable batch operations",
                    disallowEmpty(),
                    true
            );

    public static final ConfigOption<Integer> BATCH_SIZE =
            new ConfigOption<>(
                    "kvt.batch.size",
                    "Batch operation size",
                    rangeInt(1, 10000),
                    1000
            );

    // Cache settings
    public static final ConfigOption<Boolean> CACHE_ENABLED =
            new ConfigOption<>(
                    "kvt.cache.enabled",
                    "Enable query result cache",
                    disallowEmpty(),
                    true
            );

    public static final ConfigOption<Integer> CACHE_MAX_SIZE =
            new ConfigOption<>(
                    "kvt.cache.max_size",
                    "Maximum cache entries",
                    rangeInt(100, 1000000),
                    10000
            );

    public static final ConfigOption<Long> CACHE_TTL =
            new ConfigOption<>(
                    "kvt.cache.ttl",
                    "Cache TTL in milliseconds",
                    rangeInt(1000L, 3600000L),
                    60000L
            );

    // Query optimization settings
    public static final ConfigOption<Boolean> OPTIMIZER_ENABLED =
            new ConfigOption<>(
                    "kvt.query.optimizer.enabled",
                    "Enable query optimizer",
                    disallowEmpty(),
                    true
            );

    public static final ConfigOption<Long> SLOW_QUERY_THRESHOLD =
            new ConfigOption<>(
                    "kvt.query.slow_threshold",
                    "Slow query threshold in milliseconds",
                    rangeInt(100L, 60000L),
                    1000L
            );

    public static final ConfigOption<Boolean> STATS_ENABLED =
            new ConfigOption<>(
                    "kvt.query.stats.enabled",
                    "Enable query statistics",
                    disallowEmpty(),
                    true
            );

    // Index settings
    public static final ConfigOption<Boolean> INDEX_ENABLED =
            new ConfigOption<>(
                    "kvt.index.enabled",
                    "Enable index support",
                    disallowEmpty(),
                    true
            );

    public static final ConfigOption<Integer> INDEX_REBUILD_BATCH_SIZE =
            new ConfigOption<>(
                    "kvt.index.rebuild_batch_size",
                    "Batch size for index rebuild",
                    rangeInt(100, 10000),
                    1000
            );

    // Session pool settings
    public static final ConfigOption<Integer> SESSION_POOL_SIZE =
            new ConfigOption<>(
                    "kvt.session.pool.size",
                    "Session pool size",
                    rangeInt(1, 1000),
                    20
            );

    public static final ConfigOption<Integer> SESSION_POOL_MAX_SIZE =
            new ConfigOption<>(
                    "kvt.session.pool.max_size",
                    "Maximum session pool size",
                    rangeInt(1, 10000),
                    100
            );

    // Storage settings
    public static final ConfigOption<String> STORAGE_DATA_PATH =
            new ConfigOption<>(
                    "kvt.storage.data_path",
                    "Data storage path",
                    disallowEmpty(),
                    "${HUGEGRAPH_HOME}/data/kvt"
            );

    public static final ConfigOption<String> STORAGE_WAL_PATH =
            new ConfigOption<>(
                    "kvt.storage.wal_path",
                    "Write-ahead log path",
                    disallowEmpty(),
                    "${HUGEGRAPH_HOME}/data/kvt/wal"
            );

    public static final ConfigOption<String> STORAGE_COMPRESSION =
            new ConfigOption<>(
                    "kvt.storage.compression",
                    "Storage compression algorithm",
                    allowValues("NONE", "SNAPPY", "LZ4", "ZSTD"),
                    "SNAPPY"
            );

    // Memory settings
    public static final ConfigOption<Long> MEMORY_BUFFER_SIZE =
            new ConfigOption<>(
                    "kvt.memory.buffer_size",
                    "Memory buffer size in bytes",
                    rangeInt(1048576L, 1073741824L),
                    134217728L
            );

    public static final ConfigOption<Long> MEMORY_CACHE_SIZE =
            new ConfigOption<>(
                    "kvt.memory.cache_size",
                    "Memory cache size in bytes",
                    rangeInt(1048576L, 10737418240L),
                    536870912L
            );

    // Performance settings
    public static final ConfigOption<Integer> PERFORMANCE_READ_THREADS =
            new ConfigOption<>(
                    "kvt.performance.read_threads",
                    "Number of read threads",
                    rangeInt(1, 128),
                    16
            );

    public static final ConfigOption<Integer> PERFORMANCE_WRITE_THREADS =
            new ConfigOption<>(
                    "kvt.performance.write_threads",
                    "Number of write threads",
                    rangeInt(1, 64),
                    8
            );

    public static final ConfigOption<Boolean> PERFORMANCE_SCAN_PREFETCH =
            new ConfigOption<>(
                    "kvt.performance.scan_prefetch",
                    "Enable scan prefetching",
                    disallowEmpty(),
                    true
            );

    // Metrics settings
    public static final ConfigOption<Boolean> METRICS_ENABLED =
            new ConfigOption<>(
                    "kvt.metrics.enabled",
                    "Enable metrics collection",
                    disallowEmpty(),
                    true
            );

    public static final ConfigOption<Long> METRICS_INTERVAL =
            new ConfigOption<>(
                    "kvt.metrics.interval",
                    "Metrics collection interval in milliseconds",
                    rangeInt(1000L, 3600000L),
                    60000L
            );

    // Debug settings
    public static final ConfigOption<Boolean> DEBUG_ENABLED =
            new ConfigOption<>(
                    "kvt.debug.enabled",
                    "Enable debug mode",
                    disallowEmpty(),
                    false
            );

    public static final ConfigOption<String> DEBUG_LOG_LEVEL =
            new ConfigOption<>(
                    "kvt.debug.log_level",
                    "Debug log level",
                    allowValues("TRACE", "DEBUG", "INFO", "WARN", "ERROR"),
                    "INFO"
            );

    public static ConfigGroup group(String name) {
        return new ConfigGroup(name);
    }

    public static class ConfigGroup {
        private final String prefix;

        public ConfigGroup(String prefix) {
            this.prefix = prefix;
        }

        public ConfigOption<String> getString(String key, String defaultValue) {
            return new ConfigOption<>(
                    prefix + "." + key,
                    "",
                    defaultValue
            );
        }

        public ConfigOption<Integer> getInt(String key, int defaultValue) {
            return new ConfigOption<>(
                    prefix + "." + key,
                    "",
                    defaultValue
            );
        }

        public ConfigOption<Long> getLong(String key, long defaultValue) {
            return new ConfigOption<>(
                    prefix + "." + key,
                    "",
                    defaultValue
            );
        }

        public ConfigOption<Boolean> getBoolean(String key, boolean defaultValue) {
            return new ConfigOption<>(
                    prefix + "." + key,
                    "",
                    defaultValue
            );
        }
    }
}