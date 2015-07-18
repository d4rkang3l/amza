package com.jivesoftware.os.amza.lsm;

import com.google.common.primitives.UnsignedBytes;
import com.jivesoftware.os.amza.service.storage.filer.DiskBackedWALFiler;
import com.jivesoftware.os.amza.shared.filer.IReadable;
import com.jivesoftware.os.amza.shared.filer.UIO;
import com.jivesoftware.os.amza.shared.wal.WALKeyPointerStream;
import com.jivesoftware.os.amza.shared.wal.WALKeyPointers;
import java.io.File;
import java.io.IOException;

/**
 *
 * @author jonathan.colt
 */
public class LsmWalIndex implements ConcurrentReadableWalIndex, AppendableWalIndex {

    private static final int INDEX_ENTRY_SIZE = 8 + 4 + 8 + 1 + 8; // keyFp+ keyLength + timestamp + tombstone + walPointer

    private final DiskBackedWALFiler index;
    private final DiskBackedWALFiler keys;

    public LsmWalIndex(DiskBackedWALFiler index, DiskBackedWALFiler keys) {
        this.index = index;
        this.keys = keys;
    }

    @Override
    public void destroy() throws IOException {
        // TODO aquireAll
        index.close();
        keys.close();

        new File(index.getFileName()).delete();
        new File(keys.getFileName()).delete();
    }

    @Override
    public void append(WALKeyPointers pointerStream) throws Exception {
        keys.seek(keys.length());
        index.seek(index.length());

        pointerStream.consume((byte[] key, long timestamp, boolean tombstoned, long walPointer) -> {
            long keyFp = keys.getFilePointer();
            UIO.writeLong(index, keyFp, "keyFp");
            UIO.writeInt(index, key.length, "keyLength");
            UIO.writeLong(index, timestamp, "timestamp");
            UIO.writeBoolean(index, tombstoned, "tombstone");
            UIO.writeLong(index, walPointer, "walPointerFp");

            UIO.write(keys, key);
            return true;
        });
    }

    public static class WalPIndex implements ReadableWalIndex {

        private final int count;
        private final IReadable readableIndex;
        private final IReadable readableKeys;
        private final long[] offsetAndLength = new long[2];

        WalPIndex(int count, IReadable readableIndex, IReadable readableKeys) {
            this.count = count;
            this.readableIndex = readableIndex;
            this.readableKeys = readableKeys;
        }

        @Override
        public FeedNext getPointer(byte[] key) throws Exception {
            return binarySearch(0, count, key, null);
        }

        @Override
        public FeedNext rangeScan(byte[] from, byte[] to) throws Exception {
            return binarySearch(0, count, from, to);
        }

        @Override
        public FeedNext rowScan() throws Exception {
            int[] i = new int[]{0};
            return (stream) -> {
                if (i[0] < count) {
                    readableIndex.seek(i[0] * INDEX_ENTRY_SIZE);
                    long keyFp = UIO.readLong(readableIndex, "keyFp");
                    int keyLength = UIO.readInt(readableIndex, "keyLength");
                    long timestamp = UIO.readLong(readableIndex, "timestamp");
                    boolean tombstone = UIO.readBoolean(readableIndex, "tombstone");
                    long walPointerFp = UIO.readLong(readableIndex, "walPointerFp");

                    byte[] key = new byte[keyLength];
                    readableKeys.seek(keyFp);
                    readableKeys.read(key);
                    i[0]++;
                    return stream.stream(key, timestamp, tombstone, walPointerFp);
                } else {
                    return false;
                }
            };
        }

        public int count() {
            return count;
        }

        private void fillOffsetAndLength(int index) throws Exception {
            readableIndex.seek(index * INDEX_ENTRY_SIZE);
            offsetAndLength[0] = UIO.readLong(readableIndex, "keyFp");
            offsetAndLength[1] = UIO.readInt(readableIndex, "keyLength");
        }

        // TODO add reverse order support if toKey < fromKey
        private FeedNext binarySearch(int fromIndex,
            int toIndex,
            byte[] fromKey,
            byte[] toKey) throws Exception {

            int low = fromIndex;
            int high = toIndex - 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                fillOffsetAndLength(mid);
                int cmp = compare(fromKey);
                if (cmp < 0) {
                    low = mid + 1;
                } else if (cmp > 0) {
                    high = mid - 1;
                } else {
                    // return mid;  key found
                    if (toKey == null) {
                        int _mid = mid;
                        int[] i = new int[]{0};
                        return (stream) -> {
                            if (i[0] == 0) {
                                readableIndex.seek((_mid * INDEX_ENTRY_SIZE) + 8 + 4);
                                i[0]++;
                                return stream.stream(fromKey,
                                    UIO.readLong(readableIndex, "timestamp"),
                                    UIO.readBoolean(readableIndex, "tombstone"),
                                    UIO.readLong(readableIndex, "walPointerFp"));
                            } else {
                                return false;
                            }
                        };

                    } else {
                        int[] i = new int[]{0};
                        return (stream) -> {
                            if (mid + i[0] < count) {
                                boolean more = stream(mid + i[0], toKey, stream);
                                i[0]++;
                                return more;
                            } else {
                                return false;
                            }
                        };
                    }
                }
            }
            // return -(low + 1);  // key not found.
            if (toKey == null) {
                int[] i = new int[]{0};
                return (stream) -> {
                    if (i[0] == 0) {
                        i[0]++;
                        return stream.stream(fromKey, -1, false, -1);
                    } else {
                        return false;
                    }
                };
            } else {
                int _low = low;
                int[] i = new int[]{0};
                return (stream) -> {
                    if (_low + i[0] < count) {
                        boolean more = stream(_low + i[0], toKey, stream);
                        i[0]++;
                        return more;
                    } else {
                        return false;
                    }
                };
            }
        }

        private boolean stream(int i, byte[] stopKeyExclusive, WALKeyPointerStream stream) throws Exception {
            readableIndex.seek(i * INDEX_ENTRY_SIZE);
            long keyFp = UIO.readLong(readableIndex, "keyFp");
            int keyLength = UIO.readInt(readableIndex, "keyLength");
            long timestamp = UIO.readLong(readableIndex, "timestamp");
            boolean tombstone = UIO.readBoolean(readableIndex, "tombstone");
            long walPointerFp = UIO.readLong(readableIndex, "walPointerFp");
            byte[] key = new byte[keyLength];
            readableKeys.seek(keyFp);
            readableKeys.read(key);
            if (UnsignedBytes.lexicographicalComparator().compare(key, stopKeyExclusive) < 0) {
                return stream.stream(key, timestamp, tombstone, walPointerFp);
            } else {
                return false;
            }
        }

        // UnsighedBytes lex compare
        private int compare(byte[] right) throws IOException {
            readableKeys.seek(offsetAndLength[0]);
            byte[] left = new byte[(int) offsetAndLength[1]];
            readableKeys.read(left);

            int minLength = Math.min(left.length, right.length);
            for (int i = 0; i < minLength; i++) {
                int result = UnsignedBytes.compare(left[i], right[i]);
                if (result != 0) {
                    return result;
                }
            }
            return left.length - right.length;
        }
    }

    @Override
    public ReadableWalIndex concurrent() throws Exception {
        IReadable readableIndex = index.fileChannelFiler();
        IReadable readableKeys = keys.fileChannelFiler();
        return new WalPIndex((int) (readableIndex.length() / INDEX_ENTRY_SIZE), readableIndex, readableKeys);
    }
}
