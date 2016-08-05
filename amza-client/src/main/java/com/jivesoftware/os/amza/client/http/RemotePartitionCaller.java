package com.jivesoftware.os.amza.client.http;

import com.jivesoftware.os.amza.api.partition.Consistency;
import com.jivesoftware.os.amza.api.ring.RingMember;
import com.jivesoftware.os.amza.api.stream.ClientUpdates;
import com.jivesoftware.os.amza.api.stream.PrefixedKeyRanges;
import com.jivesoftware.os.amza.api.stream.TxKeyValueStream;
import com.jivesoftware.os.amza.api.stream.UnprefixedWALKeys;
import java.util.Map;

/**
 *
 * @author jonathan.colt
 */
public interface RemotePartitionCaller<C, E extends Throwable> {

    PartitionResponse<NoOpCloseable> commit(RingMember leader,
        RingMember ringMember,
        C client,
        Consistency consistency,
        byte[] prefix,
        ClientUpdates updates,
        long abandonSolutionAfterNMillis) throws E;

    PartitionResponse<CloseableStreamResponse> get(RingMember leader,
        RingMember ringMember,
        C client,
        Consistency consistency,
        byte[] prefix,
        UnprefixedWALKeys keys) throws E;

    PartitionResponse<CloseableStreamResponse> scan(RingMember leader,
        RingMember ringMember,
        C client,
        Consistency consistency,
        boolean compressed,
        PrefixedKeyRanges ranges,
        boolean hydrateValues) throws E;

    PartitionResponse<CloseableStreamResponse> takeFromTransactionId(RingMember leader,
        RingMember ringMember,
        C client,
        Map<RingMember, Long> membersTxId,
        TxKeyValueStream stream) throws E;

    PartitionResponse<CloseableStreamResponse> takePrefixFromTransactionId(RingMember leader,
        RingMember ringMember,
        C client,
        byte[] prefix,
        Map<RingMember, Long> membersTxId,
        TxKeyValueStream stream) throws E;

    PartitionResponse<CloseableLong> getApproximateCount(RingMember leader,
        RingMember ringMember,
        C client) throws E;
}
