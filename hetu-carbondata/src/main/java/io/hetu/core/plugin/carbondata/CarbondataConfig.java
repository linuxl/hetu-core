/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.hetu.core.plugin.carbondata;

import io.airlift.configuration.Config;
import io.prestosql.plugin.hive.HiveConfig;
import org.apache.carbondata.core.constants.CarbonCommonConstants;

import javax.validation.constraints.NotNull;

public class CarbondataConfig
        extends HiveConfig
{
    private String storeLocation = "";
    private long minorVacuumSegCount = Long.parseLong(CarbonCommonConstants.DEFAULT_SEGMENT_LEVEL_THRESHOLD.split(",")[0]);
    private long majorVacuumSegSize = 1L;
    private CarbondataStorageFormat carbondataStorageFormat = CarbondataStorageFormat.CARBON;

    @NotNull
    public String getStoreLocation()
    {
        return storeLocation;
    }

    @Config("carbondata.store-location")
    public CarbondataConfig setStoreLocation(String storeLocation)
    {
        this.storeLocation = storeLocation;
        return this;
    }

    @Config("carbondata.minor-compaction-seg-count")
    public CarbondataConfig setMinorVacuumSegCount(String minorVacuumSegCount)
    {
        this.minorVacuumSegCount = Long.parseLong(minorVacuumSegCount);
        return this;
    }

    @Config("carbondata.major-compaction-seg-size")
    public CarbondataConfig setMajorVacuumSegSize(String majorVacuumSegSize)
    {
        this.majorVacuumSegSize = Long.parseLong(majorVacuumSegSize);
        return this;
    }

    @NotNull
    public long getMinorVacuumSegCount()
    {
        return minorVacuumSegCount;
    }

    @NotNull
    public long getMajorVacuumSegSize()
    {
        return majorVacuumSegSize;
    }
}
