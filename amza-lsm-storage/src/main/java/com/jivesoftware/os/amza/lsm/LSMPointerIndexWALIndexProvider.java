package com.jivesoftware.os.amza.lsm;

import com.jivesoftware.os.amza.api.partition.VersionedPartitionName;
import com.jivesoftware.os.amza.shared.AmzaVersionConstants;
import com.jivesoftware.os.amza.shared.wal.WALIndexProvider;
import java.io.File;

/**
 *
 */
public class LSMPointerIndexWALIndexProvider implements WALIndexProvider<LSMPointerIndexWALIndex> {

    private final LSMPointerIndexEnvironment[] environments;

    public LSMPointerIndexWALIndexProvider(String[] baseDirs, int stripes) {
        this.environments = new LSMPointerIndexEnvironment[stripes];
        for (int i = 0; i < environments.length; i++) {
            File active = new File(
                new File(
                    new File(baseDirs[i % baseDirs.length], AmzaVersionConstants.LATEST_VERSION),
                    "lsm"),
                String.valueOf(i));
            if (!active.exists() && !active.mkdirs()) {
                throw new RuntimeException("Failed while trying to mkdirs for " + active);
            }
            this.environments[i] = new LSMPointerIndexEnvironment(active);
        }
    }

    @Override
    public LSMPointerIndexWALIndex createIndex(VersionedPartitionName versionedPartitionName) throws Exception {
        LSMPointerIndexWALIndexName name = new LSMPointerIndexWALIndexName(LSMPointerIndexWALIndexName.Type.active, versionedPartitionName.toBase64());
        return new LSMPointerIndexWALIndex(environments[Math.abs(versionedPartitionName.hashCode() % environments.length)], name);
    }

    @Override
    public void deleteIndex(VersionedPartitionName versionedPartitionName) throws Exception {
        LSMPointerIndexWALIndexName name = new LSMPointerIndexWALIndexName(LSMPointerIndexWALIndexName.Type.active, versionedPartitionName.toBase64());
        for (LSMPointerIndexWALIndexName n : name.all()) {
            environments[Math.abs(versionedPartitionName.hashCode() % environments.length)].remove(n.getPrimaryName());
            environments[Math.abs(versionedPartitionName.hashCode() % environments.length)].remove(n.getPrefixName());
        }
    }

}
