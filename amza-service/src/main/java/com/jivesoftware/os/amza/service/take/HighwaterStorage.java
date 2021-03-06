/*
 * Copyright 2013 Jive Software, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.jivesoftware.os.amza.service.take;

import com.jivesoftware.os.amza.api.partition.VersionedPartitionName;
import com.jivesoftware.os.amza.api.ring.RingMember;
import com.jivesoftware.os.amza.api.wal.WALHighwater;
import java.util.concurrent.Callable;

public interface HighwaterStorage {

    int LOCAL_NONE = -2;

    void clearRing(RingMember ringMember) throws Exception;

    void setIfLarger(RingMember ringMember,
        VersionedPartitionName versionedPartitionName,
        long highwaterTxId,
        int deltaIndex,
        int updates) throws Exception;

    void clear(RingMember ringMember, VersionedPartitionName versionedPartitionName) throws Exception;

    long get(RingMember ringMember, VersionedPartitionName versionedPartitionName) throws Exception;

    WALHighwater getPartitionHighwater(VersionedPartitionName versionedPartitionName, boolean includeLocal) throws Exception;

    boolean flush(int deltaIndex, boolean force, Callable<Void> preFlush) throws Exception;

    void delete(VersionedPartitionName versionedPartitionName) throws Exception;

    void setLocal(VersionedPartitionName versionedPartitionName, long txId) throws Exception;

    long getLocal(VersionedPartitionName versionedPartitionName) throws Exception;

    void flushLocal() throws Exception;
}
