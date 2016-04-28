package com.jivesoftware.os.amza.service.storage;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.jivesoftware.os.amza.api.BAInterner;
import com.jivesoftware.os.amza.api.TimestampedValue;
import com.jivesoftware.os.amza.api.partition.Consistency;
import com.jivesoftware.os.amza.api.partition.Durability;
import com.jivesoftware.os.amza.api.partition.PartitionName;
import com.jivesoftware.os.amza.api.partition.PartitionProperties;
import com.jivesoftware.os.amza.api.partition.RingMembership;
import com.jivesoftware.os.amza.api.partition.VersionedPartitionName;
import com.jivesoftware.os.amza.api.scan.RowChanges;
import com.jivesoftware.os.amza.api.scan.RowsChanged;
import com.jivesoftware.os.amza.api.stream.RowType;
import com.jivesoftware.os.amza.api.wal.WALKey;
import com.jivesoftware.os.amza.api.wal.WALValue;
import com.jivesoftware.os.amza.service.IndexedWALStorageProvider;
import com.jivesoftware.os.amza.service.partition.VersionedPartitionProvider;
import com.jivesoftware.os.amza.service.replication.SystemStriper;
import com.jivesoftware.os.amza.service.stats.AmzaStats;
import com.jivesoftware.os.filer.io.StripingLocksProvider;
import com.jivesoftware.os.jive.utils.collections.lh.ConcurrentLHash;
import com.jivesoftware.os.jive.utils.ordered.id.TimestampedOrderIdProvider;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import static com.jivesoftware.os.amza.service.storage.PartitionCreator.REGION_PROPERTIES;

/**
 * @author jonathan.colt
 */
public class PartitionIndex implements RowChanges, VersionedPartitionProvider {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private static final PartitionProperties REPLICATED_PROPERTIES = new PartitionProperties(Durability.fsync_never,
        0, 0, 0, 0, 0, 0, 0, 0,
        true,
        Consistency.none,
        true,
        true,
        false,
        RowType.primary,
        "memory_persistent",
        null,
        -1,
        -1);

    private static final PartitionProperties NON_REPLICATED_PROPERTIES = new PartitionProperties(Durability.fsync_never,
        0, 0, 0, 0, 0, 0, 0, 0,
        true,
        Consistency.none,
        true,
        false,
        false,
        RowType.primary,
        "memory_persistent",
        null,
        Integer.MAX_VALUE,
        -1);

    private static final PartitionProperties AQUARIUM_PROPERTIES = new PartitionProperties(Durability.ephemeral,
        0, 0, 0, 0, 0, 0, 0, 0,
        false,
        Consistency.none,
        true,
        true,
        false,
        RowType.primary,
        "memory_ephemeral",
        null,
        16,
        4);

    // TODO consider replacing ConcurrentHashMap<Long, PartitionStore> LHash
    private final ConcurrentMap<PartitionName, ConcurrentLHash<PartitionStore>> partitionStores = Maps.newConcurrentMap();
    private final ConcurrentMap<PartitionName, PartitionProperties> partitionProperties = Maps.newConcurrentMap();
    private final StripingLocksProvider<VersionedPartitionName> locksProvider = new StripingLocksProvider<>(1024); // TODO expose to config

    private final BAInterner interner;
    private final AmzaStats amzaStats;
    private final TimestampedOrderIdProvider orderIdProvider;
    private final IndexedWALStorageProvider walStorageProvider;
    private final PartitionPropertyMarshaller partitionPropertyMarshaller;
    private final int concurrency;

    private final AtomicLong partitionPropertiesVersion = new AtomicLong();

    public PartitionIndex(BAInterner interner,
        AmzaStats amzaStats,
        TimestampedOrderIdProvider orderIdProvider,
        IndexedWALStorageProvider walStorageProvider,
        PartitionPropertyMarshaller partitionPropertyMarshaller,
        int concurrency) {

        this.interner = interner;
        this.amzaStats = amzaStats;
        this.orderIdProvider = orderIdProvider;
        this.walStorageProvider = walStorageProvider;
        this.partitionPropertyMarshaller = partitionPropertyMarshaller;
        this.concurrency = concurrency;
    }

    private static final Map<VersionedPartitionName, PartitionProperties> SYSTEM_PARTITIONS = ImmutableMap
        .<VersionedPartitionName, PartitionProperties>builder()
        .put(PartitionCreator.REGION_INDEX, REPLICATED_PROPERTIES)
        .put(PartitionCreator.RING_INDEX, REPLICATED_PROPERTIES)
        .put(PartitionCreator.NODE_INDEX, REPLICATED_PROPERTIES)
        .put(PartitionCreator.PARTITION_VERSION_INDEX, REPLICATED_PROPERTIES)
        .put(PartitionCreator.REGION_PROPERTIES, REPLICATED_PROPERTIES)
        .put(PartitionCreator.HIGHWATER_MARK_INDEX, NON_REPLICATED_PROPERTIES)
        .put(PartitionCreator.AQUARIUM_STATE_INDEX, AQUARIUM_PROPERTIES)
        .put(PartitionCreator.AQUARIUM_LIVELINESS_INDEX, AQUARIUM_PROPERTIES)
        .build();

    public void init(SystemStriper systemStriper) throws Exception {
        for (VersionedPartitionName versionedPartitionName : SYSTEM_PARTITIONS.keySet()) {
            int systemStripe = systemStriper.getSystemStripe(versionedPartitionName.getPartitionName());
            get(versionedPartitionName, systemStripe);
        }
    }

    @Override
    public PartitionProperties getProperties(PartitionName partitionName) {

        return partitionProperties.computeIfAbsent(partitionName, (key) -> {
            try {
                if (partitionName.isSystemPartition()) {
                    return SYSTEM_PARTITIONS.get(new VersionedPartitionName(partitionName, VersionedPartitionName.STATIC_VERSION));
                } else {
                    TimestampedValue rawPartitionProperties = getSystemPartition(PartitionCreator.REGION_PROPERTIES)
                        .getTimestampedValue(null, partitionName.toBytes());
                    if (rawPartitionProperties == null) {
                        return null;
                    }
                    return partitionPropertyMarshaller.fromBytes(rawPartitionProperties.getValue());
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public VersionedPartitionProperties getVersionedProperties(PartitionName partitionName, VersionedPartitionProperties versionedPartitionProperties) {
        long version = partitionPropertiesVersion.get();
        if (versionedPartitionProperties != null && versionedPartitionProperties.vesion >= version) {
            return versionedPartitionProperties;
        }
        return new VersionedPartitionProperties(version, getProperties(partitionName));
    }

    public PartitionStore getIfPresent(VersionedPartitionName versionedPartitionName) {
        ConcurrentLHash<PartitionStore> versionedStores = partitionStores.get(versionedPartitionName.getPartitionName());
        if (versionedStores != null) {
            return versionedStores.get(versionedPartitionName.getPartitionVersion());
        }
        return null;
    }

    public PartitionStore get(VersionedPartitionName versionedPartitionName, int stripe) throws Exception {
        return getAndValidate(-1, -1, versionedPartitionName, stripe);
    }

    public PartitionStore getAndValidate(long deltaWALId, long prevDeltaWALId, VersionedPartitionName versionedPartitionName, int stripe) throws Exception {
        PartitionName partitionName = versionedPartitionName.getPartitionName();
        if (deltaWALId > -1 && partitionName.isSystemPartition()) {
            throw new IllegalStateException("Hooray you have a bug! Should never call get with something other than -1 for system parititions." + deltaWALId);
        }
        ConcurrentLHash<PartitionStore> versionedStores = partitionStores.get(partitionName);
        if (versionedStores != null) {
            PartitionStore partitionStore = versionedStores.get(versionedPartitionName.getPartitionVersion());
            if (partitionStore != null) {
                File baseKey = walStorageProvider.baseKey(versionedPartitionName, stripe);
                partitionStore.load(baseKey, deltaWALId, prevDeltaWALId, stripe);
                return partitionStore;
            }
        }

        if (!versionedPartitionName.getPartitionName().isSystemPartition()
            && !getSystemPartition(PartitionCreator.REGION_INDEX).containsKey(null, partitionName.toBytes())) {
            return null;
        }

        PartitionProperties properties = getProperties(partitionName);
        if (properties == null) {
            return null;
        }
        return init(deltaWALId, prevDeltaWALId, versionedPartitionName, stripe, properties);

    }

    public PartitionStore getSystemPartition(VersionedPartitionName versionedPartitionName) {
        Preconditions.checkArgument(versionedPartitionName.getPartitionName().isSystemPartition(), "Should only be called by system partitions.");
        ConcurrentLHash<PartitionStore> versionedPartitionStores = partitionStores.get(versionedPartitionName.getPartitionName());
        PartitionStore store = versionedPartitionStores == null ? null : versionedPartitionStores.get(0L);
        if (store == null) {
            throw new IllegalStateException("There is no system partition for " + versionedPartitionName);
        }
        return store;
    }

    public void delete(VersionedPartitionName versionedPartitionName, int stripe) throws Exception {
        ConcurrentLHash<PartitionStore> versionedStores = partitionStores.get(versionedPartitionName.getPartitionName());
        if (versionedStores != null) {
            PartitionStore partitionStore = versionedStores.get(versionedPartitionName.getPartitionVersion());
            if (partitionStore != null) {
                File baseKey = walStorageProvider.baseKey(versionedPartitionName, stripe);
                partitionStore.delete(baseKey);
                versionedStores.remove(versionedPartitionName.getPartitionVersion());
            }
        }
    }

    private PartitionStore init(long deltaWALId,
        long prevDeltaWALId,
        VersionedPartitionName versionedPartitionName,
        int stripe,
        PartitionProperties properties) throws Exception {
        synchronized (locksProvider.lock(versionedPartitionName, 1234)) {
            ConcurrentLHash<PartitionStore> versionedStores = partitionStores.computeIfAbsent(versionedPartitionName.getPartitionName(),
                (key) -> new ConcurrentLHash<>(3, -1, -2, concurrency));

            PartitionStore partitionStore = versionedStores.get(versionedPartitionName.getPartitionVersion());
            if (partitionStore != null) {
                return partitionStore;
            }

            File baseKey = walStorageProvider.baseKey(versionedPartitionName, stripe);
            WALStorage<?> walStorage = walStorageProvider.create(versionedPartitionName, properties);
            partitionStore = new PartitionStore(amzaStats, orderIdProvider, versionedPartitionName, walStorage, properties);
            partitionStore.load(baseKey, deltaWALId, prevDeltaWALId, stripe);

            versionedStores.put(versionedPartitionName.getPartitionVersion(), partitionStore);
            LOG.info("Opened partition:" + versionedPartitionName);

            return partitionStore;
        }
    }

    public void putProperties(PartitionName partitionName, PartitionProperties properties) {
        partitionProperties.put(partitionName, properties);
    }

    public void removeProperties(PartitionName partitionName) {
        partitionProperties.remove(partitionName);
    }

    public boolean exists(VersionedPartitionName versionedPartitionName, int stripe) throws Exception {
        return get(versionedPartitionName, stripe) != null;
    }

    @Override
    public Iterable<PartitionName> getMemberPartitions(RingMembership ringMembership) throws Exception {
        PartitionStore propertiesStore = getSystemPartition(PartitionCreator.REGION_PROPERTIES);
        List<PartitionName> partitionNames = Lists.newArrayList();
        propertiesStore.rowScan((rowType, prefix, key, value, valueTimestamp, valueTombstoned, valueVersion) -> {
            if (!valueTombstoned && valueTimestamp != -1) {
                PartitionName partitionName = PartitionName.fromBytes(key, 0, interner);
                if (ringMembership == null || ringMembership.isMemberOfRing(partitionName.getRingName())) {
                    partitionNames.add(partitionName);
                }
            }
            return true;
        });
        return partitionNames;
    }

    public interface PartitionStream {

        boolean stream(VersionedPartitionName versionedPartitionName) throws Exception;
    }

    public void streamActivePartitions(PartitionStream stream) throws Exception {
        for (Entry<PartitionName, ConcurrentLHash<PartitionStore>> entry : partitionStores.entrySet()) {
            if (!entry.getValue().stream((key, partitionStore) -> stream.stream(new VersionedPartitionName(entry.getKey(), key)))) {
                break;
            }
        }
    }

    public interface PartitionPropertiesStream {

        boolean stream(PartitionName partitionName, PartitionProperties partitionProperties) throws Exception;
    }

    public void streamAllParitions(PartitionPropertiesStream partitionStream) throws Exception {
        getSystemPartition(PartitionCreator.REGION_PROPERTIES).rowScan(
            (RowType rowType, byte[] prefix, byte[] key, byte[] value, long valueTimestamp, boolean valueTombstoned, long valueVersion) -> {
                if (!valueTombstoned) {
                    PartitionName partitionName = PartitionName.fromBytes(key, 0, interner);
                    PartitionProperties properties = partitionPropertyMarshaller.fromBytes(value);
                    if (!partitionStream.stream(partitionName, properties)) {
                        return false;
                    }
                }
                return true;
            });
    }

    public Iterable<VersionedPartitionName> getSystemPartitions() {
        return SYSTEM_PARTITIONS.keySet();
    }

    // TODO this is never called
    @Override
    public void changes(final RowsChanged changes) throws Exception {
        if (changes.getVersionedPartitionName().getPartitionName().equals(REGION_PROPERTIES.getPartitionName())) {
            try {
                for (Map.Entry<WALKey, WALValue> entry : changes.getApply().entrySet()) {
                    PartitionName partitionName = PartitionName.fromBytes(entry.getKey().key, 0, interner);
                    removeProperties(partitionName);

                    ConcurrentLHash<PartitionStore> versionedPartitionStores = partitionStores.get(partitionName);
                    if (versionedPartitionStores != null) {
                        versionedPartitionStores.stream((long key, PartitionStore store) -> {
                            PartitionProperties properties = getProperties(partitionName);
                            store.updateProperties(properties);
                            return true;
                        });
                    }
                }
                partitionPropertiesVersion.incrementAndGet();
            } catch (Throwable ex) {
                throw new RuntimeException("Error while streaming entry set.", ex);
            }
        }
    }

    public void invalidate(PartitionName partitionName) {
        partitionStores.remove(partitionName);
        partitionProperties.remove(partitionName);
    }
}
