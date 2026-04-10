package javax.net.p2p.channel;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.Attribute;
import java.security.cert.CertificateException;
import javax.net.p2p.codec.P2PWrapperEncoder;
import javax.net.p2p.codec.P2PWrapperSecureDecoder;
import javax.net.p2p.codec.P2PWrapperSecureEncoder;
import javax.net.p2p.config.P2PConfig;
import javax.net.p2p.config.TcpOptimizationConfig;
import javax.net.p2p.monitor.UdpMonitorDecorator;
import javax.net.p2p.udp.UdpReliabilityHandler;
import javax.net.ssl.SSLException;

/**
 * netty nio通道流水线处理器链初始化,配置初始化集中处理
 *
 * @author karl
 */
public class PipelineInitializer  {

     // 配置 SSL.
    static SslContext SSL_CTX_CLIENT = null;
    static SslContext SSL_CTX_SERVER = null;
    /**
     * 流水线处理器链初始化（UDP带可靠性）
     * @param channel
     * @param businessProseccor 最后一个业务逻辑处理器,应做好相关资源清理
     */
    public static void initProcessors(SocketChannel channel,SimpleChannelInboundHandler businessProseccor)  {
//        channel.config().setKeepAlive(true);
//        channel.config().setTcpNoDelay(true);
//        channel.config().setReceiveBufferSize(SystemConfig.getNettyReceiveBufferSize());
//        channel.config().setSendBufferSize(SystemConfig.getNettyReceiveBufferSize());
        //ChannelOption.SO_RCVBUF, 4096
//		实例化一个 IdleStateHandler 需要提供三个参数:
//
//readerIdleTimeSeconds, 读超时. 即当在指定的时间间隔内没有从 Channel 读取到数据时, 会触发一个 READER_IDLE 的 IdleStateEvent 事件.
//
//writerIdleTimeSeconds, 写超时. 即当在指定的时间间隔内没有数据写入到 Channel 时, 会触发一个 WRITER_IDLE 的 IdleStateEvent 事件.
//
//allIdleTimeSeconds, 读/写超时. 即当在指定的时间间隔内没有读或写操作时, 会触发一个 ALL_IDLE 的 IdleStateEvent 事件.
        channel.pipeline()
            .addLast(new IdleStateHandler(0, 0, 5)) // 5 seconds read idle time
            //                .addLast(new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4,0,4))
            //                .addLast(new LengthFieldPrepender(4))
            .addLast("P2PWrapperDecoder", new P2PWrapperSecureDecoder())
            .addLast("P2PWrapperEncoder", new P2PWrapperSecureEncoder())
            //				.addLast("encoder", new P2PWrapperDecoder())
            //                .addLast("decoder", new P2PWrapperEncoder())
            .addLast("BusinessProseccor", businessProseccor);
        //为刚刚创建的channel，初始化channel属性
        Attribute<SimpleChannelInboundHandler> attribute = channel.attr(ChannelUtils.BUSINESS_PROCESSOR);
        attribute.set(businessProseccor);
        Attribute<Integer> attributeMagic = channel.attr(ChannelUtils.MAGIC);
        attributeMagic.set(P2PWrapperEncoder.MAGIC);
        //        Attribute<Map<Integer,Object>> attribute = channel.attr(ChannelUtils.DATA_MAP_ATTRIBUTEKEY);
//        ConcurrentHashMap<Integer, Object> dataMap = new ConcurrentHashMap<>();
//        attribute.set(dataMap);
//        Attribute<AtomicInteger> sequence = channel.attr(ChannelUtils.MESSAGE_SEQUENCE);
//        AtomicInteger seq = new AtomicInteger();
//        sequence.set(seq);

    }
    
    public static void initServerOptions(ServerBootstrap bootstrap){
        // 使用优化的TCP配置
        TcpOptimizationConfig.initServerOptimizedOptions(bootstrap);
    }
    
    public static void initClientOptions(Bootstrap bootstrap){
        // 使用优化的TCP配置
        TcpOptimizationConfig.initClientOptimizedOptions(bootstrap);
    }
    
    
   
    
    
     /**
     * 流水线处理器链初始化
     * @param channel
     * @param businessProseccor 最后一个业务逻辑处理器,应做好相关资源清理
     * @param defaultMagic
     * @param enableReliability 是否启用UDP可靠性机制
     */
    public static void initProcessorsUdpWithReliability(NioDatagramChannel channel, 
            SimpleChannelInboundHandler businessProseccor, int defaultMagic, 
            boolean enableReliability)  {
        UdpReliabilityHandler reliabilityHandler = new UdpReliabilityHandler(enableReliability);
        
        channel.pipeline()
            .addLast(new IdleStateHandler(0, 0, 5)) // 5 seconds read idle time
            .addLast("UdpReliabilityHandler", reliabilityHandler)
            .addLast("UdpOutboundMonitor", UdpMonitorDecorator.createOutboundMonitor())
            .addLast("UdpInboundMonitor", UdpMonitorDecorator.createInboundMonitor())
            .addLast("BusinessProseccor", businessProseccor);
        
        // 为刚刚创建的channel，初始化channel属性
        Attribute<SimpleChannelInboundHandler> attribute = channel.attr(ChannelUtils.BUSINESS_PROCESSOR);
        attribute.set(businessProseccor);
        Attribute<Integer> attributeMagic = channel.attr(ChannelUtils.MAGIC);
        attributeMagic.set(defaultMagic);
        
        // 保存可靠性处理器引用
        Attribute<UdpReliabilityHandler> reliabilityAttr = channel.attr(ChannelUtils.UDP_RELIABILITY_HANDLER);
        reliabilityAttr.set(reliabilityHandler);
    }
    
    /**
     * 流水线处理器链初始化（UDP带自定义可靠性管理器）
     * @param channel
     * @param businessProseccor 最后一个业务逻辑处理器,应做好相关资源清理
     * @param defaultMagic
     * @param reliabilityManager 自定义的可靠性管理器
     */
    public static void initProcessorsUdpWithReliability(NioDatagramChannel channel, 
            SimpleChannelInboundHandler businessProseccor, int defaultMagic, 
            javax.net.p2p.udp.UdpReliabilityManager reliabilityManager)  {
        UdpReliabilityHandler reliabilityHandler = new UdpReliabilityHandler(reliabilityManager);
        
        channel.pipeline()
            .addLast(new IdleStateHandler(0, 0, 5)) // 5 seconds read idle time
            .addLast("UdpReliabilityHandler", reliabilityHandler)
            .addLast("UdpOutboundMonitor", UdpMonitorDecorator.createOutboundMonitor())
            .addLast("UdpInboundMonitor", UdpMonitorDecorator.createInboundMonitor())
            .addLast("BusinessProseccor", businessProseccor);
        
        // 为刚刚创建的channel，初始化channel属性
        Attribute<SimpleChannelInboundHandler> attribute = channel.attr(ChannelUtils.BUSINESS_PROCESSOR);
        attribute.set(businessProseccor);
        Attribute<Integer> attributeMagic = channel.attr(ChannelUtils.MAGIC);
        attributeMagic.set(defaultMagic);
        
        // 保存可靠性处理器引用
        Attribute<UdpReliabilityHandler> reliabilityAttr = channel.attr(ChannelUtils.UDP_RELIABILITY_HANDLER);
        reliabilityAttr.set(reliabilityHandler);
    }
    
   
    
    public static void initClientOptionsUdp(Bootstrap bootstrap)  {
        
                // 配置连接timeout的时间
                bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                //.option(ChannelOption.SO_KEEPALIVE, true)
                
                .option(ChannelOption.SO_RCVBUF, P2PConfig.BUFFER_BLOCK_SIZE)
                .option(ChannelOption.SO_SNDBUF, P2PConfig.BUFFER_BLOCK_SIZE)
                //配置主线程buffer ALLOCATOR
            	.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
}
    
     /**
     * ssl加密流水线处理器链初始化-server
     * @param channel
     * @param businessProseccor 最后一个业务逻辑处理器,应做好相关资源清理
     * @param defaultMagic
     * @throws java.security.cert.CertificateException
     * @throws javax.net.ssl.SSLException
     */
    public static void initSSLServerProcessorsUdp(NioDatagramChannel channel,SimpleChannelInboundHandler businessProseccor,int defaultMagic) throws CertificateException, SSLException  {
        
        if(SSL_CTX_SERVER==null){
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            SSL_CTX_SERVER = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
                .build();
        }
        SslHandler sslHandler = SSL_CTX_SERVER.newHandler(channel.alloc());
         
        //pipeline.addLast();
        channel.pipeline()
            .addLast(new IdleStateHandler(0, 0, 5)) // 5 seconds read idle time
                // 添加SSL处理机制
         .addLast(sslHandler)  
        .addLast("BusinessProseccor", businessProseccor);
        //为刚刚创建的channel，初始化channel属性
        Attribute<SimpleChannelInboundHandler> attribute = channel.attr(ChannelUtils.BUSINESS_PROCESSOR);
        attribute.set(businessProseccor);
        Attribute<Integer> attributeMagic = channel.attr(ChannelUtils.MAGIC);
        attributeMagic.set(defaultMagic);
      

    }
    
}
