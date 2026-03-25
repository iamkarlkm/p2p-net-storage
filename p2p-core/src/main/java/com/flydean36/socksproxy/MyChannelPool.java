
package com.flydean36.socksproxy;

/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */


import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.channel.pool.SimpleChannelPool;
import io.netty.util.AttributeKey;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.p2p.server.P2PServerUdp;

/**
 * Bootstrap bootstrap,
                            ChannelPoolHandler handler,
                            ChannelHealthChecker healthCheck, AcquireTimeoutAction action
 * @author karl
 */
public class MyChannelPool{
    private static final AttributeKey<SimpleChannelPool> POOL_KEY =
        AttributeKey.newInstance("io.netty.channel.pool.SimpleChannelPool");
    
    private static final ConcurrentHashMap<SocketAddress,FixedChannelPool> POOL_MAP = new ConcurrentHashMap();
    
    private static final ConcurrentHashMap<Bootstrap,FixedChannelPool> BOOTSTRAP_POOL_MAP = new ConcurrentHashMap();
    
    public static FixedChannelPool newPool(Bootstrap bootstrap,SocketAddress remoteAddress) {
        FixedChannelPool pool = POOL_MAP.get(remoteAddress);
        if(pool == null){
           pool = new FixedChannelPool(bootstrap.remoteAddress(remoteAddress), new SimpleChannelPoolHandler(),
                        ChannelHealthChecker.ACTIVE, FixedChannelPool.AcquireTimeoutAction.FAIL, 
            90000, Runtime.getRuntime().availableProcessors() * 2, 4096);
            POOL_MAP.put(remoteAddress, pool);
            //jvm退出,释放资源
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                FixedChannelPool pool0 = POOL_MAP.get(remoteAddress);
                pool0.close();
            }
        }));
        }
        return pool;
    }
    
    public static FixedChannelPool newPool(Bootstrap bootstrap) {
        FixedChannelPool pool = BOOTSTRAP_POOL_MAP.get(bootstrap);pool.acquire();
        if(pool == null){
           pool = new FixedChannelPool(bootstrap, new SimpleChannelPoolHandler(),
                        ChannelHealthChecker.ACTIVE, FixedChannelPool.AcquireTimeoutAction.FAIL, 
            90000, Runtime.getRuntime().availableProcessors() * 2, 4096);
            BOOTSTRAP_POOL_MAP.put(bootstrap, pool);
            //jvm退出,释放资源
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                FixedChannelPool pool0 = BOOTSTRAP_POOL_MAP.get(bootstrap);
                pool0.close();
            }
        }));
        }
        return pool;
    }
    
}

