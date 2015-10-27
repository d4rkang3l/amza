package com.jivesoftware.os.amza.lsm;

import com.google.common.primitives.UnsignedBytes;
import com.jivesoftware.os.amza.lsm.api.AppendablePointerIndex;
import com.jivesoftware.os.amza.lsm.api.ConcurrentReadablePointerIndex;
import com.jivesoftware.os.amza.lsm.api.NextPointer;
import com.jivesoftware.os.amza.lsm.api.Pointer;
import com.jivesoftware.os.amza.lsm.api.PointerStream;
import com.jivesoftware.os.amza.lsm.api.Pointers;
import com.jivesoftware.os.amza.lsm.api.ReadPointerIndex;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 *
 * @author jonathan.colt
 */
public class MemoryPointerIndex implements AppendablePointerIndex, ConcurrentReadablePointerIndex, Pointers {

    private final ConcurrentSkipListMap<byte[], Pointer> index = new ConcurrentSkipListMap<>(UnsignedBytes.lexicographicalComparator());

    @Override
    public boolean consume(PointerStream stream) throws Exception {
        for (Map.Entry<byte[], Pointer> e : index.entrySet()) {
            Pointer pointer = e.getValue();
            if (!stream.stream(pointer.sortIndex, e.getKey(), pointer.timestamp, pointer.tombstone, pointer.version, pointer.walFp)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void append(Pointers pointers) throws Exception {
        pointers.consume((sortIndex, key, timestamp, tombstoned, version, fp) -> {
            index.compute(key, (k, v) -> {
                if (v == null) {
                    return new Pointer(sortIndex, timestamp, tombstoned, version, fp);
                } else {
                    return v.timestamp > timestamp ? v : new Pointer(sortIndex, timestamp, tombstoned, version, fp);
                }
            });
            return true;
        });
    }

    @Override
    public ReadPointerIndex concurrent() throws Exception {
        return new ReadPointerIndex() {

            @Override
            public NextPointer getPointer(byte[] key) throws Exception {
                Pointer[] pointer = new Pointer[]{index.get(key)};
                return (stream) -> {
                    if (pointer[0] == null) {
                        return stream.stream(Integer.MIN_VALUE, key, -1, false, -1, -1);
                    } else {
                        boolean done = stream.stream(pointer[0].sortIndex,
                            key,
                            pointer[0].timestamp,
                            pointer[0].tombstone,
                            pointer[0].version,
                            pointer[0].walFp);
                        pointer[0] = null;
                        return done;
                    }
                };
            }

            @Override
            public NextPointer rangeScan(byte[] from, byte[] to) throws Exception {
                ConcurrentNavigableMap<byte[], Pointer> subMap = index.subMap(from, true, to, false);
                Iterator<Map.Entry<byte[], Pointer>> iterator = subMap.entrySet().iterator();
                return (stream) -> {
                    if (iterator.hasNext()) {
                        Map.Entry<byte[], Pointer> next = iterator.next();
                        Pointer pointer = next.getValue();
                        stream.stream(pointer.sortIndex, next.getKey(), pointer.timestamp, pointer.tombstone, pointer.version, pointer.walFp);
                        return true;
                    }
                    return false;
                };
            }

            @Override
            public NextPointer rowScan() throws Exception {
                Iterator<Map.Entry<byte[], Pointer>> iterator = index.entrySet().iterator();
                return (stream) -> {
                    if (iterator.hasNext()) {
                        Map.Entry<byte[], Pointer> next = iterator.next();
                        Pointer pointer = next.getValue();
                        stream.stream(pointer.sortIndex, next.getKey(), pointer.timestamp, pointer.tombstone, pointer.version, pointer.walFp);
                        return true;
                    }
                    return false;
                };
            }

            @Override
            public void close() {
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }

            @Override
            public long count() {
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }

            @Override
            public boolean isEmpty() {
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }
        };
    }

    @Override
    public void destroy() throws IOException {
        index.clear();
    }

    @Override
    public void close() {
        index.clear();
    }

    @Override
    public boolean isEmpty() {
        return index.isEmpty();
    }

    @Override
    public long count() {
        return index.size();
    }

    @Override
    public void flush() throws Exception {
    }
}
