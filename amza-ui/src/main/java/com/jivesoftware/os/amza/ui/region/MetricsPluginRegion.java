package com.jivesoftware.os.amza.ui.region;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.jivesoftware.os.amza.api.filer.UIO;
import com.jivesoftware.os.amza.api.partition.PartitionName;
import com.jivesoftware.os.amza.api.partition.RemoteVersionedState;
import com.jivesoftware.os.amza.api.partition.VersionedPartitionName;
import com.jivesoftware.os.amza.api.ring.RingMember;
import com.jivesoftware.os.amza.api.ring.RingMemberAndHost;
import com.jivesoftware.os.amza.api.scan.RowStream;
import com.jivesoftware.os.amza.api.stream.RowType;
import com.jivesoftware.os.amza.api.wal.WALHighwater;
import com.jivesoftware.os.amza.service.AmzaService;
import com.jivesoftware.os.amza.service.PartitionIsDisposedException;
import com.jivesoftware.os.amza.service.replication.PartitionStripeProvider;
import com.jivesoftware.os.amza.service.ring.AmzaRingReader;
import com.jivesoftware.os.amza.service.ring.RingTopology;
import com.jivesoftware.os.amza.service.stats.AmzaStats;
import com.jivesoftware.os.amza.service.stats.AmzaStats.CompactionFamily;
import com.jivesoftware.os.amza.service.stats.AmzaStats.Totals;
import com.jivesoftware.os.amza.service.take.HighwaterStorage;
import com.jivesoftware.os.amza.ui.soy.SoyRenderer;
import com.jivesoftware.os.aquarium.LivelyEndState;
import com.jivesoftware.os.aquarium.State;
import com.jivesoftware.os.aquarium.Waterline;
import com.jivesoftware.os.jive.utils.ordered.id.IdPacker;
import com.jivesoftware.os.jive.utils.ordered.id.TimestampProvider;
import com.jivesoftware.os.mlogger.core.LoggerSummary;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;

/**
 *
 */
// soy.page.healthPluginRegion
public class MetricsPluginRegion implements PageRegion<MetricsPluginRegion.MetricsPluginRegionInput> {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();
    private final NumberFormat numberFormat = NumberFormat.getInstance();

    private final String template;
    private final String partitionMetricsTemplate;
    private final String statsTemplate;
    private final String visualizePartitionTemplate;
    private final SoyRenderer renderer;
    private final AmzaRingReader ringReader;
    private final AmzaService amzaService;
    private final AmzaStats amzaStats;
    private final TimestampProvider timestampProvider;
    private final IdPacker idPacker;

    private final List<GarbageCollectorMXBean> garbageCollectors;
    private final MemoryMXBean memoryBean;
    private final RuntimeMXBean runtimeBean;

    public MetricsPluginRegion(String template,
        String partitionMetricsTemplate,
        String statsTemplate,
        String visualizePartitionTemplate,
        SoyRenderer renderer,
        AmzaRingReader ringReader,
        AmzaService amzaService,
        AmzaStats amzaStats,
        TimestampProvider timestampProvider,
        IdPacker idPacker) {
        this.template = template;
        this.partitionMetricsTemplate = partitionMetricsTemplate;
        this.statsTemplate = statsTemplate;
        this.visualizePartitionTemplate = visualizePartitionTemplate;
        this.renderer = renderer;
        this.ringReader = ringReader;
        this.amzaService = amzaService;
        this.amzaStats = amzaStats;
        this.timestampProvider = timestampProvider;
        this.idPacker = idPacker;

        garbageCollectors = ManagementFactory.getGarbageCollectorMXBeans();
        memoryBean = ManagementFactory.getMemoryMXBean();
        runtimeBean = ManagementFactory.getRuntimeMXBean();

    }

    public static class MetricsPluginRegionInput {

        final String ringName;
        final String partitionName;
        final boolean exact;
        final boolean visualize;

        public MetricsPluginRegionInput(String ringName, String partitionName, boolean exact, boolean visualize) {
            this.ringName = ringName;
            this.partitionName = partitionName;
            this.exact = exact;
            this.visualize = visualize;
        }
    }

    class VisualizePartition implements RowStream {

        List<Run> runs = new ArrayList<>();
        Run lastRun;
        long rowCount;

        @Override
        public boolean row(long rowFP, long rowTxId, RowType rowType, byte[] row) throws Exception {
            rowCount++;
            String subType = null;
            if (rowType == RowType.system) {
                long[] parts = UIO.bytesLongs(row);
                if (parts[0] == 2) {
                    subType = "leaps: lastTx:" + Long.toHexString(parts[1]);
                }
            }
            if (lastRun == null || lastRun.rowType != rowType || subType != null) {
                if (lastRun != null) {
                    runs.add(lastRun);
                }
                lastRun = new Run(rowType, rowFP, rowTxId);
            }

            if (subType != null) {
                lastRun.subType = subType;
            }
            lastRun.bytes += row.length;
            lastRun.count++;
            return true;
        }

        public void done(Map<String, Object> data) {
            runs.add(lastRun);

            List<Map<String, String>> runMaps = new ArrayList<>();
            for (Run r : runs) {
                if (r != null) {
                    Map<String, String> run = new HashMap<>();
                    run.put("rowType", r.rowType.name());
                    run.put("subType", r.subType);
                    run.put("startFp", String.valueOf(r.startFp));
                    run.put("rowTxId", Long.toHexString(r.rowTxId));
                    run.put("bytes", numberFormat.format(r.bytes));
                    run.put("count", numberFormat.format(r.count));
                    runMaps.add(run);
                }
            }
            data.put("runs", runMaps);
            data.put("rowCount", numberFormat.format(rowCount));
        }

        class Run {

            RowType rowType;
            String subType = "";
            long startFp;
            long rowTxId;
            long bytes;
            long count;

            public Run(RowType rowType, long startFp, long rowTxId) {
                this.rowType = rowType;
                this.startFp = startFp;
                this.rowTxId = rowTxId;
            }

        }

    }

    @Override
    public String render(MetricsPluginRegionInput input) {
        Map<String, Object> data = Maps.newHashMap();

        try {

            data.put("ringName", input.ringName);
            data.put("partitionName", input.partitionName);
            data.put("exact", input.exact);
            if (input.partitionName.length() > 0) {
                if (input.visualize) {
                    VisualizePartition visualizePartition = new VisualizePartition();
                    amzaService.visualizePartition(input.ringName.getBytes(), input.partitionName.getBytes(), visualizePartition);
                    visualizePartition.done(data);
                    return renderer.render(visualizePartitionTemplate, data);
                } else {
                    return renderer.render(partitionMetricsTemplate, data);
                }
            } else {

                data.put("addMember", numberFormat.format(amzaStats.addMember.get()));
                data.put("removeMember", numberFormat.format(amzaStats.removeMember.get()));
                data.put("getRing", numberFormat.format(amzaStats.getRing.get()));
                data.put("rowsStream", numberFormat.format(amzaStats.rowsStream.get()));
                data.put("availableRowsStream", numberFormat.format(amzaStats.availableRowsStream.get()));
                data.put("rowsTaken", numberFormat.format(amzaStats.rowsTaken.get()));

                List<Map<String, String>> longPolled = new ArrayList<>();
                for (Entry<RingMember, AtomicLong> polled : amzaStats.longPolled.entrySet()) {
                    AtomicLong longPollAvailables = amzaStats.longPollAvailables.get(polled.getKey());
                    longPolled.add(ImmutableMap.of("member", polled.getKey().getMember(),
                        "longPolled", numberFormat.format(polled.getValue().get()),
                        "longPollAvailables", numberFormat.format(longPollAvailables == null ? -1L : longPollAvailables.get())));
                }

                data.put("longPolled", longPolled);

                data.put("grandTotals", regionTotals(null, amzaStats.getGrandTotal(), false));
                List<Map<String, Object>> regionTotals = new ArrayList<>();
                List<PartitionName> partitionNames = Lists.newArrayList();
                Iterables.addAll(partitionNames, amzaService.getSystemPartitionNames());
                //Iterables.addAll(partitionNames, amzaService.getMemberPartitionNames());
                Collections.sort(partitionNames);
                for (PartitionName partitionName : partitionNames) {
                    Totals totals = amzaStats.getPartitionTotals().get(partitionName);
                    if (totals == null) {
                        totals = new Totals();
                    }
                    regionTotals.add(regionTotals(partitionName, totals, false));
                }
                data.put("regionTotals", regionTotals);
            }
            return renderer.render(template, data);
        } catch (Exception e) {
            LOG.error("Unable to retrieve data", e);
            return "Error";
        }

    }

    public String renderStats(String filter, boolean exact) {
        Map<String, Object> data = Maps.newHashMap();
        try {
            List<Map<String, String>> longPolled = new ArrayList<>();
            for (Entry<RingMember, AtomicLong> polled : amzaStats.longPolled.entrySet()) {
                AtomicLong longPollAvailables = amzaStats.longPollAvailables.get(polled.getKey());
                longPolled.add(ImmutableMap.of("member", polled.getKey().getMember(),
                    "longPolled", numberFormat.format(polled.getValue().get()),
                    "longPollAvailables", numberFormat.format(longPollAvailables == null ? -1L : longPollAvailables.get())));
            }

            data.put("longPolled", longPolled);

            data.put("grandTotals", regionTotals(null, amzaStats.getGrandTotal(), true));
            List<Map<String, Object>> regionTotals = new ArrayList<>();
            List<PartitionName> partitionNames = Lists.newArrayList();
            Iterables.addAll(partitionNames, amzaService.getSystemPartitionNames());
            Iterables.addAll(partitionNames, amzaService.getMemberPartitionNames());
            Collections.sort(partitionNames);
            for (PartitionName partitionName : partitionNames) {
                String name = new String(partitionName.getName(), StandardCharsets.UTF_8);
                if (exact && name.equals(filter) || !exact && name.contains(filter)) {
                    Totals totals = amzaStats.getPartitionTotals().get(partitionName);
                    if (totals == null) {
                        totals = new Totals();
                    }
                    regionTotals.add(regionTotals(partitionName, totals, true));
                }
            }
            data.put("regionTotals", regionTotals);
        } catch (Exception e) {
            LOG.error("Unable to retrieve data", e);
        }
        return renderer.render(statsTemplate, data);
    }

    public Map<String, Object> regionTotals(PartitionName name, AmzaStats.Totals totals, boolean includeCount) throws Exception {
        Map<String, Object> map = new HashMap<>();
        if (name != null) {

            map.put("type", name.isSystemPartition() ? "SYSTEM" : "USER");
            map.put("name", new String(name.getName()));
            map.put("ringName", new String(name.getRingName()));
            RingTopology ring = ringReader.getRing(name.getRingName());
            List<Map<String, String>> ringMaps = new ArrayList<>();
            for (RingMemberAndHost entry : ring.entries) {
                ringMaps.add(ImmutableMap.of("member", entry.ringMember.getMember(),
                    "host", entry.ringHost.getHost(),
                    "port", String.valueOf(entry.ringHost.getPort())));
            }
            map.put("ring", ringMaps);

            Map<VersionedPartitionName, Integer> categories = Maps.newHashMap();
            Map<VersionedPartitionName, Long> ringCallCounts = Maps.newHashMap();
            Map<VersionedPartitionName, Long> partitionCallCounts = Maps.newHashMap();
            amzaService.getTakeCoordinator().streamCategories((versionedPartitionName, category, ringCallCount, partitionCallCount) -> {
                categories.put(versionedPartitionName, category);
                ringCallCounts.put(versionedPartitionName, ringCallCount);
                partitionCallCounts.put(versionedPartitionName, partitionCallCount);
                return true;
            });

            PartitionStripeProvider partitionStripeProvider = amzaService.getPartitionStripeProvider();

            try {
                partitionStripeProvider.txPartition(name, (txPartitionStripe, highwaterStorage, versionedAquarium) -> {

                    VersionedPartitionName versionedPartitionName = versionedAquarium == null ? null : versionedAquarium.getVersionedPartitionName();
                    LivelyEndState livelyEndState = versionedAquarium == null ? null : versionedAquarium.getLivelyEndState();
                    Waterline currentWaterline = livelyEndState != null ? livelyEndState.getCurrentWaterline() : null;

                    map.put("state", currentWaterline == null ? "unknown" : currentWaterline.getState());
                    map.put("quorum", currentWaterline == null ? "unknown" : currentWaterline.isAtQuorum());
                    //map.put("timestamp", currentWaterline == null ? "unknown" : String.valueOf(currentWaterline.getTimestamp()));
                    //map.put("version", currentWaterline == null ? "unknown" : String.valueOf(currentWaterline.getVersion()));

                    map.put("partitionVersion", versionedPartitionName == null ? "none" : Long.toHexString(versionedPartitionName.getPartitionVersion()));
                    State currentState = livelyEndState == null ? State.bootstrap : livelyEndState.getCurrentState();
                    map.put("isOnline", livelyEndState != null && livelyEndState.isOnline());


                    long[] stripeVersion = new long[1];
                    txPartitionStripe.tx((deltaIndex, stripeIndex, partitionStripe) -> {
                        if (includeCount) {
                            map.put("count", partitionStripe == null ? "-1" : numberFormat.format(partitionStripe.count(versionedAquarium)));
                            map.put("keyCount", partitionStripe == null ? "-1" : numberFormat.format(partitionStripe.keyCount(versionedAquarium)));
                            map.put("clobberCount", partitionStripe == null ? "-1" : numberFormat.format(partitionStripe.clobberCount(versionedAquarium)));
                        } else {
                            map.put("count", "(requires watch)");
                        }
                        stripeVersion[0] = stripeIndex; // yawn

                        map.put("highestTxId", partitionStripe == null ? "-1" : String.valueOf(partitionStripe.highestAquariumTxId(versionedAquarium)));
                        return null;
                    });

                    int category = categories.getOrDefault(versionedPartitionName, -1);
                    long ringCallCount = ringCallCounts.getOrDefault(versionedPartitionName, -1L);
                    long partitionCallCount = partitionCallCounts.getOrDefault(versionedPartitionName, -1L);
                    map.put("category", category != -1 ? String.valueOf(category) : "unknown");
                    map.put("ringCallCount", String.valueOf(ringCallCount));
                    map.put("partitionCallCount", String.valueOf(partitionCallCount));

                    List<Map<String, Object>> tookLatencies = Lists.newArrayList();
                    if (includeCount) {
                        long currentTime = timestampProvider.getApproximateTimestamp(System.currentTimeMillis());
                        amzaService.getTakeCoordinator().streamTookLatencies(versionedPartitionName,
                            (ringMember, lastOfferedTxId, category1, tooSlowTxId, takeSessionId, online, steadyState, lastOfferedMillis,
                                lastTakenMillis, lastCategoryCheckMillis) -> {
                                Builder<String, Object> builder = ImmutableMap.<String, Object>builder();
                                builder.put("member", ringMember.getMember());

                                long tooSlowTimestamp = -1;
                                long latencyInMillis = -1;
                                if (lastOfferedTxId != -1) {
                                    long lastOfferedTimestamp = idPacker.unpack(lastOfferedTxId)[0];
                                    tooSlowTimestamp = idPacker.unpack(tooSlowTxId)[0];
                                    latencyInMillis = currentTime - lastOfferedTimestamp;
                                }
                                String latency = ((latencyInMillis < 0) ? '-' : ' ') + getDurationBreakdown(Math.abs(latencyInMillis));
                                builder
                                    .put("latency", (lastOfferedTxId == -1) ? "never" : latency)
                                    .put("category", String.valueOf(category1))
                                    .put("tooSlow", (lastOfferedTxId == -1) ? "never" : getDurationBreakdown(tooSlowTimestamp))
                                    .put("takeSessionId", String.valueOf(takeSessionId))
                                    .put("online", online)
                                    .put("steadyState", steadyState);
                                tookLatencies.add(builder.build());
                                return true;
                            });
                    }
                    map.put("tookLatencies", tookLatencies);

                    if (includeCount) {
                        if (versionedPartitionName == null) {
                            map.put("highwaters", "none");
                        } else if (name.isSystemPartition()) {
                            HighwaterStorage systemHighwaterStorage = amzaService.getSystemHighwaterStorage();
                            WALHighwater partitionHighwater = systemHighwaterStorage.getPartitionHighwater(versionedPartitionName);
                            map.put("highwaters", renderHighwaters(partitionHighwater));
                        } else {
                            WALHighwater partitionHighwater = highwaterStorage.getPartitionHighwater(versionedPartitionName);
                            map.put("highwaters", renderHighwaters(partitionHighwater));
                        }
                    } else {
                        map.put("highwaters", "(requires watch)");
                    }

                    map.put("localState", ImmutableMap.of("online", livelyEndState != null && livelyEndState.isOnline(),
                        "state", currentState != null ? currentState.name() : "unknown",
                        "name", new String(amzaService.getRingReader().getRingMember().asAquariumMember().getMember()),
                        "partitionVersion", versionedPartitionName == null ? "none" : String.valueOf(versionedPartitionName.getPartitionVersion()),
                        "stripeVersion", versionedAquarium == null ? "none" : String.valueOf(stripeVersion[0])));

                    return -1;
                });

                List<Map<String, Object>> neighborStates = new ArrayList<>();
                Set<RingMember> neighboringRingMembers = amzaService.getRingReader().getNeighboringRingMembers(name.getRingName());
                for (RingMember ringMember : neighboringRingMembers) {

                    RemoteVersionedState neighborState = partitionStripeProvider.getRemoteVersionedState(ringMember, name);
                    neighborStates.add(ImmutableMap.of("version", neighborState != null ? String.valueOf(neighborState.version) : "unknown",
                        "state", neighborState != null && neighborState.waterline != null ? neighborState.waterline.getState().name() : "unknown",
                        "name", new String(ringMember.getMember().getBytes())));
                }
                map.put("neighborStates", neighborStates);
            } catch (PartitionIsDisposedException e) {
                //TODO just make soy more tolerant
                map.put("state", "disposed");
                map.put("quorum", "disposed");

                map.put("partitionVersion", "disposed");
                map.put("isOnline", false);

                map.put("count", 0);
                map.put("highestTxId", "-1");

                map.put("category", "disposed");
                map.put("ringCallCount", "-1");
                map.put("partitionCallCount", "-1");

                map.put("tookLatencies", Collections.emptyList());
                map.put("highwaters", "disposed");

                map.put("localState", ImmutableMap.of("online", false,
                    "state", "disposed",
                    "name", new String(amzaService.getRingReader().getRingMember().asAquariumMember().getMember()),
                    "partitionVersion", "disposed",
                    "stripeVersion", "disposed"));
                map.put("neighborStates", Collections.emptyList());
            }
        }
        map.put("gets", numberFormat.format(totals.gets.get()));
        map.put("getsLag", getDurationBreakdown(totals.getsLatency.get()));
        map.put("scans", numberFormat.format(totals.scans.get()));
        map.put("scansLag", getDurationBreakdown(totals.scansLatency.get()));
        map.put("scanKeys", numberFormat.format(totals.scanKeys.get()));
        map.put("scanKeysLag", getDurationBreakdown(totals.scanKeysLatency.get()));
        map.put("directApplies", numberFormat.format(totals.directApplies.get()));
        map.put("directAppliesLag", getDurationBreakdown(totals.directAppliesLag.get()));
        map.put("updates", numberFormat.format(totals.updates.get()));
        map.put("updatesLag", getDurationBreakdown(totals.updatesLag.get()));
        map.put("offers", numberFormat.format(totals.offers.get()));
        map.put("offersLag", getDurationBreakdown(totals.offersLag.get()));
        map.put("takes", numberFormat.format(totals.takes.get()));
        map.put("takesLag", getDurationBreakdown(totals.takesLag.get()));
        map.put("takeApplies", numberFormat.format(totals.takeApplies.get()));
        map.put("takeAppliesLag", getDurationBreakdown(totals.takeAppliesLag.get()));
        map.put("acks", numberFormat.format(totals.acks.get()));
        map.put("acksLag", getDurationBreakdown(totals.acksLag.get()));
        map.put("quorums", numberFormat.format(totals.quorums.get()));
        map.put("quorumsLag", getDurationBreakdown(totals.quorumsLatency.get()));
        map.put("quorumTimeouts", numberFormat.format(totals.quorumTimeouts.get()));

        return map;
    }

    public String renderHighwaters(WALHighwater walHighwater) {
        StringBuilder sb = new StringBuilder();
        for (WALHighwater.RingMemberHighwater e : walHighwater.ringMemberHighwater) {
            sb.append("<p>");
            sb.append(e.ringMember.getMember()).append("=").append(Long.toHexString(e.transactionId)).append("\n");
            sb.append("</p>");
        }
        return sb.toString();
    }

    public Map<String, Object> renderPartition(PartitionName partitionName, long startFp, long endFp) {
        Map<String, Object> partitionViz = new HashMap<>();
        //amzaService.getPartitionCreator().

        return partitionViz;
    }

    public String renderOverview() throws Exception {

        StringBuilder sb = new StringBuilder();
        sb.append("<p>uptime<span class=\"badge\">").append(getDurationBreakdown(runtimeBean.getUptime())).append("</span>");
        sb.append("&nbsp;&nbsp;&nbsp;&nbsp;diskR<span class=\"badge\">").append(humanReadableByteCount(amzaStats.ioStats.read.get(), false)).append("</span>");
        sb.append("&nbsp;&nbsp;&nbsp;&nbsp;diskW<span class=\"badge\">").append(humanReadableByteCount(amzaStats.ioStats.wrote.get(), false)).append("</span>");
        sb.append("&nbsp;&nbsp;&nbsp;&nbsp;netR<span class=\"badge\">").append(humanReadableByteCount(amzaStats.netStats.read.get(), false)).append("</span>");
        sb.append("&nbsp;&nbsp;&nbsp;&nbsp;netW<span class=\"badge\">").append(humanReadableByteCount(amzaStats.netStats.wrote.get(), false)).append("</span>");
        sb.append("&nbsp;&nbsp;&nbsp;&nbsp;deltaRem1<span class=\"badge\">").append(amzaStats.deltaFirstCheckRemoves.get()).append("</span>");
        sb.append("&nbsp;&nbsp;&nbsp;&nbsp;deltaRem2<span class=\"badge\">").append(amzaStats.deltaSecondCheckRemoves.get()).append("</span>");

        double processCpuLoad = getProcessCpuLoad();
        sb.append(progress("CPU",
            (int) (processCpuLoad),
            numberFormat.format(processCpuLoad) + " cpu load"));

        double memoryLoad = (double) memoryBean.getHeapMemoryUsage().getUsed() / (double) memoryBean.getHeapMemoryUsage().getMax();
        sb.append(progress("Heap",
            (int) (memoryLoad * 100),
            humanReadableByteCount(memoryBean.getHeapMemoryUsage().getUsed(), false)
                + " used out of " + humanReadableByteCount(memoryBean.getHeapMemoryUsage().getMax(), false)));

        long s = 0;
        for (GarbageCollectorMXBean gc : garbageCollectors) {
            s += gc.getCollectionTime();
        }
        double gcLoad = (double) s / (double) runtimeBean.getUptime();
        sb.append(progress("GC",
            (int) (gcLoad * 100),
            getDurationBreakdown(s) + " total gc"));

        Totals grandTotal = amzaStats.getGrandTotal();

        sb.append(progress("Gets (" + numberFormat.format(grandTotal.gets.get()) + ")",
            (int) (((double) grandTotal.getsLatency.longValue() / 1000d) * 100),
            getDurationBreakdown(grandTotal.getsLatency.longValue()) + " lag"));

        sb.append(progress("Scans (" + numberFormat.format(grandTotal.scans.get()) + ")",
            (int) (((double) grandTotal.scansLatency.longValue() / 1000d) * 100),
            getDurationBreakdown(grandTotal.scansLatency.longValue()) + " lag"));

         sb.append(progress("ScanKeys (" + numberFormat.format(grandTotal.scanKeys.get()) + ")",
            (int) (((double) grandTotal.scanKeysLatency.longValue() / 1000d) * 100),
            getDurationBreakdown(grandTotal.scanKeysLatency.longValue()) + " lag"));

        sb.append(progress("Direct Applied (" + numberFormat.format(grandTotal.directApplies.get()) + ")",
            (int) (((double) grandTotal.directAppliesLag.longValue() / 1000d) * 100),
            getDurationBreakdown(grandTotal.directAppliesLag.longValue()) + " lag"));

        sb.append(progress("Updates (" + numberFormat.format(grandTotal.updates.get()) + ")",
            (int) (((double) grandTotal.updatesLag.longValue() / 10000d) * 100),
            getDurationBreakdown(grandTotal.updatesLag.longValue()) + " lag"));

        sb.append(progress("Offers (" + numberFormat.format(grandTotal.offers.get()) + ")",
            (int) (((double) grandTotal.offersLag.longValue() / 10000d) * 100),
            getDurationBreakdown(grandTotal.offersLag.longValue()) + " lag"));

        sb.append(progress("Took (" + numberFormat.format(grandTotal.takes.get()) + ")",
            (int) (((double) grandTotal.takesLag.longValue() / 10000d) * 100),
            getDurationBreakdown(grandTotal.takesLag.longValue()) + " lag"));

        sb.append(progress("Took Applied (" + numberFormat.format(grandTotal.takeApplies.get()) + ")",
            (int) (((double) grandTotal.takeAppliesLag.longValue() / 1000d) * 100),
            getDurationBreakdown(grandTotal.takeAppliesLag.longValue()) + " lag"));

        sb.append(progress("Took Average Rows (" + numberFormat.format(amzaStats.takes.get()) + ")",
            (int) (((double) amzaStats.takeExcessRows.get() / amzaStats.takes.get()) / 4096 * 100),
            numberFormat.format(amzaStats.takeExcessRows.get())));

        sb.append(progress("Acks (" + numberFormat.format(grandTotal.acks.get()) + ")",
            (int) (((double) grandTotal.acksLag.longValue() / 10000d) * 100),
            getDurationBreakdown(grandTotal.acksLag.longValue()) + " lag"));

        sb.append(progress("Quorums (" + numberFormat.format(grandTotal.quorums.get()) + " / " + numberFormat.format(grandTotal.quorumTimeouts.get()) + ")",
            (int) (((double) grandTotal.quorumsLatency.longValue() / 10000d) * 100),
            getDurationBreakdown(grandTotal.quorumsLatency.longValue()) + " lag"));

        sb.append(progress("Active Long Polls (" + numberFormat.format(amzaStats.availableRowsStream.get()) + ")",
            (int) (((double) amzaStats.availableRowsStream.get() / 100d) * 100), ""));

        sb.append(progress("Active Row Streaming (" + numberFormat.format(amzaStats.rowsStream.get()) + ")",
            (int) (((double) amzaStats.rowsStream.get() / 100d) * 100), "" + numberFormat.format(amzaStats.completedRowsStream.get())));

        sb.append(progress("Active Row Acknowledging (" + numberFormat.format(amzaStats.rowsTaken.get()) + ")",
            (int) (((double) amzaStats.rowsTaken.get() / 100d) * 100), "" + numberFormat.format(amzaStats.completedRowsTake.get())));

        sb.append(progress("Back Pressure (" + numberFormat.format(amzaStats.backPressure.get()) + ")",
            (int) (((double) amzaStats.backPressure.get() / 10000d) * 100), "" + amzaStats.pushBacks.get()));

        long[] count = amzaStats.deltaStripeMergeLoaded;
        double[] load = amzaStats.deltaStripeLoad;
        long[] mergeCount = amzaStats.deltaStripeMergePending;
        double[] mergeLoad = amzaStats.deltaStripeMerge;
        for (int i = 0; i < load.length; i++) {
            sb.append(progress(" Delta Stripe " + i + " (" + load[i] + ")", (int) (load[i] * 100), "" + numberFormat.format(count[i])));
            if (mergeLoad.length > i && mergeCount.length > i) {
                sb.append(progress("Merge Stripe " + i + " (" + numberFormat.format(mergeLoad[i]) + ")", (int) (mergeLoad[i] * 100),
                    numberFormat.format(mergeCount[i]) + " partitions"));
            }
        }

        int tombostoneCompaction = amzaStats.ongoingCompaction(AmzaStats.CompactionFamily.tombstone);
        int mergeCompaction = amzaStats.ongoingCompaction(AmzaStats.CompactionFamily.merge);
        int expungeCompaction = amzaStats.ongoingCompaction(AmzaStats.CompactionFamily.expunge);

        sb.append(progress("Tombstone Compactions (" + numberFormat.format(tombostoneCompaction) + ")",
            (int) (((double) tombostoneCompaction / 10d) * 100), " total:" + amzaStats.getTotalCompactions(CompactionFamily.tombstone)));

        sb.append(progress("Merge Compactions (" + numberFormat.format(mergeCompaction) + ")",
            (int) (((double) mergeCompaction / 10d) * 100), " total:" + amzaStats.getTotalCompactions(CompactionFamily.merge)));

        sb.append(progress("Expunge Compactions (" + numberFormat.format(expungeCompaction) + ")",
            (int) (((double) expungeCompaction / 10d) * 100), " total:" + amzaStats.getTotalCompactions(CompactionFamily.expunge)));

        sb.append("<p><pre>");
        for (String l : LoggerSummary.INSTANCE.lastNErrors.get()) {
            sb.append("ERROR ").append(l).append("\n");
        }
        for (String l : LoggerSummary.INSTANCE.lastNWarns.get()) {
            sb.append("WARN ").append(l).append("\n");
        }
        for (String l : LoggerSummary.INSTANCE.lastNInfos.get()) {
            sb.append("INFO ").append(l).append("\n");
        }
        sb.append("</pre><p>");

        return sb.toString();
    }

    private String progress(String title, int progress, String value) {
        Map<String, Object> data = new HashMap<>();
        data.put("title", title);
        data.put("progress", progress);
        data.put("value", value);
        return renderer.render("soy.page.amzaStackedProgress", data);
    }

    public static double getProcessCpuLoad() throws MalformedObjectNameException, ReflectionException, InstanceNotFoundException {

        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = ObjectName.getInstance("java.lang:type=OperatingSystem");
        AttributeList list = mbs.getAttributes(name, new String[] { "ProcessCpuLoad" });

        if (list.isEmpty()) {
            return Double.NaN;
        }

        Attribute att = (Attribute) list.get(0);
        Double value = (Double) att.getValue();

        if (value == -1.0) {
            return 0;  // usually takes a couple of seconds before we get real values
        }
        return ((int) (value * 1000) / 10.0);        // returns a percentage value with 1 decimal point precision
    }

    @Override
    public String getTitle() {
        return "Metrics";
    }

    public static String humanReadableByteCount(long bytes, boolean si) {
        int unit = si ? 1000 : 1024;
        if (bytes < unit) {
            return bytes + " B";
        }
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp - 1) + (si ? "" : "i");
        return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
    }

    public static String getDurationBreakdown(long millis) {
        if (millis < 0) {
            return String.valueOf(millis);
        }

        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        millis -= TimeUnit.HOURS.toMillis(hours);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis);
        millis -= TimeUnit.MINUTES.toMillis(minutes);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis);
        millis -= TimeUnit.SECONDS.toMillis(seconds);

        StringBuilder sb = new StringBuilder(64);
        boolean showRemaining = true;
        if (showRemaining || hours > 0) {
            if (hours < 10) {
                sb.append('0');
            }
            sb.append(hours);
            sb.append(":");
            showRemaining = true;
        }
        if (showRemaining || minutes > 0) {
            if (minutes < 10) {
                sb.append('0');
            }
            sb.append(minutes);
            sb.append(":");
            showRemaining = true;
        }
        if (showRemaining || seconds > 0) {
            if (seconds < 10) {
                sb.append('0');
            }
            sb.append(seconds);
            sb.append(".");
            showRemaining = true;
        }
        if (millis < 100) {
            sb.append('0');
        }
        if (millis < 10) {
            sb.append('0');
        }
        sb.append(millis);

        return (sb.toString());
    }

}
