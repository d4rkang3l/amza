package com.jivesoftware.os.amza.api.stream;

/**
 *
 * @author jonathan.colt
 */
public interface TxKeyPointerStream {

    boolean stream(long txId,
        byte[] prefix,
        byte[] key,
        long timestamp,
        boolean tombstoned,
        long version,
        long fp) throws Exception;

}