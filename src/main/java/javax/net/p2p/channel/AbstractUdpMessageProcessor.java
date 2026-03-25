package javax.net.p2p.channel;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedFile;
import io.netty.util.Attribute;
import io.netty.util.ReferenceCountUtil;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.interfaces.P2PMessageService;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.common.UdpFrameInbound;
import javax.net.p2p.server.ServerSendUdpMesageExecutor;
import javax.net.p2p.utils.SerializationUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractUdpMessageProcessor extends SimpleChannelInboundHandler<DatagramPacket> {

    protected final static HashMap<P2PCommand, P2PCommandHandler> HANDLER_REGISTRY_MAP = new HashMap<>();

    private static final List<String> CLASS_CACHE = new ArrayList<>();

    static {
        //注册命令处理器
        registerProcessors();
    }
    
    protected P2PMessageService messageService;
    
    
    protected int magic;

    protected Integer queueSize;
    protected boolean connected = false;
    
    
    
    protected final Map<InetSocketAddress,UdpFrameInbound> udpFrameInboundMap = new HashMap<>();
    
    //最近发送消息缓存
    protected final Map<InetSocketAddress,ByteBuf> lastMessageMap = new HashMap<>();
    
    protected final Map<Integer,AbstractLongTimedRequestAdapter> lastLongTimedRequestAdapterMap = new HashMap<>();
    
     protected final Map<Integer,AbstractStreamRequestAdapter> lastStreamRequestAdapterMap = new HashMap<>();
     
     protected Map<InetSocketAddress,ServerSendUdpMesageExecutor> asyncSendUdpMesageExecutorMap = new HashMap<>();
     
     protected Map<InetSocketAddress,ChannelFuture> lastSendMessageChannelFutureMap = new HashMap<>();
     
     protected final Map<Integer,ByteBuf> cachePingMap = new HashMap<>();
     
     protected final Map<Integer,ByteBuf> cachePongMap = new HashMap<>();
     
     protected long frameLastTransportSpeed;//上一个成功send数据帧的传输速率,字节/毫秒(mill),用于流控/帧超时重发
     
    private long frameStartTime;
    private long frameLengthInt;


    public AbstractUdpMessageProcessor(int magic,Integer queueSize) {
        this.magic = magic;
        this.queueSize = queueSize;
    }
    
    public AbstractUdpMessageProcessor(P2PMessageService messageService,int magic,Integer queueSize) {
        this.messageService = messageService;
        this.magic = magic;
        this.queueSize = queueSize;
    }

    public Map<InetSocketAddress, ByteBuf> getLastMessageMap() {
        return lastMessageMap;
    }
    
    

    public abstract void processMessage(ChannelHandlerContext ctx, DatagramPacket datagramPacket, P2PWrapper message);

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket datagramPacket) throws Exception {
        UdpFrameInbound bound = udpFrameInboundMap.get(datagramPacket.sender());
        if(bound==null){
            bound = UdpFrameInbound.build(this,ctx.channel(),datagramPacket.sender(),magic, queueSize);
            udpFrameInboundMap.put(datagramPacket.sender(), bound);
        }
        
        bound.channelRead0(ctx, datagramPacket);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        
            System.out.println(Integer.toHexString(magic)+" datagramPacket.sender() ->"+ctx.channel().remoteAddress()); 
            System.out.println(Integer.toHexString(magic)+" datagramPacket.recipient() ->"+ctx.channel().localAddress());  
        log.error(cause.getMessage(), cause);
        ctx.close();
        super.exceptionCaught(ctx, cause);
    }

    /**
     * udp消息特殊处理逻辑
     *
     * @param ctx
     * @param datagramPacket
     * @param response
     */
    public void sendResponse(ChannelHandlerContext ctx, DatagramPacket datagramPacket, P2PWrapper response) {

        Attribute<Integer> attrMagic = ctx.channel().attr(ChannelUtils.MAGIC);
        Integer magicChannel = attrMagic.get();
        if (magicChannel != null) {
            sendResponse(ctx.channel(), datagramPacket.sender(), response, magicChannel);
        } else {
            sendResponse(ctx.channel(), datagramPacket.sender(), response, magic);
        }
    }
    
    /**
     * 缓存远程端点的处理结果,以便远程端点超时请求重发
     * @param remoteAddess
     * @param buffer 
     */
    protected void cacheLastResponse(InetSocketAddress remoteAddess,ByteBuf buffer){
        buffer.retain();//pipeline会自动release buffer,计数+1
        buffer.markReaderIndex();
        lastMessageMap.put(remoteAddess, buffer);
    }
    
    /**
     * complete远程端点的处理结果
     * @param remoteAddess
     */
    public void completeLastResponse(InetSocketAddress remoteAddess){
        frameLastTransportSpeed = frameLengthInt/(System.currentTimeMillis()-frameStartTime);
        ByteBuf buffer = lastMessageMap.remove(remoteAddess);
        buffer.release();
        //ReferenceCountUtil.safeRelease(buffer);
        
    }
    
    public boolean isCcompleteLastResponse(InetSocketAddress remoteAddess){
        
        return  lastMessageMap.containsKey(remoteAddess);
    }
    
    /**
     * 重发远程端点的处理结果
     * @param ctx
     * @param remoteAddess
     */
    protected void retrieveLastResponse(ChannelHandlerContext ctx,InetSocketAddress remoteAddess){
        
        ByteBuf buffer = lastMessageMap.get(remoteAddess);
        buffer.resetReaderIndex();
        buffer.retain();
        if (log.isDebugEnabled()) {
                log.debug("retrieveLastResponse:{}", buffer.readableBytes());
        }
        long waitTimes = buffer.readableBytes()/frameLastTransportSpeed;
        ServerSendUdpMesageExecutor sendUdpMesageExecutor = asyncSendUdpMesageExecutorMap.get(remoteAddess);
        if(sendUdpMesageExecutor!=null){
            
        }
        sendResponse(ctx.channel(), remoteAddess, buffer);
        
    }

    /**
     * udp消息特殊处理逻辑
     *
     * @param channel
     * @param remoteAddess
     * @param response
     * @param magic
     */
    public void sendResponse(Channel channel, InetSocketAddress remoteAddess, P2PWrapper response, int magic) {
        try {
            if(lastMessageMap.containsKey(remoteAddess)){
                //远程端点尚未ACK,一般来说不应该存在新数据包,可以安全的丢弃之(比如心跳包等
                return;
            }
            ByteBuf buffer;
             //心跳消息特殊处理
            switch (response.getCommand()) {
                case HEART_PONG:
                    buffer = cachePongMap.get(magic);
                    if(buffer == null){
                        buffer = SerializationUtil.serializeToByteBuf(response, magic);
                        cachePongMap.put(magic, buffer);
                    }   buffer.retain();
                    break;
                case HEART_PING:
                    buffer = cachePingMap.get(magic);
                    if(buffer == null){
                        buffer = SerializationUtil.serializeToByteBuf(response, magic);
                        cachePingMap.put(magic, buffer);
                    }   buffer.retain();
                    break;
                default://有序发送(send -> ack/retrieve)消息
                    buffer = SerializationUtil.serializeToByteBuf(response, magic);
                    cacheLastResponse(remoteAddess,buffer);
                    break;
            }
            if (log.isDebugEnabled()) {
                log.debug("send response:{}", buffer.readableBytes());
            }
            sendResponse(channel, remoteAddess, buffer);
        } catch (Exception ex) {
            log.warn("{}消息处理异常:{},关闭channel:{}", response, ex, channel.id());
            try {
                channel.close();
            } catch (Exception ex2) {
                log.error(ex.getMessage());
            }
        }
    }
    
    
    public void sendResponse(Channel channel, InetSocketAddress remoteAddess, ByteBuf buffer) {
        try {
            frameStartTime = System.currentTimeMillis();
            frameLengthInt = buffer.readableBytes();
            //收到udp消息后，可通过此方式原路返回的方式返回消息，例如返回时间戳
            ChannelFuture cf = channel.writeAndFlush(new DatagramPacket(buffer, remoteAddess));
//            SocketAddress s = cf.channel().remoteAddress();
//            InetSocketAddress t = datagramPacket.sender();
            if (cf != null) {
                lastSendMessageChannelFutureMap.put(remoteAddess,cf);
                cf.addListener(new ChannelFutureListener() {
                    public void operationComplete(ChannelFuture future) {
                        lastSendMessageChannelFutureMap.remove(remoteAddess);
                        //System.out.println("底层IO执行完毕:future.isSuccess()=="+future.isSuccess());
                        // 等待直到底层IO执行完毕
                        if (future.isSuccess()) {
                            if (log.isDebugEnabled()) {
                                log.debug("socket io operationComplete response:{}", buffer.readableBytes());
                            }
                        } else {
                            log.warn("{}字节长度的消息处理未成功,可能原因:{},关闭channel:{}", buffer.readableBytes(), future.cause().getMessage(), channel.id());
                            channel.close();
                        }
                    }
                });
                cf.sync();
            }
        } catch (Exception ex) {
            log.warn("{}字节长度的消息处理异常:{},关闭channel:{}", buffer.readableBytes(), ex, channel.id());
            try {
                channel.close();
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

    public void sendPongMsg(ChannelHandlerContext ctx, DatagramPacket datagramPacket, int magic) {
        //Channel channel = ctx.channel();
        log.debug("channel {} send pong->\n{}", ctx.channel().id(), HEART_PONG);
        sendResponse(ctx.channel(), datagramPacket.sender(), HEART_PONG, magic);

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
        URL url = AbstractUdpMessageProcessor.class.getClassLoader().getResource(packageName.replaceAll("\\.", "/"));
        System.out.println("scan processors -> " + url.getFile());
        if (url.getFile().contains(".jar!")) {

            //file:/D:/dev/scala_pro/test/src/jedis-2.8.0.jar!/redis
            JarURLConnection urlConnection = (JarURLConnection) url.openConnection();
            // 从此jar包 得到一个枚举类
            Enumeration<JarEntry> entries = urlConnection.getJarFile().entries();
            // 遍历jar
            while (entries.hasMoreElements()) {
                // 获取jar里的一个实体 可以是目录 和一些jar包里的其他文件 如META-INF等文件
                JarEntry entry = entries.nextElement();
                //得到该jar文件下面的类实体
                if (entry.getName().startsWith(packageName.replaceAll("\\.", "/")) && entry.getName().endsWith(".class")) {
                    System.out.println(entry.getName());
                    // 因为scan 就是/  ， 所有把 file的 / 转成  \   统一都是：  /
                    String fPath = entry.getName().replaceAll("\\\\", "/");
                    // 把 包路径 前面的 盘符等 去掉
                    String packName = fPath;
                    int m = fPath.indexOf(":");
                    if (m > 0) {
                        packName = fPath.substring(m);
                    }
                    // 去掉后缀.class ，并且把 / 替换成 .    这样就是  com.hadluo.A 格式了 ， 就可以用Class.forName加载了
                    packName = packName.replace(".class", "").replaceAll("/", ".");
                    // 根据名称加载类
                    CLASS_CACHE.add(packName);
                }
            }
        } else {
            File dir = new File(url.getFile());
            for (File file : dir.listFiles()) {
                //System.out.println(file);
                //如果是一个文件夹，继续递归
                if (file.isDirectory()) {
                    scannerClass(packageName + "." + file.getName());
                } else {
                    CLASS_CACHE.add(packageName + "." + file.getName().replace(".class", "").trim());
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
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        connected = false;
        for(Map.Entry<InetSocketAddress, UdpFrameInbound> entry:udpFrameInboundMap.entrySet()){
            entry.getValue().close();
        }
        super.handlerRemoved(ctx);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        connected = true;
        super.handlerAdded(ctx);
    }

    public boolean isConnected() {
        return connected;
    }

}
