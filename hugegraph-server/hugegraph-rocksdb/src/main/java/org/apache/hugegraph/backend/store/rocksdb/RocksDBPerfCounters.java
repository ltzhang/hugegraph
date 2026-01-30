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

package org.apache.hugegraph.backend.store.rocksdb;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

/**
 * Performance counters for RocksDB backend operations.
 *
 * Tracks per-operation: count, total latency (ns), max latency (ns),
 * in-flight concurrency, and scan result sizes.
 *
 * Thread-safe via LongAdder (counts/totals) and AtomicLong (max/concurrency).
 * Call {@link #dump()} to print a summary to the logger.
 */
public final class RocksDBPerfCounters {

    private static final Logger LOG = Log.logger(RocksDBPerfCounters.class);

    private static final RocksDBPerfCounters INSTANCE = new RocksDBPerfCounters();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            INSTANCE.dump();
        }, "rocksdb-perf-dump"));
    }

    public static RocksDBPerfCounters instance() {
        return INSTANCE;
    }

    // ---- Per-operation stats ----

    // Put (WriteBatch buffer, not actual I/O)
    private final LongAdder putCount = new LongAdder();
    private final LongAdder putTotalNs = new LongAdder();
    private final AtomicLong putMaxNs = new AtomicLong(0);

    // Get (direct RocksDB read)
    private final LongAdder getCount = new LongAdder();
    private final LongAdder getTotalNs = new LongAdder();
    private final AtomicLong getMaxNs = new AtomicLong(0);
    private final AtomicLong getInflight = new AtomicLong(0);
    private final AtomicLong getMaxInflight = new AtomicLong(0);
    private final LongAdder getHitCount = new LongAdder();
    private final LongAdder getMissCount = new LongAdder();

    // Multi-get
    private final LongAdder mgetCount = new LongAdder();
    private final LongAdder mgetTotalNs = new LongAdder();
    private final AtomicLong mgetMaxNs = new AtomicLong(0);
    private final LongAdder mgetTotalKeys = new LongAdder();

    // Delete (WriteBatch buffer)
    private final LongAdder deleteCount = new LongAdder();
    private final LongAdder deleteTotalNs = new LongAdder();
    private final AtomicLong deleteMaxNs = new AtomicLong(0);

    // Merge (WriteBatch buffer)
    private final LongAdder mergeCount = new LongAdder();

    // Increase (immediate rocksdb().merge)
    private final LongAdder increaseCount = new LongAdder();
    private final LongAdder increaseTotalNs = new LongAdder();
    private final AtomicLong increaseMaxNs = new AtomicLong(0);

    // Scan (iterator creation)
    private final LongAdder scanCount = new LongAdder();
    private final LongAdder scanTotalNs = new LongAdder();
    private final AtomicLong scanMaxNs = new AtomicLong(0);
    private final AtomicLong scanInflight = new AtomicLong(0);
    private final AtomicLong scanMaxInflight = new AtomicLong(0);

    // Commit (WriteBatch write to RocksDB)
    private final LongAdder commitCount = new LongAdder();
    private final LongAdder commitTotalNs = new LongAdder();
    private final AtomicLong commitMaxNs = new AtomicLong(0);
    private final AtomicLong commitInflight = new AtomicLong(0);
    private final AtomicLong commitMaxInflight = new AtomicLong(0);
    private final LongAdder commitTotalOps = new LongAdder();

    // Wall clock reference
    private final long startTimeMs = System.currentTimeMillis();

    private RocksDBPerfCounters() {
    }

    // ---- Recording methods ----

    public void recordPut(long elapsedNs) {
        this.putCount.increment();
        this.putTotalNs.add(elapsedNs);
        updateMax(this.putMaxNs, elapsedNs);
    }

    public long enterGet() {
        long inflight = this.getInflight.incrementAndGet();
        updateMax(this.getMaxInflight, inflight);
        return System.nanoTime();
    }

    public void exitGet(long startNs, boolean hit) {
        this.getInflight.decrementAndGet();
        long elapsed = System.nanoTime() - startNs;
        this.getCount.increment();
        this.getTotalNs.add(elapsed);
        updateMax(this.getMaxNs, elapsed);
        if (hit) {
            this.getHitCount.increment();
        } else {
            this.getMissCount.increment();
        }
    }

    public void recordMget(long elapsedNs, int keyCount) {
        this.mgetCount.increment();
        this.mgetTotalNs.add(elapsedNs);
        updateMax(this.mgetMaxNs, elapsedNs);
        this.mgetTotalKeys.add(keyCount);
    }

    public void recordDelete(long elapsedNs) {
        this.deleteCount.increment();
        this.deleteTotalNs.add(elapsedNs);
        updateMax(this.deleteMaxNs, elapsedNs);
    }

    public void recordMerge() {
        this.mergeCount.increment();
    }

    public void recordIncrease(long elapsedNs) {
        this.increaseCount.increment();
        this.increaseTotalNs.add(elapsedNs);
        updateMax(this.increaseMaxNs, elapsedNs);
    }

    public long enterScan() {
        long inflight = this.scanInflight.incrementAndGet();
        updateMax(this.scanMaxInflight, inflight);
        return System.nanoTime();
    }

    public void exitScan(long startNs) {
        this.scanInflight.decrementAndGet();
        long elapsed = System.nanoTime() - startNs;
        this.scanCount.increment();
        this.scanTotalNs.add(elapsed);
        updateMax(this.scanMaxNs, elapsed);
    }

    public long enterCommit() {
        long inflight = this.commitInflight.incrementAndGet();
        updateMax(this.commitMaxInflight, inflight);
        return System.nanoTime();
    }

    public void exitCommit(long startNs, int opCount) {
        this.commitInflight.decrementAndGet();
        long elapsed = System.nanoTime() - startNs;
        this.commitCount.increment();
        this.commitTotalNs.add(elapsed);
        updateMax(this.commitMaxNs, elapsed);
        this.commitTotalOps.add(opCount);
    }

    // ---- Dump ----

    public void dump() {
        long elapsed = System.currentTimeMillis() - this.startTimeMs;
        double elapsedSec = elapsed / 1000.0;

        StringBuilder sb = new StringBuilder(2048);
        sb.append("\n");
        sb.append("=================================================================\n");
        sb.append("  RocksDB Performance Counters (");
        sb.append(String.format("%.1f", elapsedSec));
        sb.append("s wall time)\n");
        sb.append("=================================================================\n");

        appendOp(sb, "PUT", this.putCount, this.putTotalNs,
                 this.putMaxNs, null);
        appendOp(sb, "GET", this.getCount, this.getTotalNs,
                 this.getMaxNs, this.getMaxInflight);
        long gets = this.getCount.sum();
        if (gets > 0) {
            sb.append(String.format(
                "  GET hit/miss:       %,d / %,d  (%.1f%% hit rate)\n",
                this.getHitCount.sum(), this.getMissCount.sum(),
                100.0 * this.getHitCount.sum() / gets));
        }
        long mgets = this.mgetCount.sum();
        if (mgets > 0) {
            sb.append(String.format(
                "  MGET:               count=%,d  avg=%.1fus  max=%.1fus" +
                "  total=%.1fms  avg_keys=%.1f\n",
                mgets,
                (this.mgetTotalNs.sum() / (double) mgets) / 1000.0,
                this.mgetMaxNs.get() / 1000.0,
                this.mgetTotalNs.sum() / 1_000_000.0,
                (double) this.mgetTotalKeys.sum() / mgets));
        }
        appendOp(sb, "DELETE", this.deleteCount, this.deleteTotalNs,
                 this.deleteMaxNs, null);
        sb.append(String.format("  MERGE:              count=%,d\n",
                                this.mergeCount.sum()));
        appendOp(sb, "INCREASE", this.increaseCount, this.increaseTotalNs,
                 this.increaseMaxNs, null);
        appendOp(sb, "SCAN", this.scanCount, this.scanTotalNs,
                 this.scanMaxNs, this.scanMaxInflight);

        sb.append("  ---------------------------------------------------------------\n");
        appendOp(sb, "COMMIT", this.commitCount, this.commitTotalNs,
                 this.commitMaxNs, this.commitMaxInflight);
        long commits = this.commitCount.sum();
        if (commits > 0) {
            sb.append(String.format(
                "  COMMIT ops:         total=%,d  avg=%.1f per commit\n",
                this.commitTotalOps.sum(),
                (double) this.commitTotalOps.sum() / commits));
        }

        sb.append("=================================================================\n");

        LOG.info(sb.toString());
        System.err.println(sb.toString());
    }

    private static void appendOp(StringBuilder sb, String name,
                                  LongAdder count, LongAdder totalNs,
                                  AtomicLong maxNs, AtomicLong maxInflight) {
        long cnt = count.sum();
        if (cnt == 0) {
            sb.append(String.format("  %-18s count=0\n", name + ":"));
            return;
        }
        long total = totalNs.sum();
        double avgUs = (total / (double) cnt) / 1000.0;
        double maxUs = maxNs.get() / 1000.0;
        double totalMs = total / 1_000_000.0;

        sb.append(String.format(
            "  %-18s count=%,d  avg=%.1fus  max=%.1fus  total=%.1fms",
            name + ":", cnt, avgUs, maxUs, totalMs));
        if (maxInflight != null) {
            sb.append(String.format("  max_inflight=%d", maxInflight.get()));
        }
        sb.append("\n");
    }

    private static void updateMax(AtomicLong max, long value) {
        long cur;
        while ((cur = max.get()) < value) {
            if (max.compareAndSet(cur, value)) {
                break;
            }
        }
    }
}
