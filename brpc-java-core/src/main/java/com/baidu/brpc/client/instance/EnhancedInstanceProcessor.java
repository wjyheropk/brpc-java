/*
 * Copyright (c) 2019 Baidu, Inc. All Rights Reserved.
 *
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
package com.baidu.brpc.client.instance;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.baidu.brpc.client.RpcClient;
import com.baidu.brpc.client.channel.BrpcChannel;
import com.baidu.brpc.client.channel.BrpcChannelFactory;
import com.baidu.brpc.client.loadbalance.FairStrategy;
import com.baidu.brpc.client.loadbalance.LoadBalanceStrategy;
import com.baidu.brpc.thread.ClientHealthCheckTimerInstance;

import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EnhancedInstanceProcessor implements InstanceProcessor {
    private RpcClient rpcClient;
    private CopyOnWriteArraySet<ServiceInstance> instances;
    private CopyOnWriteArrayList<BrpcChannel> healthyInstanceChannels;
    private CopyOnWriteArrayList<BrpcChannel> unhealthyInstanceChannels;
    private ConcurrentMap<ServiceInstance, BrpcChannel> instanceChannelMap;
    private Lock lock;
    private Timer healthCheckTimer;
    private volatile boolean stop = false;

    public EnhancedInstanceProcessor(RpcClient rpcClient) {
        this.rpcClient = rpcClient;
        this.instances = new CopyOnWriteArraySet<ServiceInstance>();
        this.healthyInstanceChannels = new CopyOnWriteArrayList<BrpcChannel>();
        this.unhealthyInstanceChannels = new CopyOnWriteArrayList<BrpcChannel>();
        this.instanceChannelMap = new ConcurrentHashMap<ServiceInstance, BrpcChannel>();
        this.lock = new ReentrantLock();
        healthCheckTimer = ClientHealthCheckTimerInstance.getOrCreateInstance();
        init();
    }

    private void init() {
        // start healthy check timer
        healthCheckTimer.newTimeout(
                new TimerTask() {
                    @Override
                    public void run(Timeout timeout) throws Exception {
                        if (!stop) {
                            List<BrpcChannel> newHealthyInstanceChannels = new ArrayList<BrpcChannel>();
                            Iterator<BrpcChannel> iter = unhealthyInstanceChannels.iterator();
                            while (iter.hasNext()) {
                                BrpcChannel instance = iter.next();
                                boolean isHealthy = isInstanceHealthy(instance.getIp(), instance.getPort());
                                if (isHealthy) {
                                    newHealthyInstanceChannels.add(instance);
                                }
                            }

                            List<BrpcChannel> newUnhealthyInstanceChannels = new ArrayList<BrpcChannel>();
                            iter = healthyInstanceChannels.iterator();
                            while (iter.hasNext()) {
                                BrpcChannel instance = iter.next();
                                boolean isHealthy = isInstanceHealthy(instance.getIp(), instance.getPort());
                                if (!isHealthy) {
                                    newUnhealthyInstanceChannels.add(instance);
                                }
                            }

                            lock.lock();
                            try {
                                healthyInstanceChannels.addAll(newHealthyInstanceChannels);
                                unhealthyInstanceChannels.removeAll(newHealthyInstanceChannels);

                                healthyInstanceChannels.removeAll(newUnhealthyInstanceChannels);
                                unhealthyInstanceChannels.addAll(newUnhealthyInstanceChannels);
                                notifyInvalidInstance(newUnhealthyInstanceChannels);
                            } finally {
                                lock.unlock();
                            }

                            healthCheckTimer.newTimeout(this,
                                    rpcClient.getRpcClientOptions().getHealthyCheckIntervalMillis(),
                                    TimeUnit.MILLISECONDS);
                        }

                    }
                },
                rpcClient.getRpcClientOptions().getHealthyCheckIntervalMillis(),
                TimeUnit.MILLISECONDS);
    }

    public void addInstance(ServiceInstance instance) {
        lock.lock();
        try {
            if (instances.add(instance)) {
                BrpcChannel brpcChannel = BrpcChannelFactory.createChannel(
                        instance.getIp(), instance.getPort(), rpcClient);
                healthyInstanceChannels.add(brpcChannel);
                instanceChannelMap.putIfAbsent(instance, brpcChannel);
            } else {
                log.warn("endpoint already exist, {}:{}", instance.getIp(), instance.getPort());
                return;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void addInstances(Collection<ServiceInstance> addList) {
        for (ServiceInstance instance : addList) {
            addInstance(instance);
        }
    }

    @Override
    public void deleteInstances(Collection<ServiceInstance> deleteList) {
        for (ServiceInstance instance : deleteList) {
            deleteInstance(instance);
        }
    }

    @Override
    public CopyOnWriteArraySet<ServiceInstance> getInstances() {
        return instances;
    }

    @Override
    public CopyOnWriteArrayList<BrpcChannel> getHealthyInstanceChannels() {
        return healthyInstanceChannels;
    }

    @Override
    public CopyOnWriteArrayList<BrpcChannel> getUnHealthyInstanceChannels() {
        return unhealthyInstanceChannels;
    }

    @Override
    public ConcurrentMap<ServiceInstance, BrpcChannel> getInstanceChannelMap() {
        return instanceChannelMap;
    }

    @Override
    public void stop() {
        stop = true;
        for (BrpcChannel brpcChannel : healthyInstanceChannels) {
            brpcChannel.close();
        }
        for (BrpcChannel brpcChannel : unhealthyInstanceChannels) {
            brpcChannel.close();
        }
    }

    private boolean isInstanceHealthy(String ip, int port) {
        boolean isHealthy = false;
        Socket socket = null;
        try {
            socket = new Socket(ip, port);
            isHealthy = true;
        } catch (Exception e) {
            log.warn("Recover socket test for {}:{} failed. message:{}",
                    ip, port, e.getMessage());
            isHealthy = false;
        } finally {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                if (log.isDebugEnabled()) {
                    log.debug(e.getMessage(), e);
                }
            }
        }
        return isHealthy;
    }

    private void deleteInstance(ServiceInstance instance) {
        lock.lock();
        try {
            if (instances.remove(instance)) {
                List<BrpcChannel> removedInstanceChannels = new ArrayList<BrpcChannel>();
                Iterator<BrpcChannel> iterator = healthyInstanceChannels.iterator();
                while (iterator.hasNext()) {
                    BrpcChannel brpcChannel = iterator.next();
                    if (brpcChannel.getIp().equals(instance.getIp())
                            && brpcChannel.getPort() == instance.getPort()) {
                        healthyInstanceChannels.remove(brpcChannel);
                        brpcChannel.close();
                        removedInstanceChannels.add(brpcChannel);
                        break;
                    }
                }

                if (removedInstanceChannels.size() == 0) {
                    iterator = unhealthyInstanceChannels.iterator();
                    while (iterator.hasNext()) {
                        BrpcChannel brpcChannel = iterator.next();
                        if (brpcChannel.getIp().equals(instance.getIp())
                                && brpcChannel.getPort() == instance.getPort()) {
                            unhealthyInstanceChannels.remove(brpcChannel);
                            brpcChannel.close();
                            removedInstanceChannels.add(brpcChannel);
                            break;
                        }
                    }
                }

                instanceChannelMap.remove(instance);
                // notify the fair load balance strategy
                notifyInvalidInstance(removedInstanceChannels);
            }
        } finally {
            lock.unlock();
        }
    }

    private void notifyInvalidInstance(List<BrpcChannel> invalidInstances) {
        if (rpcClient.getRpcClientOptions().getLoadBalanceType() == LoadBalanceStrategy.LOAD_BALANCE_FAIR) {
            ((FairStrategy) rpcClient.getLoadBalanceStrategy()).markInvalidInstance(invalidInstances);
        }
    }

}
