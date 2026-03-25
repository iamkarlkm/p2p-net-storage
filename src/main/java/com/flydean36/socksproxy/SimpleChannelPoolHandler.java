/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.flydean36.socksproxy;


import io.netty.channel.Channel;
import io.netty.channel.pool.ChannelPoolHandler;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * 
 * @author karl
 */
@Slf4j
public class SimpleChannelPoolHandler implements ChannelPoolHandler {
    private static final AtomicInteger COUNT = new AtomicInteger();
	
	
	@Override
	public void channelReleased(Channel ch) throws Exception {
		        System.out.println("channelReleased. Channel ID: " + ch.id());
	}

	@Override
	public void channelAcquired(Channel ch) throws Exception {
		//       System.out.println("channelAcquired. Channel ID: " + ch.id());
//		if (!ch.isOpen() || !ch.isActive()) {
//                    //ch.close();
//                    //System.out.println("channel is invalid. Channel ID: " + ch.id());
//                    throw new ChannleInvalidException("channel is invalid. Channel ID: " + ch.id());
//		}
	}

	@Override
	public void channelCreated(Channel ch) throws Exception {
        System.out.println("channelCreated total:"+COUNT.incrementAndGet());

	}
}