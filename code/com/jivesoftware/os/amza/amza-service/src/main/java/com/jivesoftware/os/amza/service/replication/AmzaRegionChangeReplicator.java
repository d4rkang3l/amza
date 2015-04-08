package com.jivesoftware.os.amza.service.replication;

import com.google.common.base.Optional;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.jivesoftware.os.amza.service.AmzaHostRing;
import com.jivesoftware.os.amza.service.storage.RegionProvider;
import com.jivesoftware.os.amza.service.storage.WALs;
import com.jivesoftware.os.amza.shared.HostRing;
import com.jivesoftware.os.amza.shared.RegionName;
import com.jivesoftware.os.amza.shared.RegionProperties;
import com.jivesoftware.os.amza.shared.RingHost;
import com.jivesoftware.os.amza.shared.RowsChanged;
import com.jivesoftware.os.amza.shared.Scannable;
import com.jivesoftware.os.amza.shared.UpdatesSender;
import com.jivesoftware.os.amza.shared.WALReplicator;
import com.jivesoftware.os.amza.shared.WALStorage;
import com.jivesoftware.os.amza.shared.WALStorageUpdateMode;
import com.jivesoftware.os.amza.shared.WALValue;
import com.jivesoftware.os.amza.shared.stats.AmzaStats;
import com.jivesoftware.os.amza.storage.RowMarshaller;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author jonathan.colt
 */
public class AmzaRegionChangeReplicator implements WALReplicator {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private ScheduledExecutorService resendThreadPool;
    private ScheduledExecutorService compactThreadPool;
    private final AmzaStats amzaStats;
    private final RowMarshaller<byte[]> rowMarshaller;
    private final AmzaHostRing amzaRing;
    private final RegionProvider regionProvider;
    private final WALs resendWAL;
    private final UpdatesSender updatesSender;
    private final ExecutorService sendExecutor;
    private final Optional<SendFailureListener> sendFailureListener;
    private final Map<RegionName, Long> highwaterMarks = new ConcurrentHashMap<>();
    private final long resendReplicasIntervalInMillis;
    private final int numberOfResendThreads;

    public AmzaRegionChangeReplicator(AmzaStats amzaStats,
        RowMarshaller<byte[]> rowMarshaller,
        AmzaHostRing amzaRing,
        RegionProvider regionProvider,
        WALs resendWAL,
        UpdatesSender updatesSender,
        ExecutorService sendExecutor,
        Optional<SendFailureListener> sendFailureListener,
        long resendReplicasIntervalInMillis,
        int numberOfResendThreads) {

        this.amzaStats = amzaStats;
        this.rowMarshaller = rowMarshaller;
        this.amzaRing = amzaRing;
        this.regionProvider = regionProvider;
        this.resendWAL = resendWAL;
        this.updatesSender = updatesSender;
        this.sendExecutor = sendExecutor;
        this.sendFailureListener = sendFailureListener;
        this.resendReplicasIntervalInMillis = resendReplicasIntervalInMillis;
        this.numberOfResendThreads = numberOfResendThreads;
    }

    synchronized public void start() throws Exception {

        if (resendThreadPool == null) {
            resendThreadPool = Executors.newScheduledThreadPool(numberOfResendThreads,
                new ThreadFactoryBuilder().setNameFormat("resendLocalChanges-%d").build());
            for (int i = 0; i < numberOfResendThreads; i++) {
                final int stripe = i;
                resendThreadPool.scheduleWithFixedDelay(new Runnable() {

                    @Override
                    public void run() {
                        try {
                            resendLocalChanges(stripe);
                        } catch (Throwable x) {
                            LOG.warn("Shouldn't have gotten here. Implements please catch your expections.", x);
                        }
                    }
                }, resendReplicasIntervalInMillis, resendReplicasIntervalInMillis, TimeUnit.MILLISECONDS);
            }
        }

        if (compactThreadPool == null) {
            compactThreadPool = Executors.newScheduledThreadPool(1, new ThreadFactoryBuilder().setNameFormat("compactResendChanges-%d").build());
            compactThreadPool.scheduleWithFixedDelay(new Runnable() {

                @Override
                public void run() {
                    try {
                        compactResendChanges();
                    } catch (Throwable x) {
                        LOG.warn("Shouldn't have gotten here. Implements please catch your expections.", x);
                    }
                }
            }, 1, 1, TimeUnit.MINUTES);

        }
    }

    synchronized public void stop() throws Exception {
        if (resendThreadPool != null) {
            this.resendThreadPool.shutdownNow();
            this.resendThreadPool = null;
        }

        if (compactThreadPool != null) {
            this.compactThreadPool.shutdownNow();
            this.compactThreadPool = null;
        }
    }

    @Override
    public Future<Boolean> replicate(RowsChanged rowsChanged) throws Exception {
        return replicateLocalUpdates(rowsChanged.getRegionName(), rowsChanged, true);
    }

    public Future<Boolean> replicateLocalUpdates(
        final RegionName regionName,
        final Scannable<WALValue> updates,
        final boolean enqueueForResendOnFailure) throws Exception {

        final RegionProperties regionProperties = regionProvider.getRegionProperties(regionName);
        return sendExecutor.submit(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                if (regionProperties != null) {
                    if (!regionProperties.disabled && regionProperties.replicationFactor > 0) {
                        HostRing hostRing = amzaRing.getHostRing(regionName.getRingName());
                        RingHost[] ringHosts = hostRing.getBelowRing();
                        if (ringHosts == null || ringHosts.length == 0) {
                            if (enqueueForResendOnFailure) {
                                resendWAL.execute(regionName, new WALs.Tx<Void>() {
                                    @Override
                                    public Void execute(WALStorage resend) throws Exception {
                                        resend.update(null, WALStorageUpdateMode.noReplication, updates);
                                        return null;
                                    }
                                });
                            }
                            return false;
                        } else {
                            int numReplicated = replicateUpdatesToRingHosts(regionName, updates, enqueueForResendOnFailure, ringHosts,
                                regionProperties.replicationFactor);
                            return numReplicated >= regionProperties.replicationFactor;
                        }
                    } else {
                        return true;
                    }
                } else {
                    if (enqueueForResendOnFailure) {
                        resendWAL.execute(regionName, new WALs.Tx<Void>() {
                            @Override
                            public Void execute(WALStorage resend) throws Exception {
                                resend.update(null, WALStorageUpdateMode.noReplication, updates);
                                return null;
                            }
                        });
                    }
                    return false;
                }
            }
        });
    }

    public int replicateUpdatesToRingHosts(RegionName regionName,
        final Scannable<WALValue> updates,
        boolean enqueueForResendOnFailure,
        RingHost[] ringHosts,
        int replicationFactor) throws Exception {

        RingWalker ringWalker = new RingWalker(ringHosts, replicationFactor);
        RingHost ringHost;
        while ((ringHost = ringWalker.host()) != null) {
            try {
                updatesSender.sendUpdates(ringHost, regionName, updates);
                amzaStats.offered(ringHost);
                amzaStats.replicateErrors.setCount(ringHost, 0);
                if (sendFailureListener.isPresent()) {
                    sendFailureListener.get().sent(ringHost);
                }
                ringWalker.success();
            } catch (Exception x) {
                if (sendFailureListener.isPresent()) {
                    sendFailureListener.get().failedToSend(ringHost, x);
                }
                ringWalker.failed();
                if (amzaStats.replicateErrors.count(ringHost) == 0) {
                    LOG.warn("Can't replicate to host:{}", ringHost);
                    LOG.trace("Can't replicate to host:{} region:{} takeFromFactor:{}", new Object[] { ringHost, regionName, replicationFactor }, x);
                }
                amzaStats.replicateErrors.add(ringHost);
                if (enqueueForResendOnFailure) {
                    resendWAL.execute(regionName, new WALs.Tx<Void>() {
                        @Override
                        public Void execute(WALStorage resend) throws Exception {
                            resend.update(null, WALStorageUpdateMode.noReplication, updates);
                            return null;
                        }
                    });
                    enqueueForResendOnFailure = false;
                }
            }
        }
        return ringWalker.getNumReplicated();
    }

    void resendLocalChanges(int stripe) throws Exception {

        for (final RegionName regionName : regionProvider.getActiveRegions()) {
            if (Math.abs(regionName.hashCode()) % numberOfResendThreads == stripe) {
                HostRing hostRing = amzaRing.getHostRing(regionName.getRingName());
                RingHost[] ring = hostRing.getBelowRing();
                if (ring.length > 0) {
                    resendWAL.execute(regionName, new WALs.Tx<Void>() {
                        @Override
                        public Void execute(WALStorage resend) throws Exception {
                            Long highWatermark = highwaterMarks.get(regionName);
                            HighwaterInterceptor highwaterInterceptor = new HighwaterInterceptor(highWatermark, resend);
                            ReplicateBatchinator batchinator = new ReplicateBatchinator(rowMarshaller, regionName, AmzaRegionChangeReplicator.this);
                            highwaterInterceptor.rowScan(batchinator);
                            if (batchinator.flush()) {
                                highwaterMarks.put(regionName, highwaterInterceptor.getHighwater());
                            }
                            return null;
                        }
                    });
                } else {
                    LOG.warn("Trying to resend to an empty ring. regionName:" + regionName);
                }
            }
        }
    }

    void compactResendChanges() throws Exception {
        for (final RegionName regionName : regionProvider.getActiveRegions()) {
            final Long highWatermark = highwaterMarks.get(regionName);
            if (highWatermark != null) {
                boolean compactedToEmpty = resendWAL.execute(regionName, new WALs.Tx<Boolean>() {
                    @Override
                    public Boolean execute(WALStorage regionWAL) throws Exception {
                        amzaStats.beginCompaction("Compacting Resend:" + regionName);
                        try {
                            long sizeInBytes = regionWAL.compactTombstone(highWatermark, highWatermark);
                            return sizeInBytes == 0;
                        } catch (Exception x) {
                            LOG.warn("Failing to compact:" + regionName, x);
                            return false;
                        } finally {
                            amzaStats.endCompaction("Compacting Resend:" + regionName);
                        }
                    }
                });
                if (compactedToEmpty) {
                    resendWAL.removeIfEmpty(regionName);
                }
            }
        }
    }
}
