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

package org.apache.cassandra.sidecar.common;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Host;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;


/**
 * Since it's possible for the version of Cassandra to change under us, we need this delegate to wrap the functionality
 * of the underlying Cassandra adapter.  If a server reboots, we can swap out the right Adapter when the driver
 * reconnects.
 *
 * <p>This delegate <b>MUST</b> invoke {@link #checkSession()} before every call, because:</p>
 *
 * <ol>
 * <li>The session lazily connects</li>
 * <li>We might need to swap out the adapter if the version has changed</li>
 * </ol>
 */
public class CassandraAdapterDelegate implements ICassandraAdapter, Host.StateListener
{
    private final CQLSession cqlSession;
    private final JmxClient jmxClient;
    private final CassandraVersionProvider versionProvider;
    private final AtomicReference<Session> session = new AtomicReference<>(null);
    private SimpleCassandraVersion currentVersion;
    private ICassandraAdapter adapter;
    private volatile NodeSettings nodeSettings = null;

    private static final Logger logger = LoggerFactory.getLogger(CassandraAdapterDelegate.class);
    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final AtomicBoolean isHealthCheckActive = new AtomicBoolean(false);

    public CassandraAdapterDelegate(CassandraVersionProvider provider, CQLSession cqlSession, JmxClient jmxClient)
    {
        this.versionProvider = provider;
        this.cqlSession = cqlSession;
        this.jmxClient = jmxClient;
    }

    private void maybeRegisterHostListener(@NotNull Session session)
    {
        if (registered.compareAndSet(false, true))
        {
            session.getCluster().register(this);
        }
    }

    private void maybeUnregisterHostListener(@NotNull Session session)
    {
        if (registered.compareAndSet(true, false))
        {
            session.getCluster().unregister(this);
        }
    }

    /**
     * Make an attempt to obtain the session object.
     *
     * <p>It needs to be called before routing the request to the adapter
     * We might end up swapping the adapter out because of a server upgrade</p>
     */
    public void checkSession()
    {
        if (session.get() != null)
        {
            return;
        }

        synchronized (this)
        {
            session.compareAndSet(null, cqlSession.getLocalCql());
        }
    }

    /**
     * Should be called on initial connect as well as when a server comes back since it might be from an upgrade
     * synchronized so we don't flood the DB with version requests
     *
     * <p>If the healthcheck determines we've changed versions, it should load the proper adapter</p>
     */
    public void healthCheck()
    {
        if (isHealthCheckActive.compareAndSet(false, true))
        {
            try
            {
                healthCheckInternal();
            }
            finally
            {
                isHealthCheckActive.set(false);
            }
        }
        else
        {
            logger.debug("Skipping health check because there's an active check at the moment");
        }
    }

    private void healthCheckInternal()
    {
        checkSession();

        Session activeSession = session.get();
        if (activeSession == null)
        {
            logger.info("No local CQL session is available. Cassandra is down presumably.");
            nodeSettings = null;
            return;
        }

        maybeRegisterHostListener(activeSession);

        try
        {
            Row oneResult = activeSession.execute("select release_version, partitioner from system.local")
                                         .one();

            String releaseVersion = oneResult.getString("release_version");
            // update the nodeSettings cache.
            // Note that within the scope of this method, we should keep on using the local releaseVersion
            nodeSettings = new NodeSettings(releaseVersion, oneResult.getString("partitioner"));
            // this might swap the adapter out
            SimpleCassandraVersion newVersion = SimpleCassandraVersion.create(releaseVersion);
            if (!newVersion.equals(currentVersion))
            {
                currentVersion = newVersion;
                adapter = versionProvider.getCassandra(nodeSettings.releaseVersion()).create(cqlSession, jmxClient);
                logger.info("Cassandra version change detected. New adapter loaded: {}", adapter);
            }
            logger.debug("Cassandra version {}", releaseVersion);
        }
        catch (NoHostAvailableException e)
        {
            logger.error("Unexpected error connecting to Cassandra instance.", e);
            // The cassandra node is down.
            // Unregister the host listener and nullify the session in order to get a new object.
            nodeSettings = null;
            maybeUnregisterHostListener(activeSession);
            activeSession.closeAsync();
            session.compareAndSet(activeSession, null);
        }
    }

    /**
     * @return a cached {@link NodeSettings}. The returned value could be null when no CQL connection is established
     */
    @Nullable
    @Override
    public NodeSettings getSettings()
    {
        checkSession();
        return nodeSettings;
    }

    @Override
    public StorageOperations storageOperations()
    {
        return adapter.storageOperations();
    }

    @Override
    public void onAdd(Host host)
    {
        healthCheck();
    }

    @Override
    public void onUp(Host host)
    {
        healthCheck();
    }

    @Override
    public void onDown(Host host)
    {
        nodeSettings = null;
    }

    @Override
    public void onRemove(Host host)
    {
        healthCheck();
    }

    @Override
    public void onRegister(Cluster cluster)
    {
    }

    @Override
    public void onUnregister(Cluster cluster)
    {
    }

    public boolean isUp()
    {
        return nodeSettings != null;
    }

    public SimpleCassandraVersion getVersion()
    {
        healthCheck();
        return currentVersion;
    }
}
