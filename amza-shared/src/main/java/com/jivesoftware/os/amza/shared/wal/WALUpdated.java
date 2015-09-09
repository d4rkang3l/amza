package com.jivesoftware.os.amza.shared.wal;

import com.jivesoftware.os.amza.api.partition.VersionedPartitionName;
import com.jivesoftware.os.amza.aquarium.Waterline;

/**
 * @author jonathan.colt
 */
public interface WALUpdated {

    void updated(VersionedPartitionName versionedPartitionName, Waterline waterline, boolean isOnline, long txId) throws Exception;

}
