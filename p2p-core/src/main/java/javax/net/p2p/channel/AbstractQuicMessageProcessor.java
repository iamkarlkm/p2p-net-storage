package javax.net.p2p.channel;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedFile;
import io.netty.util.Attribute;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.ChannelUtils;
import javax.net.p2p.auth.utils.AuthCrypto;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SerializationUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractQuicMessageProcessor extends ByteToMessageDecoder {

    protected final static HashMap<P2PCommand, P2PCommandHandler> HANDLER_REGISTRY_MAP = new HashMap<>();

    private static final Set<String> CLASS_CACHE = new HashSet<>();

    static {
        //注册命令处理器
        registerProcessors();
    }
    protected int headerSize = 8;
    protected int frameLengthInt = -1;

    protected int magic;

    protected int queueSize;
    protected boolean connected = false;


    public AbstractQuicMessageProcessor(int magic,int queueSize) {
        this.magic = magic;
        this.queueSize = queueSize;
    }

    protected abstract void processMessage(ChannelHandlerContext ctx, P2PWrapper message);

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (frameLengthInt == -1) {
            if (in.readableBytes() < headerSize) {
                return;
            }
            frameLengthInt = in.readInt();
            magic = in.readInt();
            Attribute<Integer> attrMagic = ctx.channel().attr(ChannelUtils.MAGIC);
            Integer expected = attrMagic.get();
            if (expected == null || magic != expected) {
                in.skipBytes(in.readableBytes());
                frameLengthInt = -1;
                P2PWrapper response = P2PWrapper.build(0, P2PCommand.INVALID_PROTOCOL, 0);
                sendResponse(ctx, response, magic);
                return;
            }
        }

        if (in.readableBytes() < frameLengthInt) {
            return;
        }

        try {
            byte[] data = new byte[frameLengthInt];
            in.readBytes(data);
            byte[] key = ctx.channel().attr(ChannelUtils.XOR_KEY).get();
            if (key != null && key.length > 0) {
                AuthCrypto.xorInPlace(data, key);
            }
            P2PWrapper request = SerializationUtil.deserialize(P2PWrapper.class, data);
            processMessage(ctx, request);
        } catch (Exception ex) {
            log.warn("resolve error:{}, close channel:{}", ex.getMessage(), ctx.channel().id());
            ctx.close();
        } finally {
            frameLengthInt = -1;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error(cause.getMessage(), cause);
        ctx.close();
        super.exceptionCaught(ctx, cause);
    }

    /**
     * udp消息特殊处理逻辑
     *
     * @param ctx
     * @param response
     */
    public void sendResponse(ChannelHandlerContext ctx,  P2PWrapper response) {

        Attribute<Integer> attrMagic = ctx.channel().attr(ChannelUtils.MAGIC);
        Integer magicChannel = attrMagic.get();
        if (magicChannel != null) {
            sendResponse(ctx, response, magicChannel);
        } else {
            sendResponse(ctx, response, magic);
        }
    }

    /**
     * udp quic消息特殊处理逻辑
     *
     * @param ctx
     * @param response
     * @param magic
     */
    public static void sendResponse(ChannelHandlerContext ctx,  P2PWrapper response, int magic) {
        try {
            ByteBuf buffer = SerializationUtil.serializeToByteBuf(response, magic);
            byte[] key = ctx.channel().attr(ChannelUtils.XOR_KEY).get();
            if (response.getCommand() != P2PCommand.HAND) {
                Boolean plain = ctx.channel().attr(ChannelUtils.HANDSHAKE_PLAINTEXT_RESP).get();
                if (plain != null && plain) {
                    ctx.channel().attr(ChannelUtils.HANDSHAKE_PLAINTEXT_RESP).set(false);
                    key = null;
                }
            }
            if (key != null && key.length > 0 && response.getCommand() != P2PCommand.HAND) {
                int start = buffer.readerIndex() + 8;
                int end = buffer.writerIndex();
                int keyLen = key.length;
                int j = 0;
                for (int i = start; i < end; i++) {
                    buffer.setByte(i, buffer.getByte(i) ^ key[j]);
                    j++;
                    if (j >= keyLen) {
                        j = 0;
                    }
                }
            }
            //收到udp消息后，可通过此方式原路返回的方式返回消息，例如返回时间戳
            ChannelFuture cf = ctx.writeAndFlush(buffer);
//            SocketAddress s = cf.channel().remoteAddress();
//            InetSocketAddress t = datagramPacket.sender();
            if (cf != null) {
                cf.addListener(new ChannelFutureListener() {
                    public void operationComplete(ChannelFuture future) {
                        //System.out.println("底层IO执行完毕:future.isSuccess()=="+future.isSuccess());
                        // 等待直到底层IO执行完毕
                        if (future.isSuccess()) {
                            if (log.isDebugEnabled()) {
                                log.debug("socket io operationComplete response:{}", response);
                            }
                            log.info("socket io operationComplete response:{}:{}", response, ctx.channel().id());
                        } else {
                            log.warn("{}消息处理未成功,可能原因:{},关闭channel:{}", response, future.cause().getMessage(), ctx.channel().id());
                            ctx.close();
                        }
                    }
                });
                cf.sync();
            }
        } catch (Exception ex) {
            log.warn("{}消息处理异常:{},关闭channel:{}", response, ex, ctx.channel().id());
            try {
                ctx.close();
            } catch (Exception ex2) {
                log.error(ex.getMessage());
            }
        }
    }
    
    
    
    private void fileRegion(ChannelHandlerContext ctx) throws IOException{
        RandomAccessFile raf = null;
        long length = -1;
        try {
            raf = new RandomAccessFile("", "r");
            length = raf.length();
        } catch (Exception e) {
            ctx.writeAndFlush("ERR: " + e.getClass().getSimpleName() + ": " + e.getMessage() + '\n');
            return;
        } finally {
            if (length < 0 && raf != null) {
                raf.close();
            }
        }

        ctx.write("OK: " + raf.length() + '\n');
        if (ctx.pipeline().get(SslHandler.class) == null) {
            // 传输文件使用了 DefaultFileRegion 进行写入到 NioSocketChannel 中
            ctx.write(new DefaultFileRegion(raf.getChannel(), 0, length));
        } else {
            // SSL enabled - cannot use zero-copy file transfer.
            ctx.write(new ChunkedFile(raf));
        }
    }

    private static final P2PWrapper HEART_PONG = P2PWrapper.build(0, P2PCommand.HEART_PONG);
    //private static final byte[] HEART_PONG_BYTES = SerializationUtil.serialize(HEART_PONG);

    public void sendPongMsg(ChannelHandlerContext ctx, int magic) {
        //Channel channel = ctx.channel();
        log.debug("channel {} send pong->\n{}", ctx.channel().id(), HEART_PONG);
        sendResponse(ctx,  HEART_PONG, magic);

    }

    public static void registerProcessors() {
        try {
            scannerClass("javax.net.p2p.server.handler");
            doRegister();
            log.info("HANDLER_REGISTRY_MAP ->\n{}", HANDLER_REGISTRY_MAP);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static void scannerClass(String packageName) throws Exception {
        String resourcePath = packageName.replaceAll("\\.", "/");
        Enumeration<URL> urls = AbstractQuicMessageProcessor.class.getClassLoader().getResources(resourcePath);
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            if (url == null) {
                continue;
            }
            System.out.println("scan processors -> " + url.getFile());
            if (url.getFile().contains(".jar!")) {
                JarURLConnection urlConnection = (JarURLConnection) url.openConnection();
                Enumeration<JarEntry> entries = urlConnection.getJarFile().entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().startsWith(resourcePath) && entry.getName().endsWith(".class")) {
                        String fPath = entry.getName().replaceAll("\\\\", "/");
                        String packName = fPath;
                        int m = fPath.indexOf(":");
                        if (m > 0) {
                            packName = fPath.substring(m);
                        }
                        packName = packName.replace(".class", "").replaceAll("/", ".");
                        CLASS_CACHE.add(packName);
                    }
                }
            } else {
                File dir = new File(url.getFile());
                File[] files = dir.listFiles();
                if (files == null) {
                    continue;
                }
                for (File file : files) {
                    if (file.isDirectory()) {
                        scannerClass(packageName + "." + file.getName());
                    } else {
                        CLASS_CACHE.add(packageName + "." + file.getName().replace(".class", "").trim());
                    }
                }
            }
        }
    }

    private static void doRegister() {
        if (CLASS_CACHE.isEmpty()) {
            return;
        }

        for (String className : CLASS_CACHE) {
            try {
                Class<?> clazz = Class.forName(className);
                if (P2PCommandHandler.class.isAssignableFrom(clazz)) {
                    //P2PCommandHandler handler = clazz.newInstance();
                    P2PCommandHandler handler = (P2PCommandHandler) clazz.getDeclaredConstructor().newInstance();
                    if (HANDLER_REGISTRY_MAP.get(handler.getCommand()) == null) {
                        HANDLER_REGISTRY_MAP.put(handler.getCommand(), handler);
                    } else {
                        throw new RuntimeException("P2PCommandHandler register confilct:" + handler.getCommand()
                                + " " + className
                                + " <> " + HANDLER_REGISTRY_MAP.get(handler.getCommand()));
                    }
                }

                //Class<?> interfaces = clazz.getInterfaces()[0];
                //registryMap.put(interfaces.getName(), clazz.newInstance()); 
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        connected = true;
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        connected = false;
        super.channelInactive(ctx);
    }

    public boolean isConnected() {
        return connected;
    }

}
