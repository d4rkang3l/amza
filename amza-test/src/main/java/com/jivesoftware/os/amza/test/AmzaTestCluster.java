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
package com.jivesoftware.os.amza.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.jivesoftware.os.amza.client.AmzaKretrProvider;
import com.jivesoftware.os.amza.service.AmzaChangeIdPacker;
import com.jivesoftware.os.amza.service.AmzaService;
import com.jivesoftware.os.amza.service.AmzaServiceInitializer.AmzaServiceConfig;
import com.jivesoftware.os.amza.service.EmbeddedAmzaServiceInitializer;
import com.jivesoftware.os.amza.service.WALIndexProviderRegistry;
import com.jivesoftware.os.amza.service.replication.MemoryBackedHighwaterStorage;
import com.jivesoftware.os.amza.service.replication.TakeFailureListener;
import com.jivesoftware.os.amza.service.storage.PartitionPropertyMarshaller;
import com.jivesoftware.os.amza.service.storage.PartitionProvider;
import com.jivesoftware.os.amza.shared.AmzaPartitionAPI;
import com.jivesoftware.os.amza.shared.AmzaPartitionAPI.TimestampedValue;
import com.jivesoftware.os.amza.shared.AmzaPartitionUpdates;
import com.jivesoftware.os.amza.shared.partition.PartitionName;
import com.jivesoftware.os.amza.shared.partition.PartitionProperties;
import com.jivesoftware.os.amza.shared.partition.PrimaryIndexDescriptor;
import com.jivesoftware.os.amza.shared.partition.VersionedPartitionName;
import com.jivesoftware.os.amza.shared.ring.AmzaRingReader;
import com.jivesoftware.os.amza.shared.ring.RingHost;
import com.jivesoftware.os.amza.shared.ring.RingMember;
import com.jivesoftware.os.amza.shared.scan.RowStream;
import com.jivesoftware.os.amza.shared.scan.RowsChanged;
import com.jivesoftware.os.amza.shared.stats.AmzaStats;
import com.jivesoftware.os.amza.shared.take.HighwaterStorage;
import com.jivesoftware.os.amza.shared.take.RowsTaker;
import com.jivesoftware.os.amza.shared.take.StreamingTakesConsumer;
import com.jivesoftware.os.amza.shared.take.StreamingTakesConsumer.StreamingTakeConsumed;
import com.jivesoftware.os.amza.shared.wal.WALKey;
import com.jivesoftware.os.amza.shared.wal.WALStorageDescriptor;
import com.jivesoftware.os.jive.utils.ordered.id.ConstantWriterIdProvider;
import com.jivesoftware.os.jive.utils.ordered.id.JiveEpochTimestampProvider;
import com.jivesoftware.os.jive.utils.ordered.id.OrderIdProviderImpl;
import com.jivesoftware.os.jive.utils.ordered.id.TimestampedOrderIdProvider;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang.mutable.MutableBoolean;
import org.apache.commons.lang.mutable.MutableInt;

public class AmzaTestCluster {

    private final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final File workingDirctory;
    private final ConcurrentSkipListMap<RingMember, AmzaNode> cluster = new ConcurrentSkipListMap<>();
    private int oddsOfAConnectionFailureWhenAdding = 0; // 0 never - 100 always
    private int oddsOfAConnectionFailureWhenTaking = 0; // 0 never - 100 always
    private AmzaService lastAmzaService = null;

    public AmzaTestCluster(File workingDirctory,
        int oddsOfAConnectionFailureWhenAdding,
        int oddsOfAConnectionFailureWhenTaking) {
        this.workingDirctory = workingDirctory;
        this.oddsOfAConnectionFailureWhenAdding = oddsOfAConnectionFailureWhenAdding;
        this.oddsOfAConnectionFailureWhenTaking = oddsOfAConnectionFailureWhenTaking;
    }

    public Collection<AmzaNode> getAllNodes() {
        return cluster.values();
    }

    public AmzaNode get(RingMember ringMember) {
        return cluster.get(ringMember);
    }

    public void remove(RingMember ringMember) {
        cluster.remove(ringMember);
    }

    public AmzaNode newNode(final RingMember localRingMember, final RingHost localRingHost) throws Exception {

        AmzaNode service = cluster.get(localRingMember);
        if (service != null) {
            return service;
        }

        AmzaServiceConfig config = new AmzaServiceConfig();
        config.workingDirectories = new String[] { workingDirctory.getAbsolutePath() + "/" + localRingHost.getHost() + "-" + localRingHost.getPort() };
        config.takeFromNeighborsIntervalInMillis = 5;
        config.compactTombstoneIfOlderThanNMillis = 100000L;
        //config.useMemMap = true;

        OrderIdProviderImpl orderIdProvider = new OrderIdProviderImpl(new ConstantWriterIdProvider(localRingHost.getPort()),
            new AmzaChangeIdPacker(), new JiveEpochTimestampProvider());

        RowsTaker updateTaker = new RowsTaker() {

            @Override
            public void availableRowsStream(RingMember localRingMember,
                RingMember remoteRingMember,
                RingHost remoteRingHost,
                long takeSessionId,
                long timeoutMillis,
                RowsTaker.AvailableStream updatedPartitionsStream) throws Exception {

                AmzaNode amzaNode = cluster.get(remoteRingMember);
                if (amzaNode == null) {
                    throw new IllegalStateException("Service doesn't exists for " + remoteRingMember);
                } else {
                    amzaNode.takePartitionUpdates(localRingMember, orderIdProvider.nextId(), timeoutMillis, (versionedPartitionName, status, txId) -> {
                        if (versionedPartitionName != null) {
                            updatedPartitionsStream.available(versionedPartitionName, status, txId);
                        }
                    });
                }
            }

            @Override
            public RowsTaker.StreamingRowsResult rowsStream(RingMember localRingMember,
                RingMember remoteRingMember,
                RingHost remoteRingHost,
                VersionedPartitionName remoteVersionedPartitionName,
                long remoteTxId,
                RowStream rowStream) {

                AmzaNode amzaNode = cluster.get(remoteRingMember);
                if (amzaNode == null) {
                    throw new IllegalStateException("Service doesn't exist for " + localRingMember);
                } else {
                    StreamingTakesConsumer.StreamingTakeConsumed consumed = amzaNode.rowsStream(localRingMember,
                        remoteVersionedPartitionName,
                        remoteTxId,
                        rowStream);
                    return new StreamingRowsResult(null, null, consumed.isOnline ? new HashMap<>() : null);
                }
            }

            @Override
            public boolean rowsTaken(RingMember remoteRingMember, RingHost remoteRingHost, VersionedPartitionName remoteVersionedPartitionName,
                long remoteTxId) {
                AmzaNode amzaNode = cluster.get(remoteRingMember);
                if (amzaNode == null) {
                    throw new IllegalStateException("Service doesn't exists for " + localRingMember);
                } else {
                    try {
                        amzaNode.remoteMemberTookToTxId(remoteRingMember, remoteRingHost, remoteVersionedPartitionName, remoteTxId);
                        return true;
                    } catch (Exception x) {
                        throw new RuntimeException("Issue while applying acks.", x);
                    }
                }
            }
        };

        final ObjectMapper mapper = new ObjectMapper();
        PartitionPropertyMarshaller partitionPropertyMarshaller = new PartitionPropertyMarshaller() {

            @Override
            public PartitionProperties fromBytes(byte[] bytes) throws Exception {
                return mapper.readValue(bytes, PartitionProperties.class);
            }

            @Override
            public byte[] toBytes(PartitionProperties partitionProperties) throws Exception {
                return mapper.writeValueAsBytes(partitionProperties);
            }
        };

        AmzaStats amzaStats = new AmzaStats();
        HighwaterStorage highWaterMarks = new MemoryBackedHighwaterStorage();

        AmzaService amzaService = new EmbeddedAmzaServiceInitializer().initialize(config,
            amzaStats,
            localRingMember,
            localRingHost,
            orderIdProvider,
            partitionPropertyMarshaller,
            new WALIndexProviderRegistry(),
            updateTaker,
            Optional.<TakeFailureListener>absent(),
            (RowsChanged changes) -> {
            });

        amzaService.start();

        final PartitionName partitionName = new PartitionName(false, "test", "partition1");

        amzaService.watch(partitionName,
            (RowsChanged changes) -> {
                if (changes.getApply().size() > 0) {
                    System.out.println("Service:" + localRingMember
                        + " Partition:" + partitionName.getName()
                        + " Changed:" + changes.getApply().size());
                }
            }
        );

        try {
            amzaService.getRingWriter().addRingMember(AmzaRingReader.SYSTEM_RING, localRingMember); // ?? Hacky
            amzaService.getRingWriter().addRingMember("test", localRingMember); // ?? Hacky
            if (lastAmzaService != null) {
                amzaService.getRingWriter().register(lastAmzaService.getRingReader().getRingMember(), lastAmzaService.getRingWriter().getRingHost());
                amzaService.getRingWriter().addRingMember(AmzaRingReader.SYSTEM_RING, lastAmzaService.getRingReader().getRingMember()); // ?? Hacky
                amzaService.getRingWriter().addRingMember("test", lastAmzaService.getRingReader().getRingMember()); // ?? Hacky

                lastAmzaService.getRingWriter().register(localRingMember, localRingHost);
                lastAmzaService.getRingWriter().addRingMember(AmzaRingReader.SYSTEM_RING, localRingMember); // ?? Hacky
                lastAmzaService.getRingWriter().addRingMember("test", localRingMember); // ?? Hacky
            }
            lastAmzaService = amzaService;
        } catch (Exception x) {
            x.printStackTrace();
            System.out.println("FAILED CONNECTING RING");
            System.exit(1);
        }

        service = new AmzaNode(localRingMember, localRingHost, amzaService, highWaterMarks, orderIdProvider);

        cluster.put(localRingMember, service);

        System.out.println("Added serviceHost:" + localRingMember + " to the cluster.");
        return service;
    }

    public class AmzaNode {

        private final Random random = new Random();
        private final RingMember ringMember;
        private final RingHost ringHost;
        private final AmzaService amzaService;
        private final HighwaterStorage highWaterMarks;
        private final TimestampedOrderIdProvider orderIdProvider;
        private boolean off = false;
        private int flapped = 0;
        private final ExecutorService asIfOverTheWire = Executors.newSingleThreadExecutor();
        private final AmzaKretrProvider clientProvider;

        public AmzaNode(RingMember ringMember,
            RingHost ringHost,
            AmzaService amzaService,
            HighwaterStorage highWaterMarks,
            TimestampedOrderIdProvider orderIdProvider) {

            this.ringMember = ringMember;
            this.ringHost = ringHost;
            this.amzaService = amzaService;
            this.highWaterMarks = highWaterMarks;
            this.clientProvider = new AmzaKretrProvider(amzaService);
            this.orderIdProvider = orderIdProvider;
        }

        @Override
        public String toString() {
            return ringMember.toString();
        }

        public boolean isOff() {
            return off;
        }

        public void setOff(boolean off) {
            this.off = off;
            flapped++;
        }

        public void stop() throws Exception {
            amzaService.stop();
        }

        public void create(PartitionName partitionName) throws Exception {
            WALStorageDescriptor storageDescriptor = new WALStorageDescriptor(
                new PrimaryIndexDescriptor("memory", 0, false, null), null, 1000, 1000);

            amzaService.setPropertiesIfAbsent(partitionName, new PartitionProperties(storageDescriptor, 2, false));

            AmzaService.AmzaPartitionRoute partitionRoute = amzaService.getPartitionRoute(partitionName);
            while (partitionRoute.orderedPartitionHosts.isEmpty()) {
                LOG.info("Waiting for " + partitionName + " to come online.");
                Thread.sleep(10000);
                partitionRoute = amzaService.getPartitionRoute(partitionName);
            }
        }

        public void update(PartitionName partitionName, WALKey k, byte[] v, boolean tombstone) throws Exception {
            if (off) {
                throw new RuntimeException("Service is off:" + ringMember);
            }

            AmzaPartitionUpdates updates = new AmzaPartitionUpdates();
            long timestamp = orderIdProvider.nextId();
            if (tombstone) {
                updates.remove(k, timestamp);
            } else {
                updates.set(k, v, timestamp);
            }
            clientProvider.getClient(partitionName).commit(updates, 2, 10, TimeUnit.SECONDS);

        }

        public byte[] get(PartitionName partitionName, WALKey key) throws Exception {
            if (off) {
                throw new RuntimeException("Service is off:" + ringMember);
            }

            List<byte[]> got = new ArrayList<>();
            clientProvider.getClient(partitionName).get(Collections.singletonList(key), (rowTxId, key1, timestampedValue) -> {
                got.add(timestampedValue != null ? timestampedValue.getValue() : null);
                return true;
            });
            return got.get(0);
        }

        void remoteMemberTookToTxId(RingMember ringMember,
            RingHost ringHost,
            VersionedPartitionName remoteVersionedPartitionName,
            long localTxId) throws Exception {
            amzaService.rowsTaken(ringMember, ringHost, remoteVersionedPartitionName, localTxId);
        }

        public StreamingTakeConsumed rowsStream(RingMember remoteRingMember,
            VersionedPartitionName localVersionedPartitionName,
            long localTxId,
            RowStream rowStream) {
            if (off) {
                throw new RuntimeException("Service is off:" + ringMember);
            }
            if (random.nextInt(100) > (100 - oddsOfAConnectionFailureWhenTaking)) {
                throw new RuntimeException("Random take failure:" + ringMember);
            }

            try {
                ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
                Future<Object> submit = asIfOverTheWire.submit(() -> {
                    amzaService.rowsStream(new DataOutputStream(bytesOut), remoteRingMember, localVersionedPartitionName, localTxId);
                    return null;
                });
                submit.get();
                StreamingTakesConsumer streamingTakesConsumer = new StreamingTakesConsumer();
                StreamingTakesConsumer.StreamingTakeConsumed consumed = streamingTakesConsumer.consume(new ByteArrayInputStream(bytesOut.toByteArray()),
                    rowStream);
                return consumed;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public void printService() throws Exception {
            if (off) {
                System.out.println(ringHost.getHost() + ":" + ringHost.getPort() + " is OFF flapped:" + flapped);
            }
        }

        public boolean compare(AmzaNode service) throws Exception {
            if (off || service.off) {
                return true;
            }

            Set<PartitionName> allAPartitions = amzaService.getPartitionNames();
            Set<PartitionName> allBPartitions = service.amzaService.getPartitionNames();

            if (allAPartitions.size() != allBPartitions.size()) {
                System.out.println(allAPartitions + " -vs- " + allBPartitions);
                return false;
            }

            Set<PartitionName> partitionNames = new HashSet<>();
            partitionNames.addAll(allAPartitions);
            partitionNames.addAll(allBPartitions);

            NavigableMap<RingMember, RingHost> aRing = amzaService.getRingReader().getRing(AmzaRingReader.SYSTEM_RING);
            NavigableMap<RingMember, RingHost> bRing = service.amzaService.getRingReader().getRing(AmzaRingReader.SYSTEM_RING);

            if (!aRing.equals(bRing)) {
                System.out.println(aRing + "-vs-" + bRing);
                return false;
            }

            for (PartitionName partitionName : partitionNames) {
                if (partitionName.equals(PartitionProvider.HIGHWATER_MARK_INDEX.getPartitionName())) {
                    continue;
                }

                AmzaPartitionAPI a = amzaService.getPartition(partitionName);
                AmzaPartitionAPI b = service.amzaService.getPartition(partitionName);
                if (a == null || b == null) {
                    System.out.println(partitionName + " " + amzaService.getRingReader().getRingMember() + " " + a + " -- vs --"
                        + service.amzaService.getRingReader().getRingMember() + " " + b);
                    return false;
                }
                if (!compare(partitionName, a, b)) {
                    System.out.println(highWaterMarks + " -vs- " + service.highWaterMarks);
                    return false;
                }
            }
            return true;
        }

        private void takePartitionUpdates(RingMember ringMember,
            long sessionId,
            long timeoutMillis,
            RowsTaker.AvailableStream updatedPartitionsStream) {

            if (off) {
                throw new RuntimeException("Service is off:" + ringMember);
            }
            if (random.nextInt(100) > (100 - oddsOfAConnectionFailureWhenTaking)) {
                throw new RuntimeException("Random take failure:" + ringMember);
            }

            try {
                amzaService.availableRowsStream(ringMember, sessionId, timeoutMillis, updatedPartitionsStream);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        //  Use for testing
        private boolean compare(PartitionName partitionName, AmzaPartitionAPI a, AmzaPartitionAPI b) throws Exception {
            final MutableInt compared = new MutableInt(0);
            final MutableBoolean passed = new MutableBoolean(true);
            a.scan(null, null, (txid, key, aValue) -> {
                try {
                    compared.increment();
                    TimestampedValue[] bValues = new TimestampedValue[1];
                    b.get(Collections.singletonList(key), (rowTxId, key1, scanned) -> {
                        bValues[0] = scanned;
                        return true;
                    });

                    TimestampedValue bValue = bValues[0];
                    String comparing = partitionName.getRingName() + ":" + partitionName.getName()
                        + " to " + partitionName.getRingName() + ":" + partitionName.getName() + "\n";

                    if (bValue == null) {
                        System.out.println("INCONSISTENCY: " + comparing + " " + aValue.getTimestampId()
                            + " != null"
                            + "' \n" + aValue + " vs null");
                        passed.setValue(false);
                        return false;
                    }
                    if (aValue.getTimestampId() != bValue.getTimestampId()) {
                        System.out.println("INCONSISTENCY: " + comparing + " timestamp:'" + aValue.getTimestampId()
                            + "' != '" + bValue.getTimestampId()
                            + "' \n" + aValue + " vs " + bValue);
                        passed.setValue(false);
                        System.out.println("----------------------------------");

                        return false;
                    }
                    if (aValue.getValue() == null && bValue.getValue() != null) {
                        System.out.println("INCONSISTENCY: " + comparing + " null"
                            + " != '" + Arrays.toString(bValue.getValue())
                            + "' \n" + aValue + " vs " + bValue);
                        passed.setValue(false);
                        return false;
                    }
                    if (aValue.getValue() != null && !Arrays.equals(aValue.getValue(), bValue.getValue())) {
                        System.out.println("INCONSISTENCY: " + comparing + " value:'" + Arrays.toString(aValue.getValue())
                            + "' != '" + Arrays.toString(bValue.getValue())
                            + "' aClass:" + aValue.getValue().getClass()
                            + "' bClass:" + bValue.getValue().getClass()
                            + "' \n" + aValue + " vs " + bValue);
                        passed.setValue(false);
                        return false;
                    }
                    return true;
                } catch (Exception x) {
                    throw new RuntimeException("Failed while comparing", x);
                }
            });

            System.out.println("partition:" + partitionName.getName() + " vs:" + partitionName.getName() + " compared:" + compared + " keys");
            return passed.booleanValue();
        }
    }
}
