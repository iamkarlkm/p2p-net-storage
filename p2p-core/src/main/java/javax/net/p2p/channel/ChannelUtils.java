/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package javax.net.p2p.channel;

import io.netty.channel.Channel;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.p2p.client.ClientMessageProcessor;
import javax.net.p2p.client.ClientUdpMessageProcessor;
import javax.net.p2p.server.ServerUdpMessageProcessor;
import javax.net.p2p.udp.UdpReliabilityHandler;

/**
 * Netty Channel 属性工具类：统一存取 seq、magic、auth 信息与业务处理器等 AttributeKey。
 */
public class ChannelUtils {
    public static final int MESSAGE_LENGTH = 16;
    public static final AttributeKey<Map<Integer, Object>> DATA_MAP_ATTRIBUTEKEY = AttributeKey.valueOf("DATA_MAP");
    
    public static final AttributeKey<AtomicInteger> MESSAGE_SEQUENCE = AttributeKey.valueOf("MESSAGE_SEQUENCE");
	
	public static final AttributeKey<Integer> MAGIC = AttributeKey.valueOf("MAGIC");

    public static final AttributeKey<byte[]> XOR_KEY = AttributeKey.valueOf("XOR_KEY");

    public static final AttributeKey<String> AUTH_USER_ID = AttributeKey.valueOf("AUTH_USER_ID");

    public static final AttributeKey<Boolean> AUTH_LOGGED_IN = AttributeKey.valueOf("AUTH_LOGGED_IN");

    public static final AttributeKey<Boolean> HANDSHAKE_PLAINTEXT_RESP = AttributeKey.valueOf("HANDSHAKE_PLAINTEXT_RESP");
	
	public static final AttributeKey<SimpleChannelInboundHandler> BUSINESS_PROCESSOR = AttributeKey.valueOf("BUSINESS_PROCESSOR");
    
    public static final AttributeKey<UdpReliabilityHandler> UDP_RELIABILITY_HANDLER = AttributeKey.valueOf("UDP_RELIABILITY_HANDLER");

	// public static final AttributeKey<ClientMessageProcessor> CLIENT_MESSAGE_PROCESSOR = AttributeKey.valueOf("CLIENT_MESSAGE_PROCESSOR");
	 
	// public static final AttributeKey<MyClientPoolMessagerProcessor> MY_CLIENT_MESSAGE_PROCESSOR = AttributeKey.valueOf("MY_CLIENT_MESSAGE_PROCESSOR");
    
	// public static final AttributeKey<ClientUdpMessageProcessor> CLIENT_UDP_MESSAGE_PROCESSOR = AttributeKey.valueOf("CLIENT_UDP_MESSAGE_PROCESSOR");
    
    
    public static <T> void putCallbackToDataMap(Channel channel, int seq, T callback) {
        channel.attr(DATA_MAP_ATTRIBUTEKEY).get().put(seq, callback);
    }
	
	 public static <T> T removeCallback(Channel channel, int seq) {
        return (T) channel.attr(DATA_MAP_ATTRIBUTEKEY).get().remove(seq);
    }
    
    public static AtomicInteger getSequenceWithChannel(Channel channel) {
        return channel.attr(MESSAGE_SEQUENCE).get();
    }
    
    public static int getSequenceNextVal(Channel channel) {
        return channel.attr(MESSAGE_SEQUENCE).get().getAndIncrement();
    }

  
    
    /**
     * 从channel属性map中获取业务逻辑处理器
     * @param channel 
     * @return  
     */
    public static ClientMessageProcessor getClientMessageProcessor(Channel channel){
         Attribute<SimpleChannelInboundHandler> attribute =  channel.attr(ChannelUtils.BUSINESS_PROCESSOR);
            ClientMessageProcessor businessHandler = (ClientMessageProcessor) attribute.get();
        return businessHandler;
    }
    
    /**
     * 从channel属性map中获取业务逻辑处理器
     * @param channel 
     * @return  
     */
    public static ClientUdpMessageProcessor getClientUdpMessageProcessor(Channel channel){
         Attribute<SimpleChannelInboundHandler> attribute =  channel.attr(ChannelUtils.BUSINESS_PROCESSOR);
            ClientUdpMessageProcessor businessHandler = (ClientUdpMessageProcessor) attribute.get();
        return businessHandler;
    }
    
    /**
     * 从channel属性map中获取业务逻辑处理器
     * @param channel 
     * @return  
     */
    public static ServerUdpMessageProcessor getServerUdpMessageProcessor(Channel channel){
         Attribute<SimpleChannelInboundHandler> attribute =  channel.attr(ChannelUtils.BUSINESS_PROCESSOR);
            ServerUdpMessageProcessor businessHandler = (ServerUdpMessageProcessor) attribute.get();
        return businessHandler;
    }
    
}
