
package javax.net.p2p.channel;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.Attribute;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import javax.net.p2p.codec.P2PWrapperDecoder;
import javax.net.p2p.codec.P2PWrapperEncoder;
import javax.net.p2p.codec.TransportLimitEncoder;
import javax.net.p2p.config.P2PConfig;
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
    
    // 配置 quic SSL.
    static QuicSslContext SSL_CTX_CLIENT_QUIC = null;
    static QuicSslContext SSL_CTX_SERVER_QUIC = null;
    /**
     * 流水线处理器链初始化
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
            .addLast("P2PWrapperDecoder", new P2PWrapperDecoder())
            .addLast("P2PWrapperEncoder", new P2PWrapperEncoder())
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
    
    /**
     * 流水线处理器链初始化
     * @param channel
     * @param businessProseccor 最后一个业务逻辑处理器,应做好相关资源清理
     */
    public static void initProcessorsQuic(QuicStreamChannel channel,SimpleChannelInboundHandler businessProseccor)  {
        channel.pipeline()
            .addLast(new IdleStateHandler(0, 0, 5)) // 5 seconds read idle time
            //                .addLast(new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4,0,4))
            //                .addLast(new LengthFieldPrepender(4))
//            .addLast("P2PWrapperDecoder", new P2PWrapperDecoder())
//            .addLast("P2PWrapperEncoder", new P2PWrapperEncoder())
            //				.addLast("encoder", new P2PWrapperDecoder())
            //                .addLast("decoder", new P2PWrapperEncoder())
            .addLast("BusinessProseccor", businessProseccor);
        //为刚刚创建的channel，初始化channel属性
        Attribute<SimpleChannelInboundHandler> attribute = channel.attr(ChannelUtils.BUSINESS_PROCESSOR);
        attribute.set(businessProseccor);
        Attribute<Integer> attributeMagic = channel.attr(ChannelUtils.MAGIC);
        attributeMagic.set(P2PWrapperEncoder.MAGIC);

    }
    
    public static void initServerOptions(ServerBootstrap bootstrap){
        
                // 配置连接timeout的时间
                bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                // 临时存放已完成三次握手的请求的队列的最大长度。SO_BACKLOG
                // 如果未设置或所设置的值小于1，Java将使用默认值50。
                // 如果大于队列的最大长度，请求会被拒绝
                //                    .option(ChannelOption.SO_BACKLOG, 1024)  
                //配置主线程buffer ALLOCATOR
            	.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
               // .childOption(ChannelOption.SO_RCVBUF, P2PConfig.BUFFER_BLOCK_SIZE)
               // .childOption(ChannelOption.SO_SNDBUF, P2PConfig.BUFFER_BLOCK_SIZE)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                   
            .childOption(ChannelOption.TCP_NODELAY, true)
             .childOption(ChannelOption.SO_KEEPALIVE, true)
                     //配置io工作通道(线程)的buffer ALLOCATOR
                    .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
    }
    
    public static void initClientOptions(Bootstrap bootstrap){
        
                // 配置连接timeout的时间
                bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                // 临时存放已完成三次握手的请求的队列的最大长度。SO_BACKLOG
                // 如果未设置或所设置的值小于1，Java将使用默认值50。
                // 如果大于队列的最大长度，请求会被拒绝
                //                    .option(ChannelOption.SO_BACKLOG, 1024)  
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                
               // .childOption(ChannelOption.SO_RCVBUF, P2PConfig.BUFFER_BLOCK_SIZE)
               // .childOption(ChannelOption.SO_SNDBUF, P2PConfig.BUFFER_BLOCK_SIZE)
                //配置主线程buffer ALLOCATOR
            	.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
    }
    
    
   
    
    
     /**
     * 流水线处理器链初始化
     * @param channel
     * @param businessProseccor 最后一个业务逻辑处理器,应做好相关资源清理
     * @param defaultMagic
     */
    public static void initProcessorsUdp(NioDatagramChannel channel,SimpleChannelInboundHandler businessProseccor,int defaultMagic)  {
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
               // .addLast("TransportLimitEncoder", new TransportLimitEncoder())
       //     .addLast("P2PWrapperDecoder", new P2PWrapperDecoder())
       //     .addLast("P2PWrapperEncoder", new P2PWrapperEncoder())
           
        .addLast("BusinessProseccor", businessProseccor);
        //.addLast(worker,"BusinessProseccor", businessProseccor);
        //为刚刚创建的channel，初始化channel属性
        Attribute<SimpleChannelInboundHandler> attribute = channel.attr(ChannelUtils.BUSINESS_PROCESSOR);
        attribute.set(businessProseccor);
        Attribute<Integer> attributeMagic = channel.attr(ChannelUtils.MAGIC);
        attributeMagic.set(defaultMagic);
        //        Attribute<Map<Integer,Object>> attribute = channel.attr(ChannelUtils.DATA_MAP_ATTRIBUTEKEY);
//        ConcurrentHashMap<Integer, Object> dataMap = new ConcurrentHashMap<>();
//        attribute.set(dataMap);
//        Attribute<AtomicInteger> sequence = channel.attr(ChannelUtils.MESSAGE_SEQUENCE);
//        AtomicInteger seq = new AtomicInteger();
//        sequence.set(seq);

    }
    
    /**
     * ssl加密流水线处理器链初始化-client
     * @param channel
     * @param businessProseccor 最后一个业务逻辑处理器,应做好相关资源清理
     * @param defaultMagic
     */
    public static void initSSLClientProcessorsUdp(NioDatagramChannel channel,SimpleChannelInboundHandler businessProseccor,int defaultMagic) throws SSLException  {
        if(SSL_CTX_CLIENT==null){
            SSL_CTX_CLIENT = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        }
        InetSocketAddress localAddress = (InetSocketAddress) channel.remoteAddress();
        SslHandler sslHandler = SSL_CTX_CLIENT.newHandler(channel.alloc(), localAddress.getHostString(), localAddress.getPort());
         
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
    
     /**
     * ssl加密流水线处理器链初始化-server
     * @param channel
     * @param businessProseccor 最后一个业务逻辑处理器,应做好相关资源清理
     * @param defaultMagic
     * @throws java.security.cert.CertificateException
     * @throws javax.net.ssl.SSLException
     */
    public static void initSSLServerProcessorsQuic(NioDatagramChannel channel,SimpleChannelInboundHandler businessProseccor,int defaultMagic) throws CertificateException, SSLException  {
        
        if(SSL_CTX_SERVER_QUIC==null){
             SelfSignedCertificate selfSignedCertificate = new SelfSignedCertificate();
        SSL_CTX_SERVER_QUIC = QuicSslContextBuilder.forServer(
                selfSignedCertificate.privateKey(), null, selfSignedCertificate.certificate()).build();
               // .applicationProtocols("http/0.9").build();
        }
        SslHandler sslHandler = SSL_CTX_SERVER_QUIC.newHandler(channel.alloc());
         
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
    
   
    
    public static void initOptionsUdp(Bootstrap bootstrap)  {
        
                // 配置连接timeout的时间
                bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                // 临时存放已完成三次握手的请求的队列的最大长度。SO_BACKLOG
                // 如果未设置或所设置的值小于1，Java将使用默认值50。
                .option(ChannelOption.SO_BROADCAST, true)    //广播
                //.option(ChannelOption.SO_KEEPALIVE, true)
                
                .option(ChannelOption.SO_RCVBUF, P2PConfig.BUFFER_BLOCK_SIZE)
                .option(ChannelOption.SO_SNDBUF, P2PConfig.BUFFER_BLOCK_SIZE)
                //配置主线程buffer ALLOCATOR
            	.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
    }

}
