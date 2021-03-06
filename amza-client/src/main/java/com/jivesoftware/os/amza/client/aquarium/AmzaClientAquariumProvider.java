package com.jivesoftware.os.amza.client.aquarium;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.jivesoftware.os.amza.api.PartitionClientProvider;
import com.jivesoftware.os.amza.api.filer.UIO;
import com.jivesoftware.os.aquarium.Aquarium;
import com.jivesoftware.os.aquarium.AquariumStats;
import com.jivesoftware.os.aquarium.Liveliness;
import com.jivesoftware.os.aquarium.LivelyEndState;
import com.jivesoftware.os.aquarium.Member;
import com.jivesoftware.os.aquarium.Waterline;
import com.jivesoftware.os.aquarium.interfaces.AtQuorum;
import com.jivesoftware.os.aquarium.interfaces.AwaitLivelyEndState;
import com.jivesoftware.os.aquarium.interfaces.CurrentMembers;
import com.jivesoftware.os.aquarium.interfaces.TransitionQuorum;
import com.jivesoftware.os.jive.utils.ordered.id.OrderIdProvider;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 */
public class AmzaClientAquariumProvider {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private static final byte CURRENT = 0;
    private static final byte DESIRED = 1;

    private static final TransitionQuorum CURRENT_TRANSITION_QUORUM =
        (existing, nextTimestamp, nextState, readCurrent, readDesired, writeCurrent, writeDesired) -> {
            return writeCurrent.put(existing.getMember(), nextState, nextTimestamp);
        };
    private static final TransitionQuorum DESIRED_TRANSITION_QUORUM =
        (existing, nextTimestamp, nextState, readCurrent, readDesired, writeCurrent, writeDesired) -> {
            return writeDesired.put(existing.getMember(), nextState, nextTimestamp);
        };

    private final AquariumStats aquariumStats;
    private final String serviceName;
    private final PartitionClientProvider partitionClientProvider;
    private final OrderIdProvider orderIdProvider;
    private final Member member;
    private final AtQuorum atQuorum;
    private final CurrentMembers currentMembers;
    private final Liveliness liveliness;
    private final int aquariumStateStripes;
    private final long heartbeatEveryNMillis;
    private final long pushOnlineEveryNMillis;
    private final long checkLeadershipEveryNMillis;
    private final ExecutorService livelinessExecutorService;

    private final long additionalSolverAfterNMillis;
    private final long abandonLeaderSolutionAfterNMillis;
    private final long abandonSolutionAfterNMillis;
    private final boolean useSolutionLog;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, ClientAquarium> aquariums = Maps.newConcurrentMap();
    private final Map<String, TimestampedLivelyEndState> livelyEndStates = Maps.newConcurrentMap();
    private final Set<String> names = new CopyOnWriteArraySet<>();

    private final AwaitLivelyEndState awaitLivelyEndState = new AwaitLivelyEndState() {
        @Override
        public LivelyEndState awaitChange(Callable<LivelyEndState> awaiter, long timeoutMillis) throws Exception {
            return awaiter.call();
        }

        @Override
        public void notifyChange(Callable<Boolean> change) throws Exception {
            change.call();
        }
    };

    public AmzaClientAquariumProvider(AquariumStats aquariumStats,
        String serviceName,
        PartitionClientProvider partitionClientProvider,
        OrderIdProvider orderIdProvider,
        Member member,
        AtQuorum atQuorum,
        CurrentMembers currentMembers,
        int aquariumStateStripes,
        int aquariumLivelinessStripes,
        long heartbeatEveryNMillis,
        long pushOnlineEveryNMillis,
        long deadAfterNMillis,
        long checkLeadershipEveryNMillis,
        ExecutorService executorService,
        long additionalSolverAfterNMillis,
        long abandonLeaderSolutionAfterNMillis,
        long abandonSolutionAfterNMillis,
        boolean useSolutionLog) throws Exception {

        this.aquariumStats = aquariumStats;
        this.serviceName = serviceName;
        this.partitionClientProvider = partitionClientProvider;
        this.orderIdProvider = orderIdProvider;
        this.member = member;
        this.atQuorum = atQuorum;
        this.currentMembers = currentMembers;
        this.aquariumStateStripes = aquariumStateStripes;
        this.heartbeatEveryNMillis = heartbeatEveryNMillis;
        this.pushOnlineEveryNMillis = pushOnlineEveryNMillis;
        this.checkLeadershipEveryNMillis = checkLeadershipEveryNMillis;
        this.livelinessExecutorService = executorService;
        this.additionalSolverAfterNMillis = additionalSolverAfterNMillis;
        this.abandonLeaderSolutionAfterNMillis = abandonLeaderSolutionAfterNMillis;
        this.abandonSolutionAfterNMillis = abandonSolutionAfterNMillis;
        this.useSolutionLog = useSolutionLog;

        AmzaClientLivelinessStorage livelinessStorage = new AmzaClientLivelinessStorage(partitionClientProvider,
            serviceName,
            livelinessContext(serviceName),
            member,
            orderIdProvider.nextId(),
            aquariumLivelinessStripes,
            additionalSolverAfterNMillis,
            abandonLeaderSolutionAfterNMillis,
            abandonSolutionAfterNMillis,
            useSolutionLog);
        this.liveliness = new Liveliness(aquariumStats,
            System::currentTimeMillis,
            livelinessStorage,
            member,
            atQuorum,
            deadAfterNMillis,
            new AtomicLong(-1));
    }

    public void start() {
        running.set(true);
        livelinessExecutorService.submit(() -> {
            while (running.get()) {
                try {
                    liveliness.feedTheFish();

                    boolean allOnline = true;
                    for (String name : names) {
                        TimestampedLivelyEndState timestampedLivelyEndState = livelyEndStates.get(name);
                        if (timestampedLivelyEndState == null || !isRecent(timestampedLivelyEndState) || !hasLeader(timestampedLivelyEndState)) {
                            if (timestampedLivelyEndState == null || !hasLeader(timestampedLivelyEndState)) {
                                allOnline = false;
                            }

                            LivelyEndState livelyEndState = updateAndFetchLivelyEndState(name);
                            timestampedLivelyEndState = new TimestampedLivelyEndState(livelyEndState, System.currentTimeMillis());
                            livelyEndStates.put(name, timestampedLivelyEndState);
                        }
                    }

                    Thread.sleep(allOnline ? heartbeatEveryNMillis : pushOnlineEveryNMillis);
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable t) {
                    LOG.error("Failed to heartbeat", t);
                    Thread.sleep(1000L);
                }
            }
            return null;
        });
    }

    public void stop() {
        running.set(false);
        livelinessExecutorService.shutdownNow();
    }

    private boolean isRecent(TimestampedLivelyEndState timestampedLivelyEndState) throws Exception {
        if (timestampedLivelyEndState.timestamp < (System.currentTimeMillis() - checkLeadershipEveryNMillis)) {
            return false;
        }
        return true;
    }

    private boolean hasLeader(TimestampedLivelyEndState timestampedLivelyEndState) throws Exception {
        if (!timestampedLivelyEndState.livelyEndState.isOnline()) {
            return false;
        }

        Waterline leader = timestampedLivelyEndState.livelyEndState.getLeaderWaterline();
        if (leader == null || !leader.isAtQuorum()) {
            return false;
        }

        return true;
    }

    public void register(String name) {
        names.add(name);
    }

    public LivelyEndState livelyEndState(String name) throws Exception {
        TimestampedLivelyEndState timestampedLivelyEndState = livelyEndStates.get(name);
        return timestampedLivelyEndState != null ? timestampedLivelyEndState.livelyEndState : null;
    }

    private static class TimestampedLivelyEndState {
        private final LivelyEndState livelyEndState;
        private final long timestamp;

        public TimestampedLivelyEndState(LivelyEndState livelyEndState, long timestamp) {
            this.livelyEndState = livelyEndState;
            this.timestamp = timestamp;
        }
    }

    private LivelyEndState updateAndFetchLivelyEndState(String name) throws Exception {
        ClientAquarium clientAquarium = aquariums.computeIfAbsent(name, s -> {
            try {
                AmzaClientStateStorage currentStateStorage = currentStateStorage(name);
                AmzaClientStateStorage desiredStateStorage = desiredStateStorage(name);
                return new ClientAquarium(
                    new Aquarium(aquariumStats,
                        orderIdProvider,
                        currentStateStorage,
                        desiredStateStorage,
                        CURRENT_TRANSITION_QUORUM,
                        DESIRED_TRANSITION_QUORUM,
                        liveliness,
                        member1 -> 0L,
                        Long.class,
                        atQuorum,
                        currentMembers,
                        member,
                        awaitLivelyEndState),
                    currentStateStorage,
                    desiredStateStorage);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        try {
            clientAquarium.init();
            clientAquarium.aquarium.acknowledgeOther();
            clientAquarium.aquarium.tapTheGlass();
            return clientAquarium.aquarium.livelyEndState();
        } finally {
            clientAquarium.reset();
        }
    }

    private AmzaClientStateStorage currentStateStorage(String name) throws Exception {
        return new AmzaClientStateStorage(partitionClientProvider,
            serviceName,
            stateContext(serviceName, name, CURRENT),
            aquariumStateStripes,
            additionalSolverAfterNMillis,
            abandonLeaderSolutionAfterNMillis,
            abandonSolutionAfterNMillis,
            useSolutionLog);
    }

    private AmzaClientStateStorage desiredStateStorage(String name) throws Exception {
        return new AmzaClientStateStorage(partitionClientProvider,
            serviceName,
            stateContext(serviceName, name, DESIRED),
            aquariumStateStripes,
            additionalSolverAfterNMillis,
            abandonLeaderSolutionAfterNMillis,
            abandonSolutionAfterNMillis,
            useSolutionLog);
    }

    private static byte[] stateContext(String serviceName, String name, byte contextType) {
        byte[] nameBytes = (serviceName + "-" + name).getBytes(StandardCharsets.UTF_8);
        Preconditions.checkArgument(nameBytes.length < 256, "Service and aquarium name must be shorter than 256 bytes");
        byte[] context = new byte[2 + nameBytes.length + 1];
        UIO.unsignedShortBytes(nameBytes.length, context, 0);
        System.arraycopy(nameBytes, 0, context, 2, nameBytes.length);
        context[2 + nameBytes.length] = contextType;
        return context;
    }

    private static byte[] livelinessContext(String serviceName) {
        byte[] nameBytes = serviceName.getBytes(StandardCharsets.UTF_8);
        Preconditions.checkArgument(nameBytes.length < 256, "Service name must be shorter than 256 bytes");
        byte[] context = new byte[2 + nameBytes.length];
        UIO.unsignedShortBytes(nameBytes.length, context, 0);
        System.arraycopy(nameBytes, 0, context, 2, nameBytes.length);
        return context;
    }

    private static class ClientAquarium {
        private final Aquarium aquarium;
        private final AmzaClientStateStorage currentStateStorage;
        private final AmzaClientStateStorage desiredStateStorage;

        private ClientAquarium(Aquarium aquarium,
            AmzaClientStateStorage currentStateStorage,
            AmzaClientStateStorage desiredStateStorage) {
            this.aquarium = aquarium;
            this.currentStateStorage = currentStateStorage;
            this.desiredStateStorage = desiredStateStorage;
        }

        private void reset() {
            currentStateStorage.reset();
            desiredStateStorage.reset();
        }

        public void init() throws Exception {
            currentStateStorage.init();
            desiredStateStorage.init();
        }
    }

}
