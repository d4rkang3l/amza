package com.jivesoftware.os.amza.service.storage;

import com.google.common.base.Preconditions;
import com.jivesoftware.os.amza.api.TimestampedValue;
import com.jivesoftware.os.amza.api.partition.Durability;
import com.jivesoftware.os.amza.api.partition.HighestPartitionTx;
import com.jivesoftware.os.amza.api.partition.PartitionName;
import com.jivesoftware.os.amza.api.partition.VersionedAquarium;
import com.jivesoftware.os.amza.api.partition.VersionedPartitionName;
import com.jivesoftware.os.amza.api.scan.RowChanges;
import com.jivesoftware.os.amza.api.scan.RowStream;
import com.jivesoftware.os.amza.api.scan.RowsChanged;
import com.jivesoftware.os.amza.api.stream.Commitable;
import com.jivesoftware.os.amza.api.stream.KeyContainedStream;
import com.jivesoftware.os.amza.api.stream.KeyValueStream;
import com.jivesoftware.os.amza.api.stream.RowType;
import com.jivesoftware.os.amza.api.stream.TxKeyValueStream;
import com.jivesoftware.os.amza.api.stream.UnprefixedWALKeys;
import com.jivesoftware.os.amza.api.take.Highwaters;
import com.jivesoftware.os.amza.api.wal.PrimaryRowMarshaller;
import com.jivesoftware.os.amza.api.wal.WALHighwater;
import com.jivesoftware.os.amza.api.wal.WALUpdated;
import com.jivesoftware.os.amza.service.replication.AsyncStripeFlusher;
import com.jivesoftware.os.amza.service.replication.PartitionStripe;
import com.jivesoftware.os.amza.service.stats.AmzaStats;
import com.jivesoftware.os.aquarium.LivelyEndState;

/**
 * @author jonathan.colt
 */
public class SystemWALStorage {

    private final AmzaStats amzaStats;
    private final PartitionIndex partitionIndex;
    private final PrimaryRowMarshaller rowMarshaller;
    private final HighwaterRowMarshaller<byte[]> highwaterRowMarshaller;
    private final RowChanges allRowChanges;
    private final AsyncStripeFlusher systemFlusher;
    private final boolean hardFlush;

    public SystemWALStorage(AmzaStats amzaStats,
        PartitionIndex partitionIndex,
        PrimaryRowMarshaller rowMarshaller,
        HighwaterRowMarshaller<byte[]> highwaterRowMarshaller,
        RowChanges allRowChanges,
        AsyncStripeFlusher systemFlusher,
        boolean hardFlush) {

        this.amzaStats = amzaStats;
        this.partitionIndex = partitionIndex;
        this.rowMarshaller = rowMarshaller;
        this.highwaterRowMarshaller = highwaterRowMarshaller;
        this.allRowChanges = allRowChanges;
        this.systemFlusher = systemFlusher;
        this.hardFlush = hardFlush;
    }

    public void load(Iterable<VersionedPartitionName> systemPartitionNames, HighestPartitionTx tx) throws Exception {
        for (VersionedPartitionName versionedPartitionName : systemPartitionNames) {
            long highestTxId = highestPartitionTxId(versionedPartitionName);
            tx.tx(new VersionedAquarium(versionedPartitionName, null), highestTxId);
        }
    }

    public RowsChanged update(VersionedPartitionName versionedPartitionName,
        byte[] prefix,
        Commitable updates,
        WALUpdated updated) throws Exception {

        PartitionName partitionName = versionedPartitionName.getPartitionName();
        Preconditions.checkArgument(partitionName.isSystemPartition(), "Must be a system partition");
        PartitionStore partitionStore = partitionIndex.getSystemPartition(versionedPartitionName);
        RowsChanged changed = partitionStore.getWalStorage().update(amzaStats.updateIoStats,
            true,
            partitionStore.getProperties().rowType,
            -1,
            false,
            prefix,
            updates);
        if (allRowChanges != null && !changed.isEmpty()) {
            allRowChanges.changes(changed);
        }
        if (!changed.getApply().isEmpty()) {
            //LOG.info("UPDATED:{} txId:{}", versionedPartitionName, changed.getLargestCommittedTxId());
            updated.updated(versionedPartitionName, changed.getLargestCommittedTxId());
        }
        partitionStore.flush(hardFlush);
        systemFlusher.forceFlush(Durability.fsync_async, 0);

        amzaStats.direct(partitionName, changed.getApply().size(), changed.getSmallestCommittedTxId());
        return changed;
    }

    public void flush(VersionedPartitionName versionedPartitionName) throws Exception {
        PartitionStore partitionStore = partitionIndex.getSystemPartition(versionedPartitionName);
        partitionStore.flush(true);
    }

    public TimestampedValue getTimestampedValue(VersionedPartitionName versionedPartitionName, byte[] prefix, byte[] key) throws Exception {
        Preconditions.checkArgument(versionedPartitionName.getPartitionName().isSystemPartition(), "Must be a system partition");
        long start = System.currentTimeMillis();
        TimestampedValue timestampedValue = partitionIndex.getSystemPartition(versionedPartitionName).getTimestampedValue(prefix, key);
        amzaStats.gets(versionedPartitionName.getPartitionName(), 1, System.currentTimeMillis() - start);
        return timestampedValue;
    }

    public boolean get(VersionedPartitionName versionedPartitionName,
        byte[] prefix,
        UnprefixedWALKeys keys,
        KeyValueStream stream) throws Exception {
        Preconditions.checkArgument(versionedPartitionName.getPartitionName().isSystemPartition(), "Must be a system partition");

        long start = System.currentTimeMillis();
        boolean got = partitionIndex.getSystemPartition(versionedPartitionName).streamValues(prefix, keys,
            (_prefix, key, value, valueTimestamp, valueTombstone, valueVersion) -> {
                if (valueTimestamp == -1) {
                    return stream.stream(prefix, key, null, -1, false, -1);
                } else {
                    return stream.stream(prefix, key, value, valueTimestamp, valueTombstone, valueVersion);
                }
            });

        amzaStats.gets(versionedPartitionName.getPartitionName(), 1, System.currentTimeMillis() - start);
        return got;
    }

    public boolean containsKeys(VersionedPartitionName versionedPartitionName,
        byte[] prefix,
        UnprefixedWALKeys keys,
        KeyContainedStream stream) throws Exception {
        Preconditions.checkArgument(versionedPartitionName.getPartitionName().isSystemPartition(), "Must be a system partition");
        return partitionIndex.getSystemPartition(versionedPartitionName).containsKeys(prefix, keys, stream);
    }

    public <R> R takeRowUpdatesSince(VersionedPartitionName versionedPartitionName,
        long transactionId,
        PartitionStripe.TakeRowUpdates<R> takeRowUpdates) throws Exception {

        Preconditions.checkArgument(versionedPartitionName.getPartitionName().isSystemPartition(), "Must be a system partition");

        PartitionStore partitionStore = partitionIndex.getSystemPartition(versionedPartitionName);
        PartitionStripe.RowStreamer streamer = rowStream -> partitionStore.takeRowUpdatesSince(transactionId, rowStream);
        return takeRowUpdates.give(versionedPartitionName, LivelyEndState.ALWAYS_ONLINE, streamer);
    }

    public boolean takeFromTransactionId(VersionedPartitionName versionedPartitionName,
        long transactionId,
        Highwaters highwaters,
        TxKeyValueStream txKeyValueStream)
        throws Exception {
        Preconditions.checkArgument(versionedPartitionName.getPartitionName().isSystemPartition(), "Must be a system partition");
        return partitionIndex.getSystemPartition(versionedPartitionName).getWalStorage().takeRowUpdatesSince(amzaStats.takeIoStats, transactionId,
            (rowFP, rowTxId, rowType, row) -> {
                if (rowType == RowType.highwater && highwaters != null) {
                    WALHighwater highwater = highwaterRowMarshaller.fromBytes(row);
                    highwaters.highwater(highwater);
                } else if (rowType.isPrimary() && rowTxId > transactionId) {
                    return rowMarshaller.fromRows(txFpRowStream -> txFpRowStream.stream(rowTxId, rowFP, rowType, row), txKeyValueStream);
                }
                return true;
            });
    }

    public boolean takeFromTransactionId(VersionedPartitionName versionedPartitionName,
        byte[] prefix,
        long transactionId,
        Highwaters highwaters,
        TxKeyValueStream txKeyValueStream)
        throws Exception {
        Preconditions.checkArgument(versionedPartitionName.getPartitionName().isSystemPartition(), "Must be a system partition");
        return partitionIndex.getSystemPartition(versionedPartitionName).getWalStorage().takeRowUpdatesSince(prefix, transactionId,
            (rowFP, rowTxId, rowType, row) -> {
                if (rowType == RowType.highwater && highwaters != null) {
                    WALHighwater highwater = highwaterRowMarshaller.fromBytes(row);
                    highwaters.highwater(highwater);
                } else if (rowType.isPrimary() && rowTxId > transactionId) {
                    return rowMarshaller.fromRows(txFpRowStream -> txFpRowStream.stream(rowTxId, rowFP, rowType, row), txKeyValueStream);
                }
                return true;
            });
    }

    public boolean takeRowsFromTransactionId(VersionedPartitionName versionedPartitionName, long transactionId, RowStream rowStream)
        throws Exception {
        Preconditions.checkArgument(versionedPartitionName.getPartitionName().isSystemPartition(), "Must be a system partition");
        return partitionIndex.getSystemPartition(versionedPartitionName).getWalStorage().takeRowUpdatesSince(amzaStats.takeIoStats, transactionId, rowStream);
    }

    public boolean rowScan(VersionedPartitionName versionedPartitionName, KeyValueStream keyValueStream, boolean hydrateValues) throws Exception {
        Preconditions.checkArgument(versionedPartitionName.getPartitionName().isSystemPartition(), "Must be a system partition");

        PartitionStore partitionStore = partitionIndex.getSystemPartition(versionedPartitionName);
        if (partitionStore == null) {
            throw new IllegalStateException("No partition defined for " + versionedPartitionName);
        } else {
            long start = System.currentTimeMillis();
            boolean got = partitionStore.getWalStorage().rowScan(keyValueStream, hydrateValues);
            if (hydrateValues) {
                amzaStats.scans(versionedPartitionName.getPartitionName(), 1, System.currentTimeMillis() - start);
            } else {
                amzaStats.scanKeys(versionedPartitionName.getPartitionName(), 1, System.currentTimeMillis() - start);
            }
            return got;
        }
    }

    public boolean rangeScan(VersionedPartitionName versionedPartitionName,
        byte[] fromPrefix,
        byte[] fromKey,
        byte[] toPrefix,
        byte[] toKey,
        KeyValueStream keyValueStream,
        boolean hydrateValues) throws Exception {

        Preconditions.checkArgument(versionedPartitionName.getPartitionName().isSystemPartition(), "Must be a system partition");

        PartitionStore partitionStore = partitionIndex.getSystemPartition(versionedPartitionName);
        if (partitionStore == null) {
            throw new IllegalStateException("No partition defined for " + versionedPartitionName);
        } else {
            long start = System.currentTimeMillis();
            boolean got = partitionStore.getWalStorage().rangeScan(fromPrefix, fromKey, toPrefix, toKey, keyValueStream, hydrateValues);
            if (hydrateValues) {
                amzaStats.scans(versionedPartitionName.getPartitionName(), 1, System.currentTimeMillis() - start);
            } else {
                amzaStats.scanKeys(versionedPartitionName.getPartitionName(), 1, System.currentTimeMillis() - start);
            }
            return got;
        }
    }

    public long highestPartitionTxId(VersionedPartitionName versionedPartitionName) throws Exception {
        Preconditions.checkArgument(versionedPartitionName.getPartitionName().isSystemPartition(), "Must be a system partition");
        PartitionStore partitionStore = partitionIndex.getSystemPartition(versionedPartitionName);
        if (partitionStore != null) {
            return partitionStore.getWalStorage().highestTxId();
        } else {
            return -1;
        }
    }

    public long count(VersionedPartitionName versionedPartitionName) throws Exception {
        Preconditions.checkArgument(versionedPartitionName.getPartitionName().isSystemPartition(), "Must be a system partition");
        return partitionIndex.getSystemPartition(versionedPartitionName).getWalStorage().count(keyStream -> true);
    }

    public long approximateCount(VersionedPartitionName versionedPartitionName) throws Exception {
        Preconditions.checkArgument(versionedPartitionName.getPartitionName().isSystemPartition(), "Must be a system partition");
        return partitionIndex.getSystemPartition(versionedPartitionName).getWalStorage().approximateCount();
    }
}
