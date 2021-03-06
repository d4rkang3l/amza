package com.jivesoftware.os.amza.client.http;

import com.google.common.primitives.UnsignedBytes;
import com.jivesoftware.os.amza.api.stream.KeyValueStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * @author jonathan.colt
 */
public class QuorumScanNGTest {

    @Test
    public void testSomeMethod() throws Exception {

        List<byte[]> outputPrefixes = new ArrayList<>();
        List<byte[]> outputKeys = new ArrayList<>();
        List<byte[]> outputValues = new ArrayList<>();
        List<Long> outputTimestamps = new ArrayList<>();
        List<Boolean> outputTombstones = new ArrayList<>();
        List<Long> outputVersions = new ArrayList<>();

        int numStreams = 10;

        KeyValueStream capture = (prefix, key, value, timestamp, tombstoned, version) -> {
            outputPrefixes.add(prefix);
            outputKeys.add(key);
            outputValues.add(value);
            outputTimestamps.add(timestamp);
            outputTombstones.add(tombstoned);
            outputVersions.add(version);

            System.out.println("\tOUTPUT -> prefix:" + null
                + " key:" + Arrays.toString(key)
                + " value:" + Arrays.toString(value)
                + " timestamp:" + timestamp
                + " tombstoned:" + tombstoned
                + " version:" + version);

            return true;
        };

        QuorumScan quorumScannable = new QuorumScan(numStreams);

        Random r = new Random();

        Set<Byte> expectedKeys = new HashSet<>();
        Map<Byte, ValueTimestampVersion> expectedTimestamp = new HashMap<>();

        int[] v = new int[numStreams];
        for (int i = 0; i < v.length; i++) {
            v[i] = i * 128;
        }

        int done = 0;
        byte[] lastKey = new byte[numStreams];
        while (done < numStreams) {
            for (int i = 0; i < lastKey.length; i++) {
                if (!quorumScannable.used(i)) {
                    continue;
                }

                int advance = (byte) (1 + r.nextInt(32));
                if (advance + lastKey[i] < 127) {
                    lastKey[i] += advance;
                    expectedKeys.add(lastKey[i]);

                    byte[] key = new byte[] { lastKey[i] };
                    byte[] value = new byte[] { (byte) r.nextInt(127) };
                    long timestamp = r.nextInt(numStreams);
                    boolean tombstoned = r.nextBoolean();
                    long version = v[i];

                    System.out.println("INPUT -> index:" + i + " prefix:" + null
                        + " key:" + Arrays.toString(key)
                        + " value:" + Arrays.toString(value)
                        + " timestamp:" + timestamp
                        + " tombstoned:" + tombstoned
                        + " version:" + version);

                    quorumScannable.fill(i, null, key, value, timestamp, tombstoned, version);

                    expectedTimestamp.compute(lastKey[i], (k, vv) -> {
                        if (vv == null) {
                            return new ValueTimestampVersion(value, timestamp, tombstoned, version);
                        } else {
                            int c = Long.compare(timestamp, vv.timestamp);
                            if (c == 0) {
                                c = Long.compare(version, vv.version);
                                if (c == 0) {
                                    throw new RuntimeException("Test bug");
                                } else if (c < 0) {
                                    return vv;
                                }
                            } else if (c < 0) {
                                return vv;
                            }
                            return new ValueTimestampVersion(value, timestamp, tombstoned, version);
                        }
                    });
                    v[i]++;

                } else {
                    if (lastKey[i] != 127) {
                        lastKey[i] = 127;
                        done++;
                    }
                }
            }
            int wi = quorumScannable.findWinningIndex();
            if (wi != -1) {
                if (!quorumScannable.stream(wi, capture)) {
                    throw new IllegalStateException("Shouldn't be false.");
                }
            }
        }
        int wi;
        while ((wi = quorumScannable.findWinningIndex()) > -1) {
            if (!quorumScannable.stream(wi, capture)) {
                throw new IllegalStateException("Shouldn't be false.");
            }
        }

        for (int j = 0; j < outputKeys.size(); j++) {
            System.out.println("OUTPUT -> prefix:" + Arrays.toString(outputPrefixes.get(j))
                + " key:" + Arrays.toString(outputKeys.get(j))
                + " value:" + Arrays.toString(outputValues.get(j))
                + " timestamp:" + outputTimestamps.get(j)
                + " version:" + outputVersions.get(j));

        }

        int i = 0;
        ValueTimestampVersion expected = expectedTimestamp.get(outputKeys.get(i)[0]);
        Assert.assertEquals(outputValues.get(i), expected.value);
        Assert.assertEquals((long) outputTimestamps.get(i), expected.timestamp);
        Assert.assertEquals((boolean) outputTombstones.get(i), expected.tombstoned);
        Assert.assertEquals((long) outputVersions.get(i), expected.version);
        Assert.assertTrue(expectedKeys.remove(outputKeys.get(i)[0]));
        i++;
        for (; i < outputKeys.size(); i++) {

            expected = expectedTimestamp.get(outputKeys.get(i)[0]);
            Assert.assertEquals(outputValues.get(i), expected.value);
            Assert.assertEquals((long) outputTimestamps.get(i), expected.timestamp);
            Assert.assertEquals((boolean) outputTombstones.get(i), expected.tombstoned);
            Assert.assertEquals((long) outputVersions.get(i), expected.version);
            Assert.assertTrue(expectedKeys.remove(outputKeys.get(i)[0]), "expectedKeys lacks key:" + outputKeys.get(i)[0]);

            Assert.assertTrue(UnsignedBytes.lexicographicalComparator().compare(outputKeys.get(i - 1), outputKeys.get(i)) < 0, "key ordering failed");
        }

        Assert.assertTrue(expectedKeys.isEmpty());

    }

    private static class ValueTimestampVersion {

        public final byte[] value;
        public final long timestamp;
        public final boolean tombstoned;
        public final long version;

        public ValueTimestampVersion(byte[] value, long timestamp, boolean tombstoned, long version) {
            this.value = value;
            this.timestamp = timestamp;
            this.tombstoned = tombstoned;
            this.version = version;
        }
    }
}
