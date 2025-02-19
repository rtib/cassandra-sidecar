/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.sidecar;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.YAMLConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.configuration2.tree.ImmutableNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.sidecar.cluster.InstancesConfig;
import org.apache.cassandra.sidecar.cluster.InstancesConfigImpl;
import org.apache.cassandra.sidecar.cluster.instance.InstanceMetadata;
import org.apache.cassandra.sidecar.cluster.instance.InstanceMetadataImpl;
import org.apache.cassandra.sidecar.common.CQLSession;
import org.apache.cassandra.sidecar.common.CassandraVersionProvider;
import org.apache.cassandra.sidecar.common.JmxClient;
import org.apache.cassandra.sidecar.common.utils.ValidationConfiguration;
import org.apache.cassandra.sidecar.common.utils.YAMLValidationConfiguration;
import org.jetbrains.annotations.Nullable;

import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.CASSANDRA_ALLOWED_CHARS_FOR_COMPONENT_NAME;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.CASSANDRA_ALLOWED_CHARS_FOR_DIRECTORY;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.CASSANDRA_ALLOWED_CHARS_FOR_RESTRICTED_COMPONENT_NAME;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.CASSANDRA_FORBIDDEN_KEYSPACES;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.CASSANDRA_INPUT_VALIDATION;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.CASSANDRA_INSTANCE;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.CASSANDRA_INSTANCES;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.CASSANDRA_INSTANCE_DATA_DIRS;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.CASSANDRA_INSTANCE_HOST;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.CASSANDRA_INSTANCE_ID;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.CASSANDRA_INSTANCE_PORT;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.CASSANDRA_JMX_HOST;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.CASSANDRA_JMX_PORT;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.CASSANDRA_JMX_ROLE;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.CASSANDRA_JMX_ROLE_PASSWORD;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.CASSANDRA_JMX_SSL_ENABLED;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.HEALTH_CHECK_FREQ;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.HOST;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.KEYSTORE_PASSWORD;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.KEYSTORE_PATH;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.PORT;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.SSL_ENABLED;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.STREAM_REQUESTS_PER_SEC;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.THROTTLE_DELAY_SEC;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.THROTTLE_TIMEOUT_SEC;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.TRUSTSTORE_PASSWORD;
import static org.apache.cassandra.sidecar.utils.YAMLKeyConstants.TRUSTSTORE_PATH;

/**
 * A {@link Configuration} that is built from a YAML configuration file for Sidecar
 */
public class YAMLSidecarConfiguration extends Configuration
{
    private static final Logger logger = LoggerFactory.getLogger(YAMLSidecarConfiguration.class);

    private YAMLSidecarConfiguration(InstancesConfig instancesConfig,
                                     String host,
                                     Integer port,
                                     long healthCheckFrequencyMillis,
                                     boolean isSslEnabled,
                                     @Nullable String keyStorePath,
                                     @Nullable String keyStorePassword,
                                     @Nullable String trustStorePath,
                                     @Nullable String trustStorePassword,
                                     long rateLimitStreamRequestsPerSecond,
                                     long throttleTimeoutInSeconds,
                                     long throttleDelayInSeconds,
                                     ValidationConfiguration validationConfiguration)
    {
        super(instancesConfig,
              host,
              port,
              healthCheckFrequencyMillis,
              isSslEnabled,
              keyStorePath,
              keyStorePassword,
              trustStorePath,
              trustStorePassword,
              rateLimitStreamRequestsPerSecond,
              throttleTimeoutInSeconds,
              throttleDelayInSeconds,
              validationConfiguration);
    }

    /**
     * Returns a new {@link Configuration} built from the provided {@code confPath} YAML file and a
     * {@code versionProvider}
     *
     * @param confPath        the path to the Sidecar YAML configuration file
     * @param versionProvider a Cassandra version provider
     * @return the {@link YAMLConfiguration} parsed from the YAML file
     * @throws IOException when reading the configuration from file fails
     */
    public static Configuration of(String confPath, CassandraVersionProvider versionProvider) throws IOException
    {
        YAMLConfiguration yamlConf = yamlConfiguration(confPath);
        long healthCheckFrequencyMillis = yamlConf.getLong(HEALTH_CHECK_FREQ, 1000);
        ValidationConfiguration validationConfiguration = validationConfiguration(yamlConf);
        InstancesConfig instancesConfig = instancesConfig(yamlConf, versionProvider, healthCheckFrequencyMillis);

        return new YAMLSidecarConfiguration(instancesConfig,
                                            yamlConf.get(String.class, HOST),
                                            yamlConf.get(Integer.class, PORT),
                                            healthCheckFrequencyMillis,
                                            yamlConf.get(Boolean.class, SSL_ENABLED, false),
                                            yamlConf.get(String.class, KEYSTORE_PATH, null),
                                            yamlConf.get(String.class, KEYSTORE_PASSWORD, null),
                                            yamlConf.get(String.class, TRUSTSTORE_PATH, null),
                                            yamlConf.get(String.class, TRUSTSTORE_PASSWORD, null),
                                            yamlConf.getLong(STREAM_REQUESTS_PER_SEC, 5000L),
                                            yamlConf.getLong(THROTTLE_TIMEOUT_SEC, 10),
                                            yamlConf.getLong(THROTTLE_DELAY_SEC, 5),
                                            validationConfiguration);
    }

    /**
     * Returns an object to read the YAML file from {@code confPath}.
     *
     * @param confPath the YAML file that provides the Sidecar {@link Configuration}
     * @return an object to read the YAML file from {@code confPath}
     * @throws IOException when reading the configuration from file fails
     */
    private static YAMLConfiguration yamlConfiguration(String confPath) throws IOException
    {
        logger.info("Reading configuration from {}", confPath);

        try
        {
            URL url = new URL(confPath);
            YAMLConfiguration yamlConf = new YAMLConfiguration();
            InputStream stream = url.openStream();
            yamlConf.read(stream);
            return yamlConf;
        }
        catch (ConfigurationException | IOException e)
        {
            throw new IOException(String.format("Unable to parse cluster information from file='%s'", confPath), e);
        }
    }

    /**
     * Parses the {@link InstancesConfig} from the {@link YAMLConfiguration yamlConf}, the {@code versionProvider}, and
     * the {@code healthCheckFrequencyMillis}.
     *
     * @param yamlConf                   the object used to parse the YAML file
     * @param versionProvider            a Cassandra version provider
     * @param healthCheckFrequencyMillis the health check frequency configuration in milliseconds
     * @return the parsed {@link InstancesConfig} from the {@code yamlConf} object
     */
    private static InstancesConfig instancesConfig(YAMLConfiguration yamlConf, CassandraVersionProvider versionProvider,
                                                   long healthCheckFrequencyMillis)
    {
        /* Since we are supporting handling multiple instances in Sidecar optionally, we prefer reading single instance
         * data over reading multiple instances section
         */
        org.apache.commons.configuration2.Configuration singleInstanceConf = yamlConf.subset(CASSANDRA_INSTANCE);
        if (singleInstanceConf != null && !singleInstanceConf.isEmpty())
        {
            InstanceMetadata instanceMetadata = buildInstanceMetadata(singleInstanceConf,
                                                                      versionProvider,
                                                                      healthCheckFrequencyMillis);
            return new InstancesConfigImpl(instanceMetadata);
        }

        List<HierarchicalConfiguration<ImmutableNode>> instances = yamlConf.configurationsAt(CASSANDRA_INSTANCES);
        final List<InstanceMetadata> instanceMetas = new ArrayList<>();
        for (HierarchicalConfiguration<ImmutableNode> instance : instances)
        {
            InstanceMetadata instanceMetadata = buildInstanceMetadata(instance,
                                                                      versionProvider,
                                                                      healthCheckFrequencyMillis);
            instanceMetas.add(instanceMetadata);
        }
        return new InstancesConfigImpl(instanceMetas);
    }

    /**
     * Parses the {@link ValidationConfiguration} from the {@link YAMLConfiguration yamlConf}.
     *
     * @param yamlConf the object used to parse the YAML file
     * @return the parsed {@link ValidationConfiguration} from the {@code yamlConf} object
     */
    private static ValidationConfiguration validationConfiguration(YAMLConfiguration yamlConf)
    {
        org.apache.commons.configuration2.Configuration validation = yamlConf.subset(CASSANDRA_INPUT_VALIDATION);
        Set<String> forbiddenKeyspaces = new HashSet<>(validation.getList(String.class,
                                                                          CASSANDRA_FORBIDDEN_KEYSPACES,
                                                                          Collections.emptyList()));
        UnaryOperator<String> readString = key -> validation.get(String.class, key);
        String allowedPatternForDirectory = readString.apply(CASSANDRA_ALLOWED_CHARS_FOR_DIRECTORY);
        String allowedPatternForComponentName = readString.apply(CASSANDRA_ALLOWED_CHARS_FOR_COMPONENT_NAME);
        String allowedPatternForRestrictedComponentName = readString
                                                          .apply(CASSANDRA_ALLOWED_CHARS_FOR_RESTRICTED_COMPONENT_NAME);

        return new YAMLValidationConfiguration(forbiddenKeyspaces,
                                               allowedPatternForDirectory,
                                               allowedPatternForComponentName,
                                               allowedPatternForRestrictedComponentName);
    }

    /**
     * Builds the {@link InstanceMetadata} from the {@link org.apache.commons.configuration2.Configuration},
     * a provided {@code  versionProvider} and {@code healthCheckFrequencyMillis}.
     *
     * @param instance                   the object that allows reading from the YAML file
     * @param versionProvider            a Cassandra version provider
     * @param healthCheckFrequencyMillis the health check frequency configuration in milliseconds
     * @return the parsed {@link InstanceMetadata} from YAML
     */
    private static InstanceMetadata buildInstanceMetadata(org.apache.commons.configuration2.Configuration instance,
                                                          CassandraVersionProvider versionProvider,
                                                          long healthCheckFrequencyMillis)
    {
        int id = instance.get(Integer.class, CASSANDRA_INSTANCE_ID, 1);
        String host = instance.get(String.class, CASSANDRA_INSTANCE_HOST);
        int port = instance.get(Integer.class, CASSANDRA_INSTANCE_PORT);
        String dataDirs = instance.get(String.class, CASSANDRA_INSTANCE_DATA_DIRS);
        String jmxHost = instance.get(String.class, CASSANDRA_JMX_HOST, "127.0.0.1");
        int jmxPort = instance.get(Integer.class, CASSANDRA_JMX_PORT, 7199);
        String jmxRole = instance.get(String.class, CASSANDRA_JMX_ROLE, null);
        String jmxRolePassword = instance.get(String.class, CASSANDRA_JMX_ROLE_PASSWORD, null);
        boolean jmxSslEnabled = instance.get(Boolean.class, CASSANDRA_JMX_SSL_ENABLED, false);

        CQLSession session = new CQLSession(host, port, healthCheckFrequencyMillis);
        JmxClient jmxClient = new JmxClient(jmxHost, jmxPort, jmxRole, jmxRolePassword, jmxSslEnabled);
        return new InstanceMetadataImpl(id,
                                        Collections.unmodifiableList(Arrays.asList(dataDirs.split(","))),
                                        session,
                                        jmxClient,
                                        versionProvider);
    }
}
