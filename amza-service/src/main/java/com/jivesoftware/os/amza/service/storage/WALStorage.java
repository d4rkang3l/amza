/*
 * Copyright 2013 Jive Software, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.jivesoftware.os.amza.service.storage;

import com.google.common.base.Optional;
import com.google.common.collect.Table;
import com.google.common.collect.TreeBasedTable;
import com.jivesoftware.os.amza.shared.filer.UIO;
import com.jivesoftware.os.amza.shared.partition.VersionedPartitionName;
import com.jivesoftware.os.amza.shared.scan.Commitable;
import com.jivesoftware.os.amza.shared.scan.RangeScannable;
import com.jivesoftware.os.amza.shared.scan.RowStream;
import com.jivesoftware.os.amza.shared.scan.RowType;
import com.jivesoftware.os.amza.shared.scan.RowsChanged;
import com.jivesoftware.os.amza.shared.scan.Scan;
import com.jivesoftware.os.amza.shared.take.Highwaters;
import com.jivesoftware.os.amza.shared.wal.KeyValueStream;
import com.jivesoftware.os.amza.shared.wal.KeyValues;
import com.jivesoftware.os.amza.shared.wal.PrimaryRowMarshaller;
import com.jivesoftware.os.amza.shared.wal.WALHighwater;
import com.jivesoftware.os.amza.shared.wal.WALIndex;
import com.jivesoftware.os.amza.shared.wal.WALKey;
import com.jivesoftware.os.amza.shared.wal.WALKeyPointerStream;
import com.jivesoftware.os.amza.shared.wal.WALKeyValuePointerStream;
import com.jivesoftware.os.amza.shared.wal.WALMergeKeyPointerStream;
import com.jivesoftware.os.amza.shared.wal.WALReader;
import com.jivesoftware.os.amza.shared.wal.WALRow;
import com.jivesoftware.os.amza.shared.wal.WALStorageDescriptor;
import com.jivesoftware.os.amza.shared.wal.WALTimestampId;
import com.jivesoftware.os.amza.shared.wal.WALTx;
import com.jivesoftware.os.amza.shared.wal.WALValue;
import com.jivesoftware.os.amza.shared.wal.WALWriter;
import com.jivesoftware.os.jive.utils.ordered.id.OrderIdProvider;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import com.jivesoftware.os.mlogger.core.ValueType;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.lang.mutable.MutableBoolean;
import org.apache.commons.lang.mutable.MutableInt;
import org.apache.commons.lang.mutable.MutableLong;

public class WALStorage implements RangeScannable<WALValue> {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();
    private static final int numTickleMeElmaphore = 1024; // TODO config

    private final VersionedPartitionName versionedPartitionName;
    private final OrderIdProvider orderIdProvider;
    private final PrimaryRowMarshaller<byte[]> rowMarshaller;
    private final HighwaterRowMarshaller<byte[]> highwaterRowMarshaller;
    private final WALTx walTx;
    private final AtomicInteger maxUpdatesBetweenCompactionHintMarker;
    private final AtomicInteger maxUpdatesBetweenIndexCommitMarker;
    private final MutableLong[] stripedKeyHighwaterTimestamps;

    private final AtomicReference<WALIndex> walIndex = new AtomicReference<>(null);
    private final Object oneWriterAtATimeLock = new Object();
    private final Object oneTransactionAtATimeLock = new Object();
    private final Semaphore tickleMeElmophore = new Semaphore(numTickleMeElmaphore, true);
    private final AtomicLong updateCount = new AtomicLong(0);
    private final AtomicLong newCount = new AtomicLong(0);
    private final AtomicLong clobberCount = new AtomicLong(0);
    private final AtomicLong indexUpdates = new AtomicLong(0);
    private final AtomicLong highestTxId = new AtomicLong(0);
    private final int tombstoneCompactionFactor;

    private final ThreadLocal<Integer> reentrant = new ReentrantTheadLocal();

    static class ReentrantTheadLocal extends ThreadLocal<Integer> {

        @Override
        protected Integer initialValue() {
            return 0;
        }
    }

    public WALStorage(VersionedPartitionName versionedPartitionName,
        OrderIdProvider orderIdProvider,
        PrimaryRowMarshaller<byte[]> rowMarshaller,
        HighwaterRowMarshaller<byte[]> highwaterRowMarshaller,
        WALTx walTx,
        int maxUpdatesBetweenCompactionHintMarker,
        int maxUpdatesBetweenIndexCommitMarker,
        int tombstoneCompactionFactor) {

        this.versionedPartitionName = versionedPartitionName;
        this.orderIdProvider = orderIdProvider;
        this.rowMarshaller = rowMarshaller;
        this.highwaterRowMarshaller = highwaterRowMarshaller;
        this.walTx = walTx;
        this.maxUpdatesBetweenCompactionHintMarker = new AtomicInteger(maxUpdatesBetweenCompactionHintMarker);
        this.maxUpdatesBetweenIndexCommitMarker = new AtomicInteger(maxUpdatesBetweenIndexCommitMarker);
        this.tombstoneCompactionFactor = tombstoneCompactionFactor;

        int numKeyHighwaterStripes = 1024; // TODO expose to config
        this.stripedKeyHighwaterTimestamps = new MutableLong[numKeyHighwaterStripes];
        for (int i = 0; i < stripedKeyHighwaterTimestamps.length; i++) {
            stripedKeyHighwaterTimestamps[i] = new MutableLong();
        }
    }

    private void acquireOne() {
        try {
            int enters = reentrant.get();
            if (enters == 0) {
                tickleMeElmophore.acquire();
            }
            reentrant.set(enters + 1);
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void releaseOne() {
        int enters = reentrant.get();
        if (enters - 1 == 0) {
            tickleMeElmophore.release();
        }
        reentrant.set(enters - 1);
    }

    private void acquireAll() throws InterruptedException {
        tickleMeElmophore.acquire(numTickleMeElmaphore);
    }

    private void releaseAll() {
        tickleMeElmophore.release(numTickleMeElmaphore);
    }

    public VersionedPartitionName getVersionedPartitionName() {
        return versionedPartitionName;
    }

    public boolean expunge() throws Exception {
        boolean expunged = true;
        acquireAll();
        try {
            expunged &= walTx.delete(false);
            WALIndex wali = walIndex.get();
            if (wali != null) {
                expunged &= wali.delete();
            }
        } finally {
            releaseAll();
        }
        return expunged;
    }

    public boolean compactableTombstone(long removeTombstonedOlderTimestampId, long ttlTimestampId) throws Exception {
        return (clobberCount.get() + 1) / (newCount.get() + 1) > tombstoneCompactionFactor;
    }

    public long compactTombstone(long removeTombstonedOlderThanTimestampId, long ttlTimestampId) throws Exception {
        final String metricPrefix = "partition>" + versionedPartitionName.getPartitionName().getName()
            + ">ring>" + versionedPartitionName.getPartitionName().getRingName() + ">";
        Optional<WALTx.Compacted> compact = walTx.compact(removeTombstonedOlderThanTimestampId, ttlTimestampId, walIndex.get());
        if (compact.isPresent()) {
            acquireAll();
            try {
                WALTx.CommittedCompacted compacted = compact.get().commit();
                walIndex.set(compacted.index);
                newCount.set(0);
                clobberCount.set(0);

                LOG.set(ValueType.COUNT, metricPrefix + "sizeBeforeCompaction", compacted.sizeBeforeCompaction);
                LOG.set(ValueType.COUNT, metricPrefix + "sizeAfterCompaction", compacted.sizeAfterCompaction);
                LOG.set(ValueType.COUNT, metricPrefix + "keeps", compacted.keyCount);
                LOG.set(ValueType.COUNT, metricPrefix + "removes", compacted.removeCount);
                LOG.set(ValueType.COUNT, metricPrefix + "tombstones", compacted.tombstoneCount);
                LOG.set(ValueType.COUNT, metricPrefix + "ttl", compacted.ttlCount);
                LOG.set(ValueType.COUNT, metricPrefix + "duration", compacted.duration);
                LOG.set(ValueType.COUNT, metricPrefix + "catchupKeeps", compacted.catchupKeys);
                LOG.set(ValueType.COUNT, metricPrefix + "catchupRemoves", compacted.catchupRemoves);
                LOG.set(ValueType.COUNT, metricPrefix + "catchupTombstones", compacted.catchupTombstones);
                LOG.set(ValueType.COUNT, metricPrefix + "catchupTtl", compacted.catchupTTL);
                LOG.set(ValueType.COUNT, metricPrefix + "catchupDuration", compacted.catchupDuration);
                LOG.inc(metricPrefix + "compacted");

                return compacted.sizeAfterCompaction;
            } finally {
                releaseAll();
            }

        } else {
            LOG.inc(metricPrefix + "checks");
        }
        return -1;
    }

    public void load() throws Exception {
        acquireAll();
        try {

            walIndex.compareAndSet(null, walTx.load(versionedPartitionName));

            final MutableLong lastTxId = new MutableLong(0);
            final AtomicLong rowsVisited = new AtomicLong(maxUpdatesBetweenCompactionHintMarker.get());
            final AtomicBoolean gotCompactionHints = new AtomicBoolean(false);
            final AtomicBoolean gotHighestTxId = new AtomicBoolean(false);

            walTx.read((WALReader rowReader) -> {
                rowReader.reverseScan((rowPointer, rowTxId, rowType, row) -> {
                    if (rowTxId > lastTxId.longValue()) {
                        lastTxId.setValue(rowTxId);
                        gotHighestTxId.set(true);
                    }
                    if (!gotCompactionHints.get() && rowType == RowType.system) {
                        ByteBuffer buf = ByteBuffer.wrap(row);
                        byte[] keyBytes = new byte[8];
                        buf.get(keyBytes);
                        long key = UIO.bytesLong(keyBytes);
                        if (key == RowType.COMPACTION_HINTS_KEY) {
                            byte[] newCountBytes = new byte[8];
                            byte[] clobberCountBytes = new byte[8];
                            buf.get(newCountBytes);
                            buf.get(clobberCountBytes);
                            newCount.set(UIO.bytesLong(newCountBytes));
                            clobberCount.set(UIO.bytesLong(clobberCountBytes));
                            gotCompactionHints.set(true);
                        }
                    }
                    return !gotHighestTxId.get() || (rowsVisited.decrementAndGet() >= 0 && !gotCompactionHints.get());
                });
                return null;
            });
            highestTxId.set(lastTxId.longValue());

            walTx.write((WALWriter writer) -> {
                writeCompactionHintMarker(writer);
                return null;
            });

        } finally {
            releaseAll();
        }
    }

    public void flush(boolean fsync) throws Exception {
        walTx.flush(fsync);
    }

    public boolean delete(boolean ifEmpty) throws Exception {
        throw new UnsupportedOperationException("Not yet!");
    }

    private void writeCompactionHintMarker(WALWriter rowWriter) throws Exception {
        synchronized (oneTransactionAtATimeLock) {
            rowWriter.writeSystem(UIO.longsBytes(new long[]{
                RowType.COMPACTION_HINTS_KEY,
                newCount.get(),
                clobberCount.get()
            }));
            updateCount.set(0);
        }
    }

    private void writeIndexCommitMarker(WALWriter rowWriter, long indexCommitedUpToTxId) throws Exception {
        synchronized (oneTransactionAtATimeLock) {
            rowWriter.writeSystem(UIO.longsBytes(new long[]{
                RowType.COMMIT_KEY,
                indexCommitedUpToTxId}));
        }
    }

    public void writeHighwaterMarker(WALWriter rowWriter, WALHighwater highwater) throws Exception {
        synchronized (oneTransactionAtATimeLock) {
            rowWriter.writeHighwater(highwaterRowMarshaller.toBytes(highwater));
        }
    }

    /*TODO if we care, persist stripedKeyHighwaterTimestamps periodically as a cold start optimization for getLargestTimestampForKeyStripe()
     private void writeStripedTimestamp(WALWriter rowWriter) throws Exception {
     synchronized (oneTransactionAtATimeLock) {
     rowWriter.write(
     Collections.singletonList(-1L),
     Collections.singletonList(WALWriter.SYSTEM_VERSION_1),
     Collections.singletonList(UIO.longsBytes(new long[]{
     WALWriter.STRIPE_TIME_MARKER,
     stripedKeyHighwaterTimestamps})));
     }
     }
     */
    public RowsChanged update(boolean useUpdateTxId,
        Commitable<WALValue> updates) throws Exception {

        final AtomicLong oldestAppliedTimestamp = new AtomicLong(Long.MAX_VALUE);
        final Table<Long, WALKey, WALValue> apply = TreeBasedTable.create();
        final Map<WALKey, WALTimestampId> removes = new HashMap<>();
        final Map<WALKey, WALTimestampId> clobbers = new HashMap<>();

        final List<Long> transactionIds = useUpdateTxId ? new ArrayList<>() : null;
        final List<WALKey> keys = new ArrayList<>();
        final List<WALValue> values = new ArrayList<>();
        final Map<Long, WALHighwater> txIdToHighwater = new HashMap<>();

        updates.commitable((highwater) -> {
            if (useUpdateTxId) {
                txIdToHighwater.put(transactionIds.get(transactionIds.size() - 1), highwater);
            } else {
                txIdToHighwater.put(-1L, highwater);
            }
        }, (transactionId, key, update) -> {
            if (useUpdateTxId) {
                transactionIds.add(transactionId);
            }
            keys.add(key);
            values.add(update);
            return true;
        });

        acquireOne();
        try {
            WALIndex wali = walIndex.get();
            RowsChanged rowsChanged;
            final MutableBoolean flushCompactionHint = new MutableBoolean(false);
            final MutableLong indexCommitedUpToTxId = new MutableLong();

            MutableInt i = new MutableInt(0);
            wali.getPointers((KeyValueStream stream) -> {
                for (int k = 0; k < keys.size(); k++) {
                    stream.stream(keys.get(k), values.get(k));
                }
            }, (WALKey key, WALValue value, long timestamp, boolean tombstoned, long fp) -> {
                long txId = useUpdateTxId ? transactionIds.get(i.intValue()) : -1L;
                if (fp == -1) {
                    apply.put(txId, key, value);
                    if (oldestAppliedTimestamp.get() > value.getTimestampId()) {
                        oldestAppliedTimestamp.set(value.getTimestampId());
                    }
                } else if (timestamp < value.getTimestampId()) {
                    apply.put(txId, key, value);
                    if (oldestAppliedTimestamp.get() > value.getTimestampId()) {
                        oldestAppliedTimestamp.set(value.getTimestampId());
                    }
                    //TODO do we REALLY need the old value? WALValue value = hydrateRowIndexValue(currentPointer);
                    WALTimestampId currentTimestampId = new WALTimestampId(timestamp, tombstoned);
                    clobbers.put(key, currentTimestampId);
                    if (value.getTombstoned() && !tombstoned) {
                        removes.put(key, currentTimestampId);
                    }
                }
                i.increment();
                return true;
            });

            NavigableMap<WALKey, Long> keyToRowPointer = new TreeMap<>();

            if (apply.isEmpty()) {
                rowsChanged = new RowsChanged(versionedPartitionName, oldestAppliedTimestamp.get(), apply, removes, clobbers, -1);
            } else {
                walTx.write((WALWriter rowWriter) -> {
                    List<Long> rawTransactionIds = useUpdateTxId ? new ArrayList<>() : null;
                    List<WALKey> rawKeys = new ArrayList<>();
                    List<byte[]> rawRows = new ArrayList<>();

                    for (Long txId : apply.rowKeySet()) {
                        for (Entry<WALKey, WALValue> row : apply.row(txId).entrySet()) {
                            if (useUpdateTxId) {
                                rawTransactionIds.add(txId);
                            }
                            rawKeys.add(row.getKey());
                            rawRows.add(rowMarshaller.toRow(row.getKey(), row.getValue()));
                        }

                        WALHighwater highwater = txIdToHighwater.get(txId);
                        if (highwater != null) {
                            flush(useUpdateTxId,
                                indexCommitedUpToTxId,
                                rawTransactionIds, rawKeys,
                                rawRows,
                                rowWriter,
                                highwater,
                                keyToRowPointer,
                                flushCompactionHint);

                            if (useUpdateTxId) {
                                rawTransactionIds.clear();
                            }
                            rawKeys.clear();
                            rawRows.clear();
                        }
                    }
                    if (!rawKeys.isEmpty()) {
                        flush(useUpdateTxId,
                            indexCommitedUpToTxId,
                            rawTransactionIds, rawKeys,
                            rawRows,
                            rowWriter,
                            null,
                            keyToRowPointer,
                            flushCompactionHint);
                    }

                    return null;
                });
                synchronized (oneWriterAtATimeLock) {
                    Iterator<Table.Cell<Long, WALKey, WALValue>> iter = apply.cellSet().iterator();

                    wali.merge((WALKeyPointerStream stream) -> {
                        while (iter.hasNext()) {
                            Table.Cell<Long, WALKey, WALValue> cell = iter.next();
                            WALKey key = cell.getColumnKey();
                            WALValue value = cell.getValue();
                            long rowPointer = keyToRowPointer.get(key);
                            stream.stream(key, value.getTimestampId(), value.getTombstoned(), rowPointer);

                            int highwaterTimestampIndex = (int) Math.abs((long) key.hashCode()) % stripedKeyHighwaterTimestamps.length;
                            stripedKeyHighwaterTimestamps[highwaterTimestampIndex].setValue(Math.max(
                                stripedKeyHighwaterTimestamps[highwaterTimestampIndex].longValue(),
                                value.getTimestampId()));
                        }
                    }, (byte mode, WALKey key, long timestamp, boolean tombstoned, long fp) -> {
                        if (mode == WALMergeKeyPointerStream.added) {
                            newCount.incrementAndGet();
                        } else if (mode == WALMergeKeyPointerStream.clobbered) {
                            clobberCount.incrementAndGet();
                        } else {
                            iter.remove();
                        }
                        return true;
                    });

                    rowsChanged = new RowsChanged(versionedPartitionName, oldestAppliedTimestamp.get(), apply, removes, clobbers,
                        indexCommitedUpToTxId.longValue());
                }
            }

            final boolean commitAndWriteMarker = apply.size() > 0 && indexUpdates.addAndGet(apply.size()) > maxUpdatesBetweenIndexCommitMarker.get();
            if (commitAndWriteMarker) {
                wali.commit();
                indexUpdates.set(0);
            }
            if (commitAndWriteMarker || flushCompactionHint.isTrue()) {
                walTx.write((WALWriter writer) -> {
                    if (flushCompactionHint.isTrue()) {
                        writeCompactionHintMarker(writer);
                    }
                    if (commitAndWriteMarker) {
                        writeIndexCommitMarker(writer, indexCommitedUpToTxId.longValue());
                    }
                    return null;
                });
            }
            return rowsChanged;
        } finally {
            releaseOne();
        }
    }

    private void flush(boolean useUpdateTxId,
        MutableLong indexCommitedUpToTxId,
        List<Long> rawTransactionIds,
        List<WALKey> rawKeys,
        List<byte[]> rawRows,
        WALWriter rowWriter,
        WALHighwater highwater,
        NavigableMap<WALKey, Long> keyToRowPointer,
        MutableBoolean flushCompactionHint) throws Exception {

        long[] rowPointers;
        synchronized (oneTransactionAtATimeLock) {
            if (useUpdateTxId) {
                indexCommitedUpToTxId.setValue(rawTransactionIds.get(rawTransactionIds.size() - 1));
            } else {
                long transactionId = orderIdProvider.nextId();
                rawTransactionIds = Collections.nCopies(rawRows.size(), transactionId);
                indexCommitedUpToTxId.setValue(transactionId);
            }
            rowPointers = rowWriter.writePrimary(rawTransactionIds, rawRows);
            if (highwater != null) {
                writeHighwaterMarker(rowWriter, highwater);
            }
            highestTxId.set(indexCommitedUpToTxId.longValue());
        }
        for (int i = 0; i < rowPointers.length; i++) {
            keyToRowPointer.put(rawKeys.get(i), rowPointers[i]);
        }
        if (updateCount.addAndGet(rawKeys.size()) > maxUpdatesBetweenCompactionHintMarker.get()) {
            flushCompactionHint.setValue(true);
        }
    }

    @Override
    public void rowScan(Scan<WALValue> scan) throws Exception {
        acquireOne();
        try {
            WALIndex wali = walIndex.get();
            wali.rowScan((WALKey key, long timestamp, boolean tombstoned, long fp) -> {
                return scan.row(-1, key, hydrateRowIndexValue(timestamp, tombstoned, fp));
            });
        } finally {
            releaseOne();
        }
    }

    @Override
    public void rangeScan(WALKey from, WALKey to, Scan<WALValue> scan) throws Exception {
        acquireOne();
        try {
            WALIndex wali = walIndex.get();
            wali.rangeScan(from, to, (WALKey key, long timestamp, boolean tombstoned, long fp) -> {
                return scan.row(-1, key, hydrateRowIndexValue(timestamp, tombstoned, fp));
            });
        } finally {
            releaseOne();
        }
    }

    public WALValue get(WALKey key) throws Exception {
        acquireOne();
        try {
            WALValue[] values = new WALValue[1];
            walIndex.get().getPointer(key, (WALKey key1, long timestamp, boolean tombstoned, long fp) -> {
                if (fp != -1 && !tombstoned) {
                    values[0] = hydrateRowIndexValue(timestamp, tombstoned, fp);
                }
                return true;
            });
            return values[0];
        } finally {
            releaseOne();
        }
    }

    public WALValue[] get(WALKey[] keys) throws Exception {
        acquireOne();
        try {
            WALValue[] values = new WALValue[keys.length];
            MutableInt i = new MutableInt();
            walIndex.get().getPointers((KeyValueStream stream) -> {
                for (WALKey key : keys) {
                    stream.stream(key, null);
                }
            }, (WALKey key, WALValue value, long timestamp, boolean tombstoned, long fp) -> {
                if (fp != -1 && !tombstoned) {
                    values[i.intValue()] = hydrateRowIndexValue(timestamp, tombstoned, fp);
                }
                i.increment();
                return true;
            });
            return values;
        } finally {
            releaseOne();
        }
    }

    public void streamWALPointers(KeyValues keyValues, WALKeyValuePointerStream stream) throws Exception {
        acquireOne();
        try {
            walIndex.get().getPointers((KeyValueStream indexStream) -> {
                keyValues.consume((WALKey key, WALValue value) -> {
                    if (value.getTimestampId() > getLargestTimestampForKeyStripe(key)) {
                        return stream.stream(key, value, -1, false, -1);
                    } else {
                        return indexStream.stream(key, value);
                    }
                });
            }, (WALKey key, WALValue value, long timestamp, boolean tombstoned, long fp) -> {
                return stream.stream(key, value, timestamp, tombstoned, fp);
            });

        } finally {
            releaseOne();
        }
    }

    public boolean containsKey(WALKey key) throws Exception {
        return containsKey(Collections.singletonList(key)).get(0);
    }

    public List<Boolean> containsKey(List<WALKey> keys) throws Exception {
        acquireOne();
        try {
            return walIndex.get().containsKey(keys);
        } finally {
            releaseOne();
        }
    }

    private long getLargestTimestampForKeyStripe(WALKey key) {
        int highwaterTimestampIndex = (int) Math.abs((long) key.hashCode()) % stripedKeyHighwaterTimestamps.length;
        return stripedKeyHighwaterTimestamps[highwaterTimestampIndex].longValue();
    }

    private WALValue hydrateRowIndexValue(long timestamp, boolean tombstone, long indexFP) {
        try {
            byte[] row = walTx.read((WALReader rowReader) -> rowReader.read(indexFP));
            byte[] value = rowMarshaller.valueFromRow(row);
            return new WALValue(value, timestamp, tombstone);
        } catch (Exception x) {
            String base64;
            try {
                base64 = versionedPartitionName.toBase64();
            } catch (IOException e) {
                base64 = e.getMessage();
            }

            long length;
            try {
                length = walTx.length();
            } catch (Exception e) {
                length = -1;
            }

            throw new RuntimeException("Failed to hydrate for " + versionedPartitionName + " base64=" + base64 + " size=" + length + " fp=" + indexFP, x);
        }
    }

    public boolean takeRowUpdatesSince(final long sinceTransactionId, final RowStream rowStream) throws Exception {
        acquireOne();
        try {
            return walTx.readFromTransactionId(sinceTransactionId, (long offset, WALReader rowReader) -> rowReader.scan(offset, false,
                (long rowPointer, long rowTxId, RowType rowType, byte[] row) -> {
                    if (rowType != RowType.system && rowTxId > sinceTransactionId) {
                        return rowStream.row(rowPointer, rowTxId, rowType, row);
                    }
                    return true;
                }));
        } finally {
            releaseOne();
        }
    }

    /**
     * @param sinceTransactionId
     * @param highwaters         Nullable
     * @param scan
     * @return
     * @throws Exception
     */
    public boolean takeFromTransactionId(final long sinceTransactionId, Highwaters highwaters, Scan<WALValue> scan) throws Exception {
        acquireOne();
        try {
            return walTx.readFromTransactionId(sinceTransactionId, (long offset, WALReader rowReader) -> rowReader.scan(offset, false,
                (long rowPointer, long rowTxId, RowType rowType, byte[] row) -> {
                    if (rowType == RowType.highwater && highwaters != null) {
                        WALHighwater highwater = highwaterRowMarshaller.fromBytes(row);
                        highwaters.highwater(highwater);
                    } else if (rowType == RowType.primary && rowTxId > sinceTransactionId) {
                        WALRow walRow = rowMarshaller.fromRow(row);
                        return scan.row(rowTxId, walRow.key, walRow.value);
                    }
                    return true;
                }));
        } finally {
            releaseOne();
        }
    }

    public boolean takeRowsFromTransactionId(long sinceTransactionId, RowStream rowStream) throws Exception {
        acquireOne();
        try {
            return walTx.readFromTransactionId(sinceTransactionId, (long offset, WALReader reader) -> {
                return reader.scan(offset, false, rowStream);
            });
        } finally {
            releaseOne();
        }
    }

    public void updatedStorageDescriptor(WALStorageDescriptor walStorageDescriptor) throws Exception {
        maxUpdatesBetweenCompactionHintMarker.set(walStorageDescriptor.maxUpdatesBetweenCompactionHintMarker);
        maxUpdatesBetweenIndexCommitMarker.set(walStorageDescriptor.maxUpdatesBetweenIndexCommitMarker);
        acquireOne();
        try {
            WALIndex wali = walIndex.get();
            wali.updatedDescriptors(walStorageDescriptor.primaryIndexDescriptor, walStorageDescriptor.secondaryIndexDescriptors);
        } finally {
            releaseOne();
        }
    }

    public long count() throws Exception {
        acquireOne();
        try {
            WALIndex wali = this.walIndex.get();
            return wali.size();
        } finally {
            releaseOne();
        }
    }

    public long highestTxId() {
        return highestTxId.get();
    }
}
