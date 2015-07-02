package com.jivesoftware.os.amza.shared.wal;

import com.jivesoftware.os.amza.shared.partition.TxPartitionStatus.Status;
import com.jivesoftware.os.amza.shared.partition.VersionedPartitionName;

/**
 *
 * @author jonathan.colt
 */
public interface WALUpdated {

    void updated(VersionedPartitionName versionedPartitionName, Status status, long txId) throws Exception;

}
