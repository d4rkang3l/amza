package com.jivesoftware.os.amza.lab.pointers;

import com.jivesoftware.os.amza.api.CompareTimestampVersions;
import com.jivesoftware.os.amza.api.filer.UIO;
import com.jivesoftware.os.amza.api.partition.VersionedPartitionName;
import com.jivesoftware.os.amza.api.scan.CompactionWALIndex;
import com.jivesoftware.os.amza.api.stream.KeyContainedStream;
import com.jivesoftware.os.amza.api.stream.KeyValuePointerStream;
import com.jivesoftware.os.amza.api.stream.KeyValues;
import com.jivesoftware.os.amza.api.stream.MergeTxKeyPointerStream;
import com.jivesoftware.os.amza.api.stream.TxFpStream;
import com.jivesoftware.os.amza.api.stream.TxKeyPointers;
import com.jivesoftware.os.amza.api.stream.UnprefixedWALKeys;
import com.jivesoftware.os.amza.api.stream.WALKeyPointerStream;
import com.jivesoftware.os.amza.api.stream.WALKeyPointers;
import com.jivesoftware.os.amza.api.stream.WALMergeKeyPointerStream;
import com.jivesoftware.os.amza.api.wal.WALIndex;
import com.jivesoftware.os.amza.api.wal.WALKey;
import com.jivesoftware.os.amza.lab.pointers.LABPointerIndexWALIndexName.Type;
import com.jivesoftware.os.lab.LABEnvironment;
import com.jivesoftware.os.lab.api.MemoryRawEntryFormat;
import com.jivesoftware.os.lab.api.NoOpFormatTransformerProvider;
import com.jivesoftware.os.lab.api.ValueIndex;
import com.jivesoftware.os.lab.api.ValueIndexConfig;
import com.jivesoftware.os.lab.api.rawhide.LABRawhide;
import com.jivesoftware.os.lab.guts.IndexUtil;
import com.jivesoftware.os.lab.guts.LABHashIndexType;
import com.jivesoftware.os.lab.io.BolBuffer;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author jonathan.colt
 */
public class LABPointerIndexWALIndex implements WALIndex {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private static final int numPermits = 1024;

    private final String providerName;
    private final int maxValueSizeInIndex;
    private final VersionedPartitionName versionedPartitionName;
    private final LABPointerIndexWALIndexName name;
    private final LABPointerIndexConfig config;
    private final LABEnvironment[] environments;
    private volatile int currentStripe;
    private ValueIndex<byte[]> primaryDb;
    private ValueIndex<byte[]> prefixDb;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Semaphore lock = new Semaphore(numPermits, true);
    private final AtomicLong count = new AtomicLong(-1);
    private final AtomicInteger commits = new AtomicInteger(0);
    private final AtomicReference<WALIndex> compactingTo = new AtomicReference<>();

    public LABPointerIndexWALIndex(String providerName,
        int maxValueSizeInIndex,
        VersionedPartitionName versionedPartitionName,
        LABEnvironment[] environments,
        int currentStripe,
        LABPointerIndexWALIndexName name,
        LABPointerIndexConfig config) throws Exception {
        this.providerName = providerName;
        this.maxValueSizeInIndex = maxValueSizeInIndex;
        this.versionedPartitionName = versionedPartitionName;
        this.name = name;
        this.config = config;
        this.environments = environments;
        this.currentStripe = currentStripe;
    }

    private void init() throws Exception {
        if (primaryDb != null) {
            return;
        }
        synchronized (closed) {
            if (primaryDb != null || closed.get()) {
                return;
            }
            primaryDb = environments[currentStripe].open(new ValueIndexConfig(name.getPrimaryName(),
                config.getEntriesBetweenLeaps(),
                config.getMaxHeapPressureInBytes(),
                config.getSplitWhenKeysTotalExceedsNBytes(),
                config.getSplitWhenValuesTotalExceedsNBytes(),
                config.getSplitWhenValuesAndKeysTotalExceedsNBytes(),
                NoOpFormatTransformerProvider.NAME,
                LABRawhide.NAME,
                MemoryRawEntryFormat.NAME,
                -1,
                LABHashIndexType.valueOf(config.getHashIndexType()),
                config.getHashIndexLoadFactor(),
                config.getHashIndexEnabled()));
            prefixDb = environments[currentStripe].open(new ValueIndexConfig(name.getPrefixName(),
                config.getEntriesBetweenLeaps(),
                config.getMaxHeapPressureInBytes(),
                config.getSplitWhenKeysTotalExceedsNBytes(),
                config.getSplitWhenValuesTotalExceedsNBytes(),
                config.getSplitWhenValuesAndKeysTotalExceedsNBytes(),
                NoOpFormatTransformerProvider.NAME,
                LABRawhide.NAME,
                MemoryRawEntryFormat.NAME,
                -1,
                LABHashIndexType.valueOf(config.getHashIndexType()),
                config.getHashIndexLoadFactor(),
                config.getHashIndexEnabled()));
        }
    }

    @Override
    public int getStripe() {
        return currentStripe;
    }

    @Override
    public String getProviderName() {
        return providerName;
    }

    public VersionedPartitionName getVersionedPartitionName() {
        return versionedPartitionName;
    }

    @Override
    public void delete() throws Exception {
        close();
        lock.acquire(numPermits);
        try {
            synchronized (compactingTo) {
                WALIndex wali = compactingTo.get();
                if (wali != null) {
                    wali.close();
                }
                for (Type type : Type.values()) {
                    removeDatabase(currentStripe, type);
                }
            }
        } finally {
            lock.release(numPermits);
        }
    }

    @Override
    public boolean merge(TxKeyPointers pointers, MergeTxKeyPointerStream stream) throws Exception {
        init();
        try {
            lock.acquire();
        } catch (InterruptedException ie) {
            throw new RuntimeException(ie);
        }
        try {
            byte[] mode = new byte[1];
            byte[] txFpBytes = new byte[16];
            BolBuffer entryBuffer = new BolBuffer();
            BolBuffer keyBuffer = new BolBuffer();
            return pointers.consume((txId, prefix, key, value, timestamp, tombstoned, version, fp) -> {
                byte[] pk = WALKey.compose(prefix, key);
                return primaryDb.get(
                    (stream1) -> stream1.key(0, pk, 0, pk.length),
                    (index1, key1, timestamp1, tombstoned1, version1, payload) -> {
                        if (payload != null) {
                            int c = CompareTimestampVersions.compare(timestamp1, version1, timestamp, version);
                            mode[0] = (c < 0) ? WALMergeKeyPointerStream.clobbered : WALMergeKeyPointerStream.ignored;
                        } else {
                            mode[0] = WALMergeKeyPointerStream.added;
                        }

                        if (mode[0] != WALMergeKeyPointerStream.ignored) {
                            byte[] mergePayload = toPayload(fp, value);
                            primaryDb.append((pointerStream) -> {
                                return pointerStream.stream(-1, pk, timestamp, tombstoned, version, mergePayload);
                            }, true, entryBuffer, keyBuffer);

                            if (prefix != null) {
                                UIO.longBytes(txId, txFpBytes, 0);
                                UIO.longBytes(fp, txFpBytes, 8);
                                byte[] prefixTxFp = WALKey.compose(prefix, txFpBytes);
                                prefixDb.append((pointerStream) -> {
                                    return pointerStream.stream(-1, prefixTxFp, timestamp, tombstoned, version, mergePayload);
                                }, true, entryBuffer, keyBuffer);
                            }
                        }
                        if (stream != null) {
                            return stream.stream(mode[0], txId, prefix, key, timestamp, tombstoned, version, fp);
                        } else {
                            return true;
                        }
                    },
                    true);

            });
        } finally {
            lock.release();
        }
    }

    private static byte PAYLOAD_NULL = -1;
    private static byte PAYLOAD_NONNULL = -2;

    private byte[] toPayload(long fp, byte[] value) {
        if (fp < 0) {
            throw new IllegalArgumentException("Negative fp " + fp);
        }
        int valueLength = (value == null) ? 0 : value.length;
        if (maxValueSizeInIndex >= 0 && maxValueSizeInIndex >= valueLength) {
            // leverage the fact that fp cannot be negative by using a negative leading byte
            byte[] payload = new byte[1 + (value == null ? 0 : value.length)];
            payload[0] = (value == null) ? PAYLOAD_NULL : PAYLOAD_NONNULL;
            if (value != null && value.length > 0) {
                System.arraycopy(value, 0, payload, 1, value.length);
            }
            return payload;
        } else {
            return UIO.longBytes(fp);
        }
    }

    private boolean fromPayload(long txId,
        long fp,
        byte[] payload,
        TxFpStream txFpStream,
        boolean hydrateValues) throws Exception {
        if (payload != null && payload[0] < 0) {
            if (payload[0] == PAYLOAD_NULL) {
                return txFpStream.stream(txId, fp, true, null);
            } else if (payload[0] == PAYLOAD_NONNULL) {
                byte[] value = new byte[payload.length - 1];
                System.arraycopy(payload, 1, value, 0, value.length);
                return txFpStream.stream(txId, fp, true, value);
            }
        }
        //TODO split hydrateValues=false into its own method
        return txFpStream.stream(txId, fp, !hydrateValues, null);
    }

    private boolean fromPayload(byte[] prefix,
        byte[] key,
        long timestamp,
        boolean tombstoned,
        long version,
        byte[] payload,
        WALKeyPointerStream stream,
        boolean hydrateValues) throws Exception {
        if (payload != null) {
            if (payload[0] == PAYLOAD_NULL) {
                return stream.stream(prefix, key, timestamp, tombstoned, version, -1, true, null);
            } else if (payload[0] == PAYLOAD_NONNULL) {
                byte[] value = new byte[payload.length - 1];
                System.arraycopy(payload, 1, value, 0, value.length);
                return stream.stream(prefix, key, timestamp, tombstoned, version, -1, true, value);
            } else {
                long fp = UIO.bytesLong(payload);
                return stream.stream(prefix, key, timestamp, tombstoned, version, fp, false, null);
            }
        }
        //TODO split hydrateValues=false into its own method
        return stream.stream(prefix, key, timestamp, tombstoned, version, -1, !hydrateValues, null);
    }

    private boolean fromPayload(byte[] prefix,
        byte[] key,
        byte[] value,
        long valueTimestamp,
        boolean valueTombstoned,
        long valueVersion,
        long timestamp,
        boolean tombstoned,
        long version,
        byte[] payload,
        KeyValuePointerStream stream,
        boolean hydrateValues) throws Exception {
        if (payload != null) {
            if (payload[0] == PAYLOAD_NULL) {
                return stream.stream(prefix, key, value, valueTimestamp, valueTombstoned, valueVersion, timestamp, tombstoned, version, -1, true, null);
            } else if (payload[0] == PAYLOAD_NONNULL) {
                byte[] pointerValue = new byte[payload.length - 1];
                System.arraycopy(payload, 1, pointerValue, 0, pointerValue.length);
                return stream.stream(prefix, key, value, valueTimestamp, valueTombstoned, valueVersion, timestamp, tombstoned, version, -1, true, pointerValue);
            } else {
                long fp = UIO.bytesLong(payload);
                return stream.stream(prefix, key, value, valueTimestamp, valueTombstoned, valueVersion, timestamp, tombstoned, version, fp, false, null);
            }
        }
        //TODO split hydrateValues=false into its own method
        return stream.stream(prefix, key, value, valueTimestamp, valueTombstoned, valueVersion, timestamp, tombstoned, version, -1, !hydrateValues, null);
    }

    @Override
    public boolean takePrefixUpdatesSince(byte[] prefix, long sinceTransactionId, TxFpStream txFpStream) throws Exception {
        init();
        lock.acquire();
        try {
            byte[] fromFpPk = WALKey.compose(prefix, new byte[0]);
            byte[] toFpPk = WALKey.prefixUpperExclusive(fromFpPk);
            BolBuffer bbToFpPk = new BolBuffer(toFpPk);
            return prefixDb.rangeScan(fromFpPk, toFpPk, (index, rawKey, timestamp, tombstoned, version, payload) -> {
                if (IndexUtil.compare(rawKey, bbToFpPk) >= 0) {
                    return false;
                }
                BolBuffer key = new BolBuffer(WALKey.rawKeyKey(rawKey.copy()));
                long takeTxId = key.getLong(0);
                long takeFp = key.getLong(8);
                return fromPayload(takeTxId, takeFp, payload == null ? null : payload.copy(), txFpStream, true);
            }, true);
        } finally {
            lock.release();
        }
    }

    @Override
    public boolean getPointer(byte[] prefix, byte[] key, WALKeyPointerStream stream) throws Exception {
        init();
        try {
            lock.acquire();
        } catch (InterruptedException ie) {
            throw new RuntimeException(ie);
        }
        try {
            return getPointerInternal(prefix, key, stream);
        } finally {
            lock.release();
        }
    }

    private boolean getPointerInternal(byte[] prefix, byte[] key, WALKeyPointerStream stream) throws Exception {
        byte[] pk = WALKey.compose(prefix, key);
        return primaryDb.get((keyStream) -> keyStream.key(0, pk, 0, pk.length),
            (index, rawKey, timestamp, tombstoned, version, payload) -> {
                return fromPayload(prefix, key, timestamp, tombstoned, version, payload == null ? null : payload.copy(), stream, true);
            },
            true);
    }

    @Override
    public boolean getPointers(byte[] prefix, UnprefixedWALKeys keys, WALKeyPointerStream stream) throws Exception {
        init();
        lock.acquire();
        try {
            return keys.consume((key) -> {
                byte[] pk = WALKey.compose(prefix, key);
                return primaryDb.get((keyStream) -> keyStream.key(0, pk, 0, pk.length),
                    (index, rawKey, timestamp, tombstoned, version, payload) -> {
                        return fromPayload(prefix, key, timestamp, tombstoned, version, payload == null ? null : payload.copy(), stream, true);
                    },
                    true);
            });
        } finally {
            lock.release();
        }
    }

    @Override
    public boolean getPointers(KeyValues keyValues, KeyValuePointerStream stream) throws Exception {
        init();
        lock.acquire();
        try {
            return keyValues.consume((prefix, key, value, valueTimestamp, valueTombstoned, valueVersion) -> {
                byte[] pk = WALKey.compose(prefix, key);
                return primaryDb.get((keyStream) -> keyStream.key(0, pk, 0, pk.length),
                    (index, rawKey, timestamp, tombstoned, version, payload) -> {
                        return fromPayload(prefix,
                            key,
                            value,
                            valueTimestamp,
                            valueTombstoned,
                            valueVersion,
                            timestamp,
                            tombstoned,
                            version,
                            payload == null ? null : payload.copy(),
                            stream,
                            true);
                    },
                    true);
            });
        } finally {
            lock.release();
        }
    }

    @Override
    public boolean containsKeys(byte[] prefix, UnprefixedWALKeys keys, KeyContainedStream stream) throws Exception {
        init();
        lock.acquire();
        try {
            return keys.consume((key) -> getPointerInternal(prefix, key,
                (_prefix, _key, timestamp, tombstoned, version, fp, hasValue, value) -> {
                    boolean contained = (fp != -1 || hasValue) && !tombstoned;
                    stream.stream(prefix, key, contained, timestamp, version);
                    return true;
                }));
        } finally {
            lock.release();
        }
    }

    @Override
    public boolean exists() throws Exception {
        lock.acquire();
        try {
            return environments[currentStripe].exists(name.getPrimaryName());
        } finally {
            lock.release();
        }
    }

    @Override
    public long deltaCount(WALKeyPointers keyPointers) throws Exception {
        init();
        lock.acquire();
        try {
            long[] delta = new long[1];
            boolean completed = keyPointers.consume(
                (prefix, key, requestTimestamp, requestTombstoned, requestVersion, requestFp, requestIndexValue, requestValue)
                -> getPointerInternal(prefix, key, (_prefix, _key, indexTimestamp, indexTombstoned, indexVersion, indexFp, indexHasValue, indexValue) -> {
                    // indexFp, indexHasValue, backingHasValue, indexTombstoned, requestTombstoned, delta
                    // -1       false          false            false            false              1
                    // -1       true           true             false            false              0
                    // -1       false          false            false            true               0
                    // -1       true           true             false            true               -1
                    //  1       false          true             false            false              0
                    //  1       false          true             false            true               -1
                    //  1       false          true             true             false              1
                    //  1       false          true             true             true               0
                    boolean backingHasValue = (indexFp != -1 || indexHasValue);
                    if (!requestTombstoned && (!backingHasValue && !indexTombstoned || backingHasValue && indexTombstoned)) {
                        delta[0]++;
                    } else if (backingHasValue && !indexTombstoned && requestTombstoned) {
                        delta[0]--;
                    }
                    return true;
                }));
            if (!completed) {
                return -1;
            }
            return delta[0];
        } finally {
            lock.release();
        }
    }

    @Override
    public void commit(boolean fsync) throws Exception {
        init();
        lock.acquire();
        try {
            // TODO is this the right thing to do?
            if (primaryDb != null) {
                primaryDb.commit(fsync, true);
            }
            if (prefixDb != null) {
                prefixDb.commit(fsync, true);
            }

            synchronized (commits) {
                count.set(-1);
                commits.incrementAndGet();
            }
        } finally {
            lock.release();
        }
    }

    @Override
    public void close() throws Exception {
        lock.acquire(numPermits);
        try {
            synchronized (closed) {
                if (primaryDb != null) {
                    primaryDb.close(true, true);
                    primaryDb = null;
                }
                if (prefixDb != null) {
                    prefixDb.close(true, true);
                    prefixDb = null;
                }
                closed.set(true);
            }
        } finally {
            lock.release(numPermits);
        }
    }

    @Override
    public boolean rowScan(final WALKeyPointerStream stream, boolean hydrateValues) throws Exception {
        init();
        lock.acquire();
        try {
            return primaryDb.rowScan(
                (index, rawKey, timestamp, tombstoned, version, payload) -> {
                    byte[] rawKeyBytes = rawKey.copy();
                    return fromPayload(WALKey.rawKeyPrefix(rawKeyBytes),
                        WALKey.rawKeyKey(rawKeyBytes),
                        timestamp,
                        tombstoned,
                        version,
                        payload == null ? null : payload.copy(),
                        stream,
                        hydrateValues);
                },
                hydrateValues);
        } finally {
            lock.release();
        }
    }

    @Override
    public boolean rangeScan(byte[] fromPrefix,
        byte[] fromKey,
        byte[] toPrefix,
        byte[] toKey,
        WALKeyPointerStream stream,
        boolean hydrateValues) throws Exception {

        init();
        lock.acquire();
        try {
            byte[] fromPk = fromKey != null ? WALKey.compose(fromPrefix, fromKey) : null;
            byte[] toPk = toKey != null ? WALKey.compose(toPrefix, toKey) : null;
            return primaryDb.rangeScan(fromPk,
                toPk,
                (index, rawKey, timestamp, tombstoned, version, payload) -> {
                    byte[] rawKeyBytes = rawKey.copy();
                    return fromPayload(WALKey.rawKeyPrefix(rawKeyBytes),
                        WALKey.rawKeyKey(rawKeyBytes),
                        timestamp,
                        tombstoned,
                        version,
                        payload == null ? null : payload.copy(),
                        stream,
                        hydrateValues);
                },
                hydrateValues);
        } finally {
            lock.release();
        }
    }

    @Override
    public CompactionWALIndex startCompaction(boolean hasActive, int compactionStripe) throws Exception {

        init();
        synchronized (compactingTo) {
            WALIndex got = compactingTo.get();
            if (got != null) {
                throw new IllegalStateException("Tried to compact while another compaction is already underway: " + name);
            }

            if (primaryDb == null || prefixDb == null) {
                throw new IllegalStateException("Tried to compact a index that has been expunged: " + name);
            }

            removeDatabase(compactionStripe, Type.compacting);
            removeDatabase(compactionStripe, Type.compacted);
            removeDatabase(currentStripe, Type.backup);

            final LABPointerIndexWALIndex compactingWALIndex = new LABPointerIndexWALIndex(providerName,
                maxValueSizeInIndex,
                versionedPartitionName,
                environments,
                compactionStripe,
                name.typeName(Type.compacting),
                config);
            compactingTo.set(compactingWALIndex);

            return new CompactionWALIndex() {

                @Override
                public boolean merge(TxKeyPointers pointers) throws Exception {
                    return compactingWALIndex.merge(pointers, null);
                }

                @Override
                public void commit(boolean fsync, Callable<Void> commit) throws Exception {
                    lock.acquire(numPermits);
                    try {
                        compactingWALIndex.commit(fsync);
                        compactingWALIndex.close();
                        if (!compactingTo.compareAndSet(compactingWALIndex, null)) {
                            throw new IllegalStateException("Tried to commit a stale compaction index");
                        }
                        if (primaryDb == null || prefixDb == null) {
                            LOG.warn("Was not commited because index has been closed.");
                        } else {
                            LOG.debug("Committing before swap: {}", name.getPrimaryName());

                            boolean compactedNonEmpty = rename(compactionStripe, Type.compacting, Type.compacted, false);

                            synchronized (closed) {
                                primaryDb.close(true, true);
                                primaryDb = null;
                                prefixDb.close(true, true);
                                prefixDb = null;
                                if (hasActive) {
                                    rename(currentStripe, Type.active, Type.backup, compactedNonEmpty);
                                } else {
                                    removeDatabase(currentStripe, Type.active);
                                }

                                if (commit != null) {
                                    commit.call();
                                }

                                if (compactedNonEmpty) {
                                    rename(compactionStripe, Type.compacted, Type.active, true);
                                }
                                removeDatabase(currentStripe, Type.backup);

                                primaryDb = environments[compactionStripe].open(new ValueIndexConfig(name.getPrimaryName(),
                                    config.getEntriesBetweenLeaps(),
                                    config.getMaxHeapPressureInBytes(),
                                    config.getSplitWhenKeysTotalExceedsNBytes(),
                                    config.getSplitWhenValuesTotalExceedsNBytes(),
                                    config.getSplitWhenValuesAndKeysTotalExceedsNBytes(),
                                    NoOpFormatTransformerProvider.NAME,
                                    LABRawhide.NAME,
                                    MemoryRawEntryFormat.NAME,
                                    -1,
                                    LABHashIndexType.valueOf(config.getHashIndexType()),
                                    config.getHashIndexLoadFactor(),
                                    config.getHashIndexEnabled()));

                                prefixDb = environments[compactionStripe].open(new ValueIndexConfig(name.getPrefixName(),
                                    config.getEntriesBetweenLeaps(),
                                    config.getMaxHeapPressureInBytes(),
                                    config.getSplitWhenKeysTotalExceedsNBytes(),
                                    config.getSplitWhenValuesTotalExceedsNBytes(),
                                    config.getSplitWhenValuesAndKeysTotalExceedsNBytes(),
                                    NoOpFormatTransformerProvider.NAME,
                                    LABRawhide.NAME,
                                    MemoryRawEntryFormat.NAME,
                                    -1,
                                    LABHashIndexType.valueOf(config.getHashIndexType()),
                                    config.getHashIndexLoadFactor(),
                                    config.getHashIndexEnabled()));
                            }

                            currentStripe = compactionStripe;
                            LOG.debug("Committing after swap: {}", name.getPrimaryName());
                        }
                    } finally {
                        lock.release(numPermits);
                    }
                }

                @Override
                public void abort() throws Exception {
                    compactingWALIndex.close();
                    if (compactingTo.compareAndSet(compactingWALIndex, null)) {
                        removeDatabase(compactionStripe, Type.compacting);
                    }
                }
            };

        }
    }

    private boolean rename(int stripe, Type fromType, Type toType, boolean required) throws Exception {
        boolean primaryRenamed = environments[stripe].rename(name.typeName(fromType).getPrimaryName(), name.typeName(toType).getPrimaryName(), true);
        boolean prefixRenamed = environments[stripe].rename(name.typeName(fromType).getPrefixName(), name.typeName(toType).getPrefixName(), true);
        if (!primaryRenamed && (required || prefixRenamed)) {
            throw new IOException("Failed to rename"
                + " from:" + name.typeName(fromType).getPrimaryName()
                + " to:" + name.typeName(toType).getPrimaryName()
                + " required:" + required
                + " prefix:" + prefixRenamed);
        }
        return primaryRenamed;
    }

    private void removeDatabase(int stripe, Type type) throws Exception {
        environments[stripe].remove(name.typeName(type).getPrimaryName(), true);
        environments[stripe].remove(name.typeName(type).getPrefixName(), true);
    }

    public void flush(boolean fsync) throws Exception {
        lock.acquire();
        try {
            if (primaryDb != null) {
                primaryDb.commit(fsync, true);
            }
            if (prefixDb != null) {
                prefixDb.commit(fsync, true);
            }
        } finally {
            lock.release();
        }
    }

    @Override
    public void updatedProperties(Map<String, String> properties) {
    }

    @Override
    public String toString() {
        return "LABPointerIndexWALIndex{" + "name=" + name
            + ", environments=" + Arrays.toString(environments)
            + ", primaryDb=" + primaryDb
            + ", prefixDb=" + prefixDb
            + ", lock=" + lock
            + ", count=" + count
            + ", commits=" + commits
            + ", compactingTo=" + compactingTo
            + '}';
    }

}
