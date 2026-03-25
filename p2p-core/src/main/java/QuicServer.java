import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicServerCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.internal.SystemPropertyUtil;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * quic服务端
 * author heliang
 */
public class QuicServer {
/** Current time minus 1 year, just in case software clock goes back due to time synchronization */
    private static final Date DEFAULT_NOT_BEFORE = new Date(SystemPropertyUtil.getLong(
            "io.netty.selfSignedCertificate.defaultNotBefore", System.currentTimeMillis() - 86400000L * 365));
    /** The maximum possible value in X.509 specification: 9999-12-31 23:59:59 */
    private static final Date DEFAULT_NOT_AFTER = new Date(SystemPropertyUtil.getLong(
            "io.netty.selfSignedCertificate.defaultNotAfter", 253402300799000L));
    public static void main(String[] args)throws Exception {
         final KeyPair keypair;
         int bits = 2048;
         SecureRandom random = ThreadLocalInsecureRandom.current();
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(bits, random);
            keypair = keyGen.generateKeyPair();
        } catch (Exception e) {
            // Should not reach here because every Java implementation must have RSA and EC key pair generator.
            throw new Error(e);
        }
        String[] paths;
        try {
            // Try Bouncy Castle first as otherwise we will see an IllegalAccessError on more recent JDKs.
            //paths = BouncyCastleSelfSignedCertGenerator.generate(
            //        "localhost", keypair, random, DEFAULT_NOT_BEFORE, DEFAULT_NOT_AFTER, "RSA");
        } catch (Throwable t) {
//            logger.debug("Failed to generate a self-signed X.509 certificate using Bouncy Castle:", t);
//            try {
//                // Try the OpenJDK's proprietary implementation.
//                paths = OpenJdkSelfSignedCertGenerator.generate(fqdn, keypair, random, notBefore, notAfter, algorithm);
//            } catch (Throwable t2) {
//                logger.debug("Failed to generate a self-signed X.509 certificate using sun.security.x509:", t2);
//                final CertificateException certificateException = new CertificateException(
//                        "No provider succeeded to generate a self-signed certificate. " +
//                                "See debug log for the root cause.", t2);
//                ThrowableUtil.addSuppressed(certificateException, t);
//                throw certificateException;
//            }
        }
        SelfSignedCertificate selfSignedCertificate = new SelfSignedCertificate();
        QuicSslContext context = QuicSslContextBuilder.forServer(
                selfSignedCertificate.privateKey(), null, selfSignedCertificate.certificate())
                .applicationProtocols("http/0.9").build();

        NioEventLoopGroup group = new NioEventLoopGroup(1);
        //创建quic服务端编码器
        ChannelHandler codec = new QuicServerCodecBuilder().sslContext(context)
                .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
                //最大数量配置
                .initialMaxData(10000000)
                .initialMaxStreamDataBidirectionalLocal(1000000)
                .initialMaxStreamDataBidirectionalRemote(1000000)
                .initialMaxStreamsBidirectional(100)
                .initialMaxStreamsUnidirectional(100)
                // 设置一个令牌处理程序。在生产系统中，您可能希望实现和提供您的定制
                //.tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                //添加到QuicChannel管道中的ChannelHandler。
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelActive(ChannelHandlerContext ctx) {
                        QuicChannel channel = (QuicChannel) ctx.channel();
                    }

                    public void channelInactive(ChannelHandlerContext ctx) {
                        ((QuicChannel) ctx.channel()).collectStats().addListener(new GenericFutureListener(){

                            @Override
                            public void operationComplete(Future future) throws Exception {
                                 if(future.isSuccess())
                                 {
                                     System.out.println("Connection closed:"+future.getNow());
                                 }
                            }
                        });
                    }

                    @Override
                    public boolean isSharable() {
                        return true;
                    }
                })
                .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                    @Override
                    protected void initChannel(QuicStreamChannel ch)  {
                        // 在这里添加一个LineBasedFrameDecoder，因为我们只是想做一些简单的HTTP 0.9处理。
                        ch.pipeline().addLast(new LineBasedFrameDecoder(1024))
                                .addLast(new ChannelInboundHandlerAdapter() {
                                    @Override
                                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                        ByteBuf byteBuf = (ByteBuf) msg;
                                        try {
                                            if (byteBuf.toString(CharsetUtil.US_ASCII).trim().equals("GET /")) {
                                                ByteBuf buffer = ctx.alloc().directBuffer();
                                                buffer.writeCharSequence("Hello World!何亮\r\n", CharsetUtil.UTF_8);
                                                // 写入缓冲区并通过写入FIN关闭输出。
                                                ctx.writeAndFlush(buffer).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
                                            }
                                        } finally {
                                            byteBuf.release();
                                        }
                                    }
                                });
                    }
                }).build();
        try {
            Bootstrap bs = new Bootstrap();
            Channel channel = bs.group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(codec)
                    //设置端口号 9999
                    .bind(new InetSocketAddress(9999)).sync().channel();
            channel.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }
}
