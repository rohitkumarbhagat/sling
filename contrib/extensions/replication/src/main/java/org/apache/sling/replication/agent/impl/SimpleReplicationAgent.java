/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.replication.agent.impl;

import java.net.URI;
import org.apache.sling.replication.agent.AgentReplicationException;
import org.apache.sling.replication.agent.ReplicationAgent;
import org.apache.sling.replication.communication.ReplicationEndpoint;
import org.apache.sling.replication.communication.ReplicationRequest;
import org.apache.sling.replication.communication.ReplicationResponse;
import org.apache.sling.replication.queue.*;
import org.apache.sling.replication.serialization.ReplicationPackage;
import org.apache.sling.replication.serialization.ReplicationPackageBuilder;
import org.apache.sling.replication.serialization.ReplicationPackageBuildingException;
import org.apache.sling.replication.queue.ReplicationQueueItem;
import org.apache.sling.replication.transport.ReplicationTransportException;
import org.apache.sling.replication.transport.TransportHandler;
import org.apache.sling.replication.transport.authentication.TransportAuthenticationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Basic implementation of a {@link ReplicationAgent}
 */
public class SimpleReplicationAgent implements ReplicationAgent, ReplicationQueueProcessor {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final ReplicationPackageBuilder packageBuilder;

    private final ReplicationQueueProvider queueProvider;

    private final TransportHandler transportHandler;

    private final TransportAuthenticationProvider<?, ?> transportAuthenticationProvider;

    private final ReplicationQueueDistributionStrategy queueDistributionStrategy;

    private final String name;

    private final String endpoint;

    private final String[] rules;

    private final boolean useAggregatePaths;

    public SimpleReplicationAgent(String name, String endpoint, String[] rules,
                                  boolean useAggregatePaths,
                                  TransportHandler transportHandler,
                                  ReplicationPackageBuilder packageBuilder,
                                  ReplicationQueueProvider queueProvider,
                                  TransportAuthenticationProvider<?, ?> transportAuthenticationProvider,
                                  ReplicationQueueDistributionStrategy queueDistributionHandler) {
        this.name = name;
        this.endpoint = endpoint;
        this.rules = rules;
        this.transportHandler = transportHandler;
        this.packageBuilder = packageBuilder;
        this.queueProvider = queueProvider;
        this.transportAuthenticationProvider = transportAuthenticationProvider;
        this.queueDistributionStrategy = queueDistributionHandler;
        this.useAggregatePaths = useAggregatePaths;
    }

    public ReplicationResponse execute(ReplicationRequest replicationRequest)
            throws AgentReplicationException {

        // create packages from request
        ReplicationPackage[] replicationPackages = buildPackages(replicationRequest);

        ReplicationResponse replicationResponse = schedule(replicationPackages, false);

        return replicationResponse;
    }

    public void send(ReplicationRequest replicationRequest) throws AgentReplicationException {
        // create packages from request
        ReplicationPackage[] replicationPackages = buildPackages(replicationRequest);

        schedule(replicationPackages, true);
    }


    private ReplicationPackage buildPackage(ReplicationRequest replicationRequest) throws AgentReplicationException {
        // create package from request
        ReplicationPackage replicationPackage;
        try {
            replicationPackage = packageBuilder.createPackage(replicationRequest);
        } catch (ReplicationPackageBuildingException e) {
            throw new AgentReplicationException(e);
        }

        return replicationPackage;
    }

    private ReplicationPackage[] buildPackages(ReplicationRequest replicationRequest) throws AgentReplicationException {

        List<ReplicationPackage> packages = new ArrayList<ReplicationPackage>();

        if(useAggregatePaths){
            ReplicationPackage replicationPackage = buildPackage(replicationRequest);
            packages.add(replicationPackage);
        }
        else {
            for (String path : replicationRequest.getPaths()){
                ReplicationPackage replicationPackage = buildPackage(new ReplicationRequest(replicationRequest.getTime(),
                        replicationRequest.getAction(),
                        new String[] { path }));

                packages.add(replicationPackage);
            }
        }

        return packages.toArray(new ReplicationPackage[0]);
    }

    // offer option throws an exception at first error
    private ReplicationResponse schedule(ReplicationPackage[] packages, boolean offer) throws AgentReplicationException {
        ReplicationResponse replicationResponse = new ReplicationResponse();

        for (ReplicationPackage replicationPackage : packages){
            ReplicationResponse currentReplicationResponse = schedule(replicationPackage, offer);

            replicationResponse.setSuccessful(currentReplicationResponse.isSuccessful());
            replicationResponse.setStatus(currentReplicationResponse.getStatus());
        }

        return replicationResponse;
    }

    private ReplicationResponse schedule(ReplicationPackage replicationPackage, boolean offer) throws AgentReplicationException {
        ReplicationResponse replicationResponse = new ReplicationResponse();
        ReplicationQueueItem replicationQueueItem = new ReplicationQueueItem(replicationPackage.getId(),
                replicationPackage.getPaths(),
                replicationPackage.getAction(),
                replicationPackage.getType());

        if(offer){
            try {

                queueDistributionStrategy.offer(replicationQueueItem, this, queueProvider);
            } catch (ReplicationQueueException e) {
                replicationResponse.setSuccessful(false);
                throw new AgentReplicationException(e);
            }
        }
        else {
            // send the replication package to the queue distribution handler
            try {
                ReplicationQueueItemState state = queueDistributionStrategy.add(replicationQueueItem,
                        this, queueProvider);
                if (state != null) {
                    replicationResponse.setStatus(state.getItemState().toString());
                    replicationResponse.setSuccessful(state.isSuccessful());
                } else {
                    replicationResponse.setStatus(ReplicationQueueItemState.ItemState.ERROR.toString());
                    replicationResponse.setSuccessful(false);
                }
            } catch (Exception e) {
                if (log.isErrorEnabled()) {
                    log.error("an error happened during queue processing", e);
                }
                replicationResponse.setSuccessful(false);
            }
        }
        return replicationResponse;
    }

    public boolean process(ReplicationQueueItem itemInfo)  {
        try {
            ReplicationPackage replicationPackage = packageBuilder.getPackage(itemInfo.getId());
            if(replicationPackage == null){
                return false;
            }

            try {
                if (transportHandler != null || (endpoint != null && endpoint.length() > 0)) {
                    transportHandler.transport(replicationPackage,
                            new ReplicationEndpoint(endpoint),
                            transportAuthenticationProvider);

                    return true;
                } else {
                    log.info("agent {} processing skipped", name);
                    return false;
                }
            }
            finally {
                replicationPackage.delete();
            }
        } catch (ReplicationTransportException e) {
            log.error("transport error", e);
            return false;
        }
    }

    public ReplicationPackage removeHead(String queueName) throws ReplicationQueueException {
        ReplicationQueue queue = getQueue(queueName);
        ReplicationQueueItem info = queue.getHead();
        if(info == null) return null;

        queue.removeHead();

        ReplicationPackage replicationPackage = packageBuilder.getPackage(info.getId());
        return replicationPackage;
    }

    public URI getEndpoint() {
        return new ReplicationEndpoint(endpoint).getUri();
    }

    public String getName() {
        return name;
    }

    public ReplicationQueue getQueue(String name) throws ReplicationQueueException {
        ReplicationQueue queue;
        if (name != null && name.length() > 0) {
            queue = queueProvider.getQueue(this, name);
        } else {
            queue = queueProvider.getDefaultQueue(this);
        }
        return queue;
    }

    public String[] getRules() {
        return rules;
    }

}
