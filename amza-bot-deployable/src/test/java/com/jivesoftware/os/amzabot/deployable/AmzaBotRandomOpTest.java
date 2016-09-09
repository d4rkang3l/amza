package com.jivesoftware.os.amzabot.deployable;

import com.jivesoftware.os.amza.api.PartitionClient;
import com.jivesoftware.os.amza.api.PartitionClientProvider;
import com.jivesoftware.os.amza.api.partition.PartitionName;
import com.jivesoftware.os.amza.api.partition.PartitionProperties;
import com.jivesoftware.os.amza.api.wal.KeyUtil;
import com.jivesoftware.os.amza.client.test.InMemoryPartitionClient;
import com.jivesoftware.os.amzabot.deployable.bot.AmzaBotRandomOpConfig;
import com.jivesoftware.os.amzabot.deployable.bot.AmzaBotRandomOpService;
import com.jivesoftware.os.amzabot.deployable.bot.AmzaBotRandomOpServiceInitializer;
import com.jivesoftware.os.jive.utils.ordered.id.ConstantWriterIdProvider;
import com.jivesoftware.os.jive.utils.ordered.id.JiveEpochTimestampProvider;
import com.jivesoftware.os.jive.utils.ordered.id.OrderIdProviderImpl;
import com.jivesoftware.os.jive.utils.ordered.id.SnowflakeIdPacker;
import com.jivesoftware.os.mlogger.core.AtomicCounter;
import com.jivesoftware.os.mlogger.core.ValueType;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import org.merlin.config.BindInterfaceToConfiguration;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class AmzaBotRandomOpTest {

    private AmzaBotRandomOpService service;
    private AmzaKeyClearingHouse amzaKeyClearingHouse;
    private AmzaBotRandomOpConfig config;

    @BeforeMethod
    public void setUp() throws Exception {
        OrderIdProviderImpl orderIdProvider = new OrderIdProviderImpl(new ConstantWriterIdProvider(1),
            new SnowflakeIdPacker(),
            new JiveEpochTimestampProvider());

        Map<PartitionName, PartitionClient> indexes = new ConcurrentHashMap<>();
        PartitionClientProvider partitionClientProvider = new PartitionClientProvider() {
            @Override
            public PartitionClient getPartition(PartitionName partitionName) throws Exception {
                return indexes.computeIfAbsent(partitionName,
                    partitionName1 -> new InMemoryPartitionClient(new ConcurrentSkipListMap<>(KeyUtil.lexicographicalComparator()), orderIdProvider));
            }

            @Override
            public PartitionClient getPartition(PartitionName partitionName, int desiredRingSize, PartitionProperties partitionProperties) throws Exception {
                return getPartition(partitionName);
            }
        };

        AmzaBotConfig amzaBotConfig = BindInterfaceToConfiguration.bindDefault(AmzaBotConfig.class);

        config = BindInterfaceToConfiguration.bindDefault(AmzaBotRandomOpConfig.class);
        config.setEnabled(false);
        config.setHesitationFactorMs(100);
        config.setWriteThreshold(100L);
        config.setValueSizeThreshold(20);
        config.setDurability("fsync_async");
        config.setConsistency("leader_quorum");
        config.setPartitionSize(1);
        config.setRetryWaitMs(100);
        config.setSnapshotFrequency(10);

        amzaKeyClearingHouse = new AmzaKeyClearingHouse();
        service = new AmzaBotRandomOpServiceInitializer(
            amzaBotConfig,
            config,
            partitionClientProvider,
            amzaKeyClearingHouse).initialize();
        service.start();
    }

    @AfterMethod
    public void tearDown() throws Exception {
        service.stop();
    }

    @Test
    public void testWaitOnProcessor() throws InterruptedException {
        Thread.sleep(1_000);

        System.out.println("Outstanding key count: " +
            amzaKeyClearingHouse.getKeyMap().size());

        Assert.assertEquals(amzaKeyClearingHouse.getQuarantinedKeyMap().size(), 0);

        service.clearKeyMap();
        service.clearQuarantinedKeyMap();

        // success
    }

    @Test
    public void testWRDRandomOp() throws Exception {
        boolean write, read, delete;
        write = read = delete = false;

        AtomicCounter seq = new AtomicCounter(ValueType.COUNT);

        while (!write || !read || !delete) {
            int op = service.randomOp("test:" + String.valueOf(seq.getValue()));

            if (op == 0) {
                read = true;
            } else if (op == 1) {
                delete = true;
            } else {
                write = true;
            }

            seq.inc();
        }

        Assert.assertEquals(amzaKeyClearingHouse.getQuarantinedKeyMap().size(), 0);

        service.clearKeyMap();
        service.clearQuarantinedKeyMap();

        // success
    }

    @Test
    public void testMultipleRandomOps() throws Exception {
        AtomicCounter seq = new AtomicCounter(ValueType.COUNT);
        for (int i = 0; i < 10; i++) {
            service.randomOp("test:" + String.valueOf(seq.getValue()));

            if (config.getHesitationFactorMs() > 0) {
                Thread.sleep(new Random().nextInt(config.getHesitationFactorMs()));
            }

            seq.inc();
        }

        System.out.println("Outstanding key count: " +
            amzaKeyClearingHouse.getKeyMap().size());

        Assert.assertEquals(amzaKeyClearingHouse.getQuarantinedKeyMap().size(), 0);

        service.clearKeyMap();
        service.clearQuarantinedKeyMap();

        // success
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void validateInvalidRandomRange() throws Exception {
        Assert.assertEquals(new Random().nextInt(0), 0);
    }

    @Test
    public void validateRandomRange() throws Exception {
        Assert.assertEquals(new Random().nextInt(1), 0);
    }

}