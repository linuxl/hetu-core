/*
 * Copyright (C) 2018-2020. Huawei Technologies Co., Ltd. All rights reserved.
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

package io.hetu.core.statestore.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.config.DiscoveryStrategyConfig;
import com.hazelcast.config.EvictionConfig;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MaxSizePolicy;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.config.SerializerConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import io.airlift.slice.Slice;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.statestore.CipherService;
import io.prestosql.spi.statestore.StateStoreBootstrapper;

import java.util.Collection;
import java.util.Map;

import static io.hetu.core.statestore.Constants.STATE_STORE_CLUSTER_CONFIG_NAME;
import static io.hetu.core.statestore.StateStoreUtils.getEncryptionTypeFromConfig;
import static io.hetu.core.statestore.hazelcast.HazelcastConstants.DEFAULT_CLUSTER_ID;
import static io.hetu.core.statestore.hazelcast.HazelcastConstants.DEFAULT_DISCOVERY_PORT;
import static io.hetu.core.statestore.hazelcast.HazelcastConstants.DISCOVERY_ENABLED;
import static io.hetu.core.statestore.hazelcast.HazelcastConstants.DISCOVERY_MODE_CONFIG_NAME;
import static io.hetu.core.statestore.hazelcast.HazelcastConstants.DISCOVERY_MODE_MULTICAST;
import static io.hetu.core.statestore.hazelcast.HazelcastConstants.DISCOVERY_MODE_TCPIP;
import static io.hetu.core.statestore.hazelcast.HazelcastConstants.DISCOVERY_MULTICAST_STRATEGY_CLASS_NAME;
import static io.hetu.core.statestore.hazelcast.HazelcastConstants.DISCOVERY_PORT_CONFIG_NAME;
import static io.hetu.core.statestore.hazelcast.HazelcastConstants.MINIMUM_CP_MEMBER_COUNT;
import static io.prestosql.spi.StandardErrorCode.CONFIGURATION_INVALID;
import static io.prestosql.spi.StandardErrorCode.STATE_STORE_FAILURE;

/**
 * State store bootstrapper for Hazelcast to bootstrap Hazelcast member instance
 *
 * @since 2020-03-06
 */
public class HazelcastStateStoreBootstrapper
        implements StateStoreBootstrapper
{
    private HazelcastInstance hzInstance;
    private static final String MERGEMAP = "merged";
    private static final int MAXIDLESECONDS = 30;
    private static final int EVICTIONSIZE = 200;
    private static final int TIMETOLIVESECONDS = 300;

    @Override
    public HazelcastStateStore bootstrap(Collection<String> locations, Map<String, String> config)
    {
        // Initialize the Hazelcast instance and discovery service
        final String discoveryMode = config.get(DISCOVERY_MODE_CONFIG_NAME);

        Config hzConfig = new Config();
        // Config hazelcast cluster name

        // Add serialization for Slice
        SerializerConfig sc = new SerializerConfig().setImplementation(new HazelCastSliceSerializer()).setTypeClass(Slice.class);
        hzConfig.getSerializationConfig().addSerializerConfig(sc);

        String clusterId = config.get(STATE_STORE_CLUSTER_CONFIG_NAME);
        if (clusterId == null) {
            clusterId = DEFAULT_CLUSTER_ID;
        }
        hzConfig.setClusterName(clusterId);
        // Config CP system
        hzConfig = setCpSystemConfigs(config, hzConfig);

        // Set eviction rules
        hzConfig = setEvictionConfigs(hzConfig, MERGEMAP);

        // Set discovery port
        hzConfig = setPortConfigs(config, hzConfig);

        // default discovery_mode = multicast
        if (discoveryMode == null || discoveryMode.equals(DISCOVERY_MODE_MULTICAST)) {
            hzConfig.setProperty(DISCOVERY_ENABLED, "true");
            JoinConfig join = hzConfig.getNetworkConfig().getJoin();
            join.getMulticastConfig().setEnabled(false);
            join.getTcpIpConfig().setEnabled(false);
            DiscoveryStrategyConfig strategy = new DiscoveryStrategyConfig(DISCOVERY_MULTICAST_STRATEGY_CLASS_NAME);
            join.getDiscoveryConfig().addDiscoveryStrategyConfig(strategy);

            hzInstance = Hazelcast.newHazelcastInstance(hzConfig);
        }
        else if (discoveryMode.equals(DISCOVERY_MODE_TCPIP)) {
            if (locations == null || locations.isEmpty()) {
                throw new PrestoException(STATE_STORE_FAILURE, "Using TCP-IP discovery but no seed ip found."
                        + "Please check whether seed store is enabled");
            }
            // Hardcode seed IP for testing
            NetworkConfig network = hzConfig.getNetworkConfig();

            JoinConfig join = network.getJoin();
            join.getAwsConfig().setEnabled(false);
            join.getMulticastConfig().setEnabled(false);
            join.getTcpIpConfig().setEnabled(true)
                    .addMember(String.join(",", locations));

            hzInstance = Hazelcast.newHazelcastInstance(hzConfig);
        }
        else {
            throw new PrestoException(CONFIGURATION_INVALID, "Discovery mode not supported: " + discoveryMode);
        }
        if (hzInstance == null) {
            throw new PrestoException(STATE_STORE_FAILURE, "Hazelcast state store bootstrap failed");
        }

        CipherService.Type encryptionType = getEncryptionTypeFromConfig(config);

        return new HazelcastStateStore(hzInstance, clusterId, encryptionType);
    }

    private Config setEvictionConfigs(Config hzConfig, String mapName)
    {
        MapConfig mapCfg = new MapConfig();
        mapCfg.setName(mapName);
        mapCfg.setMaxIdleSeconds(MAXIDLESECONDS);
        mapCfg.setTimeToLiveSeconds(TIMETOLIVESECONDS);

        EvictionConfig evictionConfig = new EvictionConfig();
        evictionConfig.setEvictionPolicy(EvictionPolicy.LFU);
        evictionConfig.setMaxSizePolicy(MaxSizePolicy.PER_NODE);
        evictionConfig.setSize(EVICTIONSIZE);
        mapCfg.setEvictionConfig(evictionConfig);

        hzConfig.addMapConfig(mapCfg);
        return hzConfig;
    }

    private Config setPortConfigs(Map<String, String> properties, Config config)
    {
        String port = properties.get(DISCOVERY_PORT_CONFIG_NAME);

        if (port == null || port.trim().isEmpty()) {
            port = DEFAULT_DISCOVERY_PORT;
        }

        // Disable port auto increment
        config.getNetworkConfig().setPortAutoIncrement(false);
        config.getNetworkConfig().setPort(Integer.parseInt(port));

        return config;
    }

    private Config setCpSystemConfigs(Map<String, String> properties, Config config)
    {
        String cpMemberCountValue = properties.get("hazelcast.cp-system.member-count");

        if (cpMemberCountValue == null) {
            return config;
        }

        int cpMemberCount = Integer.parseInt(cpMemberCountValue);
        if (cpMemberCount > 0 && cpMemberCount < MINIMUM_CP_MEMBER_COUNT) {
            throw new PrestoException(CONFIGURATION_INVALID,
                    "CP member count should not be smaller than " + MINIMUM_CP_MEMBER_COUNT);
        }
        config.getCPSubsystemConfig().setCPMemberCount(cpMemberCount);

        return config;
    }
}
