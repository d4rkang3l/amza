package com.jivesoftware.os.amza.ui.region;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.jivesoftware.os.amza.service.AmzaRegion;
import com.jivesoftware.os.amza.service.AmzaService;
import com.jivesoftware.os.amza.shared.PrimaryIndexDescriptor;
import com.jivesoftware.os.amza.shared.RegionName;
import com.jivesoftware.os.amza.shared.RegionProperties;
import com.jivesoftware.os.amza.shared.RingHost;
import com.jivesoftware.os.amza.shared.WALKey;
import com.jivesoftware.os.amza.shared.WALStorageDescriptor;
import com.jivesoftware.os.amza.ui.soy.SoyRenderer;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 */
// soy.page.amzaStressPluginRegion
public class AmzaStressPluginRegion implements PageRegion<Optional<AmzaStressPluginRegion.AmzaStressPluginRegionInput>> {

    private static final MetricLogger log = MetricLoggerFactory.getLogger();

    private static final ConcurrentMap<String, Stress> STRESS_MAP = Maps.newConcurrentMap();

    private final String template;
    private final SoyRenderer renderer;
    private final AmzaService amzaService;

    public AmzaStressPluginRegion(String template,
        SoyRenderer renderer,
        AmzaService amzaService) {
        this.template = template;
        this.renderer = renderer;
        this.amzaService = amzaService;
    }

    public static class AmzaStressPluginRegionInput {

        final String name;
        final String regionPrefix;
        final int numBatches;
        final int batchSize;
        final int numRegions;
        final int numThreadsPerRegion;
        final String action;

        public AmzaStressPluginRegionInput(String name,
            String regionPrefix,
            int numBatches,
            int batchSize,
            int numRegions,
            int numThreadsPerRegion,
            String action) {
            this.name = name;
            this.regionPrefix = regionPrefix;
            this.numBatches = numBatches;
            this.batchSize = batchSize;
            this.numRegions = numRegions;
            this.numThreadsPerRegion = numThreadsPerRegion;
            this.action = action;
        }
    }

    @Override
    public String render(Optional<AmzaStressPluginRegionInput> optionalInput) {
        Map<String, Object> data = Maps.newHashMap();

        try {
            AmzaStressPluginRegionInput input = optionalInput.orNull();
            if (input != null && !input.name.isEmpty() && !input.regionPrefix.isEmpty()) {

                if (input.action.equals("start") || input.action.equals("stop")) {
                    Stress stress = STRESS_MAP.remove(input.name);
                    log.info("Removed {}", input.name);
                    if (stress != null) {
                        boolean stopped = stress.stop(10, TimeUnit.SECONDS);
                        data.put("stopped", stopped);
                    }
                }
                if (input.action.equals("start")) {
                    ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 10);
                    Stress stress = new Stress(System.currentTimeMillis(), executor, new AtomicLong(0), input);
                    Stress existing = STRESS_MAP.putIfAbsent(input.name, stress);
                    log.info("Added {}", input.name);
                    if (existing == null) {
                        stress.start();
                    }
                }
            }

            List<Map<String, String>> rows = new ArrayList<>();
            for (Map.Entry<String, Stress> entry : STRESS_MAP.entrySet()) {
                Stress stress = entry.getValue();
                long added = stress.added.get();
                long endTimeMillis = stress.endTimeMillis.get();
                if (endTimeMillis < 0) {
                    endTimeMillis = System.currentTimeMillis();
                }
                long elapsed = endTimeMillis - stress.startTimeMillis;

                Map<String, String> row = new HashMap<>();
                row.put("name", entry.getKey());
                row.put("regionPrefix", stress.input.regionPrefix);
                row.put("numBatches", String.valueOf(stress.input.numBatches));
                row.put("batchSize", String.valueOf(stress.input.batchSize));
                row.put("numRegions", String.valueOf(stress.input.numRegions));
                row.put("numThreadsPerRegion", String.valueOf(stress.input.numThreadsPerRegion));

                row.put("elapsed", HealthPluginRegion.getDurationBreakdown(elapsed));
                row.put("added", String.valueOf(added));
                row.put("addedPerSecond", String.valueOf((double) added * 1000 / (double) elapsed));
                rows.add(row);
            }

            data.put("stress", rows);

        } catch (Exception e) {
            log.error("Unable to retrieve data", e);
        }

        return renderer.render(template, data);
    }

    private class Stress {

        final long startTimeMillis;
        final ExecutorService executor;
        final AtomicLong added;
        final AmzaStressPluginRegionInput input;
        final AtomicInteger completed = new AtomicInteger(0);
        final AtomicLong endTimeMillis = new AtomicLong(-1);
        final AtomicBoolean forcedStop = new AtomicBoolean(false);

        public Stress(long startTimeMillis, ExecutorService executor, AtomicLong added, AmzaStressPluginRegionInput input) {
            this.startTimeMillis = startTimeMillis;
            this.executor = executor;
            this.added = added;
            this.input = input;
        }

        private void start() throws Exception {

            for (int i = 0; i < input.numRegions; i++) {
                String regionName = input.regionPrefix + i;
                for (int j = 0; j < input.numThreadsPerRegion; j++) {
                    executor.submit(new Feeder(regionName, j));
                }
            }
        }

        private class Feeder implements Runnable {

            AtomicInteger batch = new AtomicInteger();
            private final String regionName;
            private final int threadIndex;

            public Feeder(String regionName, int threadIndex) {
                this.regionName = regionName;
                this.threadIndex = threadIndex;
            }

            @Override
            public void run() {
                try {
                    int b = batch.incrementAndGet();
                    if (b <= input.numBatches && !forcedStop.get()) {
                        feed(regionName, b, threadIndex);
                        executor.submit(this);
                    } else {
                        completed();
                    }
                } catch (Exception x) {
                    x.printStackTrace();
                }
            }
        }

        private void completed() {
            int completed = this.completed.incrementAndGet();
            if (completed == input.numRegions * input.numThreadsPerRegion) {
                endTimeMillis.set(System.currentTimeMillis());
            }
        }

        private boolean stop(long timeout, TimeUnit unit) throws InterruptedException {
            forcedStop.set(true);
            executor.shutdownNow();
            return executor.awaitTermination(timeout, unit);
        }

        private void feed(String regionName, int batch, int threadIndex) throws Exception {
            AmzaRegion amzaRegion = createRegionIfAbsent(regionName);

            Map<String, String> values = new LinkedHashMap<>();
            int bStart = threadIndex * input.batchSize;
            int bEnd = bStart + input.batchSize;
            for (int b = bStart; b < bEnd; b++) {
                values.put(b + "k" + batch, b + "v" + batch);
            }

            try {
                amzaRegion.set(Iterables.transform(values.entrySet(), new Function<Map.Entry<String, String>, Map.Entry<WALKey, byte[]>>() {
                    @Override
                    public Map.Entry<WALKey, byte[]> apply(Map.Entry<String, String> input) {
                        return new AbstractMap.SimpleEntry<>(new WALKey(input.getKey().getBytes()), input.getValue().getBytes());
                    }
                }));
            } catch (Exception x) {
                log.warn("Failed to set region:" + regionName + " values:" + values, x);
            }

            added.addAndGet(input.batchSize);

        }
    }

    private AmzaRegion createRegionIfAbsent(String regionName) throws Exception {

        List<RingHost> ring = amzaService.getAmzaRing().getRing("default");
        if (ring.isEmpty()) {
            amzaService.getAmzaRing().buildRandomSubRing("default", amzaService.getAmzaRing().getRing("system").size());
        }

        WALStorageDescriptor storageDescriptor = new WALStorageDescriptor(new PrimaryIndexDescriptor("berkeleydb", 0, false, null),
            null, 1000, 1000);

        return amzaService.createRegionIfAbsent(new RegionName(false, "default", regionName),
            new RegionProperties(storageDescriptor, 1, 1, false)
        );
    }

    @Override
    public String getTitle() {
        return "Amza Stress";
    }

}
