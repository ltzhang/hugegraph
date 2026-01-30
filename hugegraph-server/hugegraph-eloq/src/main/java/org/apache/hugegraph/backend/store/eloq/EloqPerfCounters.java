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

package org.apache.hugegraph.backend.store.eloq;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

/**
 * Performance counters for EloqRocks JNI operations.
 *
 * Tracks per-operation: count, total latency (ns), max latency (ns),
 * in-flight concurrency, and scan result sizes.
 *
 * Thread-safe via LongAdder (counts/totals) and AtomicLong (max/concurrency).
 * Call {@link #dump()} to print a summary to the logger.
 */
public final class EloqPerfCounters {

    private static final Logger LOG = Log.logger(EloqPerfCounters.class);

    // Singleton instance
    private static final EloqPerfCounters INSTANCE = new EloqPerfCounters();

    public static EloqPerfCounters instance() {
        return INSTANCE;
    }

    // ---- Per-operation stats ----

    // Put
    private final LongAdder putCount = new LongAdder();
    private final LongAdder putTotalNs = new LongAdder();
    private final AtomicLong putMaxNs = new AtomicLong(0);
    private final AtomicLong putInflight = new AtomicLong(0);
    private final AtomicLong putMaxInflight = new AtomicLong(0);

    // Get
    private final LongAdder getCount = new LongAdder();
    private final LongAdder getTotalNs = new LongAdder();
    private final AtomicLong getMaxNs = new AtomicLong(0);
    private final AtomicLong getInflight = new AtomicLong(0);
    private final AtomicLong getMaxInflight = new AtomicLong(0);
    private final LongAdder getHitCount = new LongAdder();
    private final LongAdder getMissCount = new LongAdder();

    // Delete
    private final LongAdder deleteCount = new LongAdder();
    private final LongAdder deleteTotalNs = new LongAdder();
    private final AtomicLong deleteMaxNs = new AtomicLong(0);

    // Scan
    private final LongAdder scanCount = new LongAdder();
    private final LongAdder scanTotalNs = new LongAdder();
    private final AtomicLong scanMaxNs = new AtomicLong(0);
    private final AtomicLong scanInflight = new AtomicLong(0);
    private final AtomicLong scanMaxInflight = new AtomicLong(0);
    private final LongAdder scanTotalResults = new LongAdder();
    private final AtomicLong scanMaxResults = new AtomicLong(0);

    // Transaction lifecycle
    private final LongAdder startTxCount = new LongAdder();
    private final LongAdder startTxTotalNs = new LongAdder();
    private final AtomicLong startTxMaxNs = new AtomicLong(0);

    private final LongAdder commitTxCount = new LongAdder();
    private final LongAdder commitTxTotalNs = new LongAdder();
    private final AtomicLong commitTxMaxNs = new AtomicLong(0);

    private final LongAdder abortTxCount = new LongAdder();

    // Batch write (single JNI call for all ops)
    private final LongAdder batchWriteCount = new LongAdder();
    private final LongAdder batchWriteTotalNs = new LongAdder();
    private final AtomicLong batchWriteMaxNs = new AtomicLong(0);
    private final LongAdder batchWriteTotalOps = new LongAdder();

    // Session-level commit (batch replay)
    private final LongAdder sessionCommitCount = new LongAdder();
    private final LongAdder sessionCommitTotalNs = new LongAdder();
    private final AtomicLong sessionCommitMaxNs = new AtomicLong(0);
    private final LongAdder sessionCommitTotalOps = new LongAdder();

    // Wall clock reference
    private final long startTimeMs = System.currentTimeMillis();

    private EloqPerfCounters() {
    }

    // ---- Recording methods ----

    public void recordPut(long elapsedNs) {
        this.putCount.increment();
        this.putTotalNs.add(elapsedNs);
        updateMax(this.putMaxNs, elapsedNs);
    }

    public long enterPut() {
        long inflight = this.putInflight.incrementAndGet();
        updateMax(this.putMaxInflight, inflight);
        return System.nanoTime();
    }

    public void exitPut(long startNs) {
        this.putInflight.decrementAndGet();
        recordPut(System.nanoTime() - startNs);
    }

    public void recordGet(long elapsedNs, boolean hit) {
        this.getCount.increment();
        this.getTotalNs.add(elapsedNs);
        updateMax(this.getMaxNs, elapsedNs);
        if (hit) {
            this.getHitCount.increment();
        } else {
            this.getMissCount.increment();
        }
    }

    public long enterGet() {
        long inflight = this.getInflight.incrementAndGet();
        updateMax(this.getMaxInflight, inflight);
        return System.nanoTime();
    }

    public void exitGet(long startNs, boolean hit) {
        this.getInflight.decrementAndGet();
        recordGet(System.nanoTime() - startNs, hit);
    }

    public void recordDelete(long elapsedNs) {
        this.deleteCount.increment();
        this.deleteTotalNs.add(elapsedNs);
        updateMax(this.deleteMaxNs, elapsedNs);
    }

    public void recordScan(long elapsedNs, int resultCount) {
        this.scanCount.increment();
        this.scanTotalNs.add(elapsedNs);
        updateMax(this.scanMaxNs, elapsedNs);
        this.scanTotalResults.add(resultCount);
        updateMax(this.scanMaxResults, resultCount);
    }

    public long enterScan() {
        long inflight = this.scanInflight.incrementAndGet();
        updateMax(this.scanMaxInflight, inflight);
        return System.nanoTime();
    }

    public void exitScan(long startNs, int resultCount) {
        this.scanInflight.decrementAndGet();
        recordScan(System.nanoTime() - startNs, resultCount);
    }

    public void recordStartTx(long elapsedNs) {
        this.startTxCount.increment();
        this.startTxTotalNs.add(elapsedNs);
        updateMax(this.startTxMaxNs, elapsedNs);
    }

    public void recordCommitTx(long elapsedNs) {
        this.commitTxCount.increment();
        this.commitTxTotalNs.add(elapsedNs);
        updateMax(this.commitTxMaxNs, elapsedNs);
    }

    public void recordAbortTx() {
        this.abortTxCount.increment();
    }

    public void recordBatchWrite(long elapsedNs, int opCount) {
        this.batchWriteCount.increment();
        this.batchWriteTotalNs.add(elapsedNs);
        updateMax(this.batchWriteMaxNs, elapsedNs);
        this.batchWriteTotalOps.add(opCount);
    }

    public void recordSessionCommit(long elapsedNs, int opCount) {
        this.sessionCommitCount.increment();
        this.sessionCommitTotalNs.add(elapsedNs);
        updateMax(this.sessionCommitMaxNs, elapsedNs);
        this.sessionCommitTotalOps.add(opCount);
    }

    // ---- Dump ----

    public void dump() {
        long elapsed = System.currentTimeMillis() - this.startTimeMs;
        double elapsedSec = elapsed / 1000.0;

        StringBuilder sb = new StringBuilder(2048);
        sb.append("\n");
        sb.append("=================================================================\n");
        sb.append("  EloqRocks Performance Counters (");
        sb.append(String.format("%.1f", elapsedSec));
        sb.append("s wall time)\n");
        sb.append("=================================================================\n");

        appendOp(sb, "PUT", this.putCount, this.putTotalNs,
                 this.putMaxNs, this.putMaxInflight);
        appendOp(sb, "GET", this.getCount, this.getTotalNs,
                 this.getMaxNs, this.getMaxInflight);
        long gets = this.getCount.sum();
        if (gets > 0) {
            sb.append(String.format(
                "  GET hit/miss:       %,d / %,d  (%.1f%% hit rate)\n",
                this.getHitCount.sum(), this.getMissCount.sum(),
                100.0 * this.getHitCount.sum() / gets));
        }
        appendOp(sb, "DELETE", this.deleteCount, this.deleteTotalNs,
                 this.deleteMaxNs, null);
        appendOp(sb, "SCAN", this.scanCount, this.scanTotalNs,
                 this.scanMaxNs, this.scanMaxInflight);
        long scans = this.scanCount.sum();
        if (scans > 0) {
            sb.append(String.format(
                "  SCAN results:       total=%,d  avg=%.1f  max=%,d\n",
                this.scanTotalResults.sum(),
                (double) this.scanTotalResults.sum() / scans,
                this.scanMaxResults.get()));
        }

        sb.append("  ---------------------------------------------------------------\n");
        long batchWrites = this.batchWriteCount.sum();
        appendOp(sb, "BATCH_WRITE", this.batchWriteCount,
                 this.batchWriteTotalNs, this.batchWriteMaxNs, null);
        if (batchWrites > 0) {
            sb.append(String.format(
                "  BATCH_WRITE ops:    total=%,d  avg=%.1f per batch\n",
                this.batchWriteTotalOps.sum(),
                (double) this.batchWriteTotalOps.sum() / batchWrites));
        }

        sb.append("  ---------------------------------------------------------------\n");
        appendOp(sb, "START_TX", this.startTxCount, this.startTxTotalNs,
                 this.startTxMaxNs, null);
        appendOp(sb, "COMMIT_TX", this.commitTxCount, this.commitTxTotalNs,
                 this.commitTxMaxNs, null);
        sb.append(String.format("  ABORT_TX:           count=%,d\n",
                                this.abortTxCount.sum()));

        sb.append("  ---------------------------------------------------------------\n");
        long sessionCommits = this.sessionCommitCount.sum();
        appendOp(sb, "SESSION_COMMIT", this.sessionCommitCount,
                 this.sessionCommitTotalNs, this.sessionCommitMaxNs, null);
        if (sessionCommits > 0) {
            sb.append(String.format(
                "  SESSION_COMMIT ops: total=%,d  avg=%.1f per commit\n",
                this.sessionCommitTotalOps.sum(),
                (double) this.sessionCommitTotalOps.sum() / sessionCommits));
        }

        sb.append("=================================================================\n");

        LOG.info(sb.toString());
        // Also print to stderr so it's visible in test output
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
