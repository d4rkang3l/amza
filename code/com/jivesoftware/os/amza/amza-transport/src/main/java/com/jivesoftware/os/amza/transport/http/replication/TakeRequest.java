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
package com.jivesoftware.os.amza.transport.http.replication;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.jivesoftware.os.amza.shared.RegionName;

public class TakeRequest {

    private final long highestTransactionId;
    private final RegionName regionName;

    @JsonCreator
    public TakeRequest(
        @JsonProperty("highestTransactionId") long highestTransactionId,
        @JsonProperty("tableName") RegionName regionName) {
        this.highestTransactionId = highestTransactionId;
        this.regionName = regionName;
    }

    public long getHighestTransactionId() {
        return highestTransactionId;
    }

    public RegionName getRegionName() {
        return regionName;
    }

    @Override
    public String toString() {
        return "TakeRequest{" + "highestTransactionId=" + highestTransactionId + ", regionName=" + regionName + '}';
    }
}
