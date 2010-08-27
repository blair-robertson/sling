/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.commons.threads.impl;

import java.util.Dictionary;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.sling.commons.threads.ModifiableThreadPoolConfig;
import org.apache.sling.commons.threads.ThreadPool;
import org.apache.sling.commons.threads.ThreadPoolConfig;
import org.apache.sling.commons.threads.ThreadPoolManager;
import org.apache.sling.commons.threads.ThreadPoolConfig.ThreadPoolPolicy;
import org.apache.sling.commons.threads.ThreadPoolConfig.ThreadPriority;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedServiceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The DefaultThreadPoolManager implements the {@link ThreadPoolManager} interface
 * and is responsible for managing {@link ThreadPool}s.
 */
public class DefaultThreadPoolManager
    implements ThreadPoolManager, ManagedServiceFactory {

    /** By default we use the logger for this class. */
    protected final Logger logger = LoggerFactory.getLogger(this.getClass().getName());

    /** The managed thread pools */
    protected final Map<String, Entry> pools = new HashMap<String, Entry>();

    /** The properties. */
    protected final Dictionary<String, Object> properties;

    /** The bundle context. */
    protected final BundleContext bundleContext;

    /**
     * Constructor and activate this component.
     */
    public DefaultThreadPoolManager(final BundleContext bc, final Dictionary<String, Object> props) {
        this.properties = props;
        this.bundleContext = bc;
        this.logger.info("Started Apache Sling Thread Pool Manager");
    }

    /**
     * Deactivate this component.
     */
    public void destroy() {
        this.logger.debug("Disposing all thread pools");

        synchronized ( this.pools ) {
            for (final Entry entry : this.pools.values()) {
                entry.shutdown();
            }
            this.pools.clear();
        }
        this.logger.info("Stopped Apache Sling Thread Pool Manager");
    }

    /**
     * Create a thread pool configuration from a config admin configuration
     */
    private ThreadPoolConfig createConfig(final Dictionary<String, Object> props) {
        final ModifiableThreadPoolConfig config = new ModifiableThreadPoolConfig();
        if ( props.get(ModifiableThreadPoolConfig.PROPERTY_MIN_POOL_SIZE) != null ) {
            config.setMinPoolSize((Integer)props.get(ModifiableThreadPoolConfig.PROPERTY_MIN_POOL_SIZE));
        }
        if ( props.get(ModifiableThreadPoolConfig.PROPERTY_MAX_POOL_SIZE) != null ) {
            config.setMaxPoolSize((Integer)props.get(ModifiableThreadPoolConfig.PROPERTY_MAX_POOL_SIZE));
        }
        if ( props.get(ModifiableThreadPoolConfig.PROPERTY_QUEUE_SIZE) != null ) {
            config.setQueueSize((Integer)props.get(ModifiableThreadPoolConfig.PROPERTY_QUEUE_SIZE));
        }
        if ( props.get(ModifiableThreadPoolConfig.PROPERTY_KEEP_ALIVE_TIME) != null ) {
            config.setKeepAliveTime((Long)props.get(ModifiableThreadPoolConfig.PROPERTY_KEEP_ALIVE_TIME));
        }
        if ( props.get(ModifiableThreadPoolConfig.PROPERTY_BLOCK_POLICY) != null ) {
            config.setBlockPolicy(ThreadPoolPolicy.valueOf(props.get(ModifiableThreadPoolConfig.PROPERTY_BLOCK_POLICY).toString()));
        }
        if ( props.get(ModifiableThreadPoolConfig.PROPERTY_SHUTDOWN_GRACEFUL) != null ) {
            config.setShutdownGraceful((Boolean)props.get(ModifiableThreadPoolConfig.PROPERTY_SHUTDOWN_GRACEFUL));
        }
        if ( props.get(ModifiableThreadPoolConfig.PROPERTY_SHUTDOWN_WAIT_TIME) != null ) {
            config.setShutdownWaitTimeMs((Integer)props.get(ModifiableThreadPoolConfig.PROPERTY_SHUTDOWN_WAIT_TIME));
        }
        if ( props.get(ModifiableThreadPoolConfig.PROPERTY_PRIORITY) != null ) {
            config.setPriority(ThreadPriority.valueOf(props.get(ModifiableThreadPoolConfig.PROPERTY_PRIORITY).toString()));
        }
        if ( props.get(ModifiableThreadPoolConfig.PROPERTY_DAEMON) != null ) {
            config.setDaemon((Boolean)props.get(ModifiableThreadPoolConfig.PROPERTY_DAEMON));
        }
        return config;
    }

    /**
     * @see org.apache.sling.commons.threads.ThreadPoolManager#get(java.lang.String)
     */
    public ThreadPool get(final String name) {
        final String poolName = (name == null ? DEFAULT_THREADPOOL_NAME : name);
        Entry entry = null;
        synchronized (this.pools) {
            entry = this.pools.get(poolName);
            if ( entry == null ) {
                this.logger.debug("Creating new pool with name {}", poolName);
                final ModifiableThreadPoolConfig config = new ModifiableThreadPoolConfig();
                entry = new Entry(null, config, poolName);

                this.pools.put(poolName, entry);
            }
            return entry.incUsage();
        }
    }

    /**
     * @see org.apache.sling.commons.threads.ThreadPoolManager#release(org.apache.sling.commons.threads.ThreadPool)
     */
    public void release(ThreadPool pool) {
        if ( pool instanceof ThreadPoolFacade ) {
            synchronized ( this.pools ) {
                final Entry entry = this.pools.get(pool.getName());
                if ( entry != null ) {
                    entry.decUsage();
                }
            }
        }

    }

    /**
     * @see org.apache.sling.commons.threads.ThreadPoolManager#create(org.apache.sling.commons.threads.ThreadPoolConfig)
     */
    public ThreadPool create(ThreadPoolConfig config) {
        if ( config == null ) {
            throw new IllegalArgumentException("Config must not be null.");
        }
        final String name = "ThreadPool-" + UUID.randomUUID().toString();
        final Entry entry = new Entry(null, config, name);
        synchronized ( this.pools ) {
            this.pools.put(name, entry);
        }
        return entry.incUsage();
    }

    /**
     * @see org.osgi.service.cm.ManagedServiceFactory#getName()
     */
    public String getName() {
        return this.properties.get(Constants.SERVICE_DESCRIPTION).toString();
    }

    /**
     * Return all configurations for the web console printer
     */
    public Entry[] getConfigurations() {
        synchronized ( this.pools ) {
            return this.pools.values().toArray(new Entry[this.pools.size()]);
        }
    }

    /**
     * @see org.osgi.service.cm.ManagedServiceFactory#updated(java.lang.String, java.util.Dictionary)
     */
    @SuppressWarnings("unchecked")
    public void updated(String pid, Dictionary properties)
    throws ConfigurationException {
        final String name = (String) properties.get(ModifiableThreadPoolConfig.PROPERTY_NAME);
        if ( name == null || name.length() == 0 ) {
            throw new ConfigurationException(ModifiableThreadPoolConfig.PROPERTY_NAME, "Property is missing or empty.");
        }
        this.logger.debug("Updating {} with {}", pid, properties);
        synchronized ( this.pools ) {
            final ThreadPoolConfig config = this.createConfig(properties);

            Entry foundEntry = null;
            // we have to search the config by using the pid first!
            for (final Entry entry : this.pools.values()) {
                if ( pid.equals(entry.getPid()) ) {
                    foundEntry = entry;
                    break;
                }
            }
            // if we haven't found it by pid we search by name
            if ( foundEntry == null ) {
                for (final Entry entry : this.pools.values()) {
                    if ( name.equals(entry.getName()) ) {
                        foundEntry = entry;
                        break;
                    }
                }
            }

            if ( foundEntry != null ) {
                // if the name changed - we have to reregister(!)
                if ( !name.equals(foundEntry.getName()) ) {
                    this.pools.remove(foundEntry.getName());
                    this.pools.put(name, foundEntry);
                }
                // update
                foundEntry.update(config, name, pid);
            } else {
                // create
                this.pools.put(name, new Entry(pid, config, name));
            }
        }
    }

    /**
     * @see org.osgi.service.cm.ManagedServiceFactory#deleted(java.lang.String)
     */
    public void deleted(String pid) {
        this.logger.debug("Deleting " + pid);
        // we just remove the thread pool from our list of pools and readd it
        // as an anonymous pool with default config(!) if it is used
        synchronized ( this.pools ) {
            Entry foundEntry = null;
            // we have to search the config by using the pid!
            for (final Entry entry : this.pools.values()) {
                if ( pid.equals(entry.getPid()) ) {
                    foundEntry = entry;
                    break;
                }
            }
            if ( foundEntry != null ) {
                this.pools.remove(foundEntry.getName());
                if ( foundEntry.isUsed() ) {
                    // we register this with a new name
                    final String name = "ThreadPool-" + UUID.randomUUID().toString();
                    foundEntry.update(new ModifiableThreadPoolConfig(), name, null);
                    this.pools.put(name, foundEntry);
                }
            }
        }
    }

    protected static final class Entry {
        /** The configuration pid. (might be null for anonymous pools.*/
        private volatile String pid;

        /** Usage count. */
        private volatile int count;

        /** The configuration for the pool. */
        private volatile ThreadPoolConfig config;

        /** The name of the pool. */
        private volatile String name;

        /** The corresponding pool - might be null if unused. */
        private volatile ThreadPoolFacade pool;

        public Entry(final String pid, final ThreadPoolConfig config, final String name) {
            this.pid = pid;
            this.config = config;
            this.name = name;
        }

        public String getPid() {
            return this.pid;
        }

        public void shutdown() {
            if ( this.pool != null ) {
                this.pool.shutdown();
                this.pool = null;
            }
        }

        public ThreadPoolFacade incUsage() {
            if ( pool == null ) {
                pool = new ThreadPoolFacade(new DefaultThreadPool(name, this.config));
            }
            this.count++;
            return pool;
        }

        public void decUsage() {
            this.count--;
            if ( this.count == 0 ) {
                this.shutdown();
            }
        }

        public void update(final ThreadPoolConfig config, final String name, final String pid) {
            if ( this.pool != null ) {
                this.pool.setName(name);
                if ( !this.config.equals(config) ) {
                    this.pool.setPool(new DefaultThreadPool(name, config));
                }
            }
            this.config = config;
            this.name = name;
            this.pid = pid;
        }

        public String getName() {
            return this.name;
        }

        public boolean isUsed() {
            return this.count > 0;
        }

        public ThreadPoolConfig getConfig() {
            return this.config;
        }
    }
}
