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
import io.netty.util.concurrent.ScheduledFuture;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.api.P2PServiceCategory;
import javax.net.p2p.config.P2PConfig;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.interfaces.P2PMessageService;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.common.UdpFrameInbound;
import javax.net.p2p.server.ServerSendUdpMesageExecutor;
import javax.net.p2p.server.P2PServiceManager;
import javax.net.p2p.utils.SerializationUtil;
import lombok.extern.slf4j.Slf4j;
import javax.net.p2p.utils.XXHashUtil;

/**
 *  UDP消息处理基本类
 * @author Administrator
 */
@Slf4j
public abstract class AbstractUdpMessageProcessor extends SimpleChannelInboundHandler<DatagramPacket> {

    protected static final ConcurrentHashMap<P2PCommand, P2PCommandHandler> HANDLER_REGISTRY_MAP = new ConcurrentHashMap<>();
    protected static final ConcurrentHashMap<P2PCommand, P2PCommandHandler> ALL_HANDLER_MAP = new ConcurrentHashMap<>();
    protected static final ConcurrentHashMap<P2PServiceCategory, ConcurrentHashMap<P2PCommand, P2PCommandHandler>> CATEGORY_HANDLER_MAP = new ConcurrentHashMap<>();

    private static final Set<String> CLASS_CACHE = new LinkedHashSet<>();

    static {
        //注册命令处理器
        registerProcessors();
    }
    
    protected P2PMessageService messageService;
    
    
    protected int magic;

    protected Integer queueSize;
    protected boolean connected = false;
    
    
    /**
     * udp数据帧接收缓冲类,用于udp流控,粘包,帧同步(重置),超时重发等
     */
    protected final ConcurrentHashMap<InetSocketAddress,UdpFrameInbound> udpFrameInboundMap = new ConcurrentHashMap<>();
    //protected final Map<InetSocketAddress,UdpFrameOutbound> udpFrameOutboundMap = new HashMap<>();
    
    //最近发送消息缓存
    protected final ConcurrentHashMap<InetSocketAddress,ByteBuf> lastMessageMap = new ConcurrentHashMap<>();
    protected final ConcurrentHashMap<InetSocketAddress, Integer> lastMessageSeqMap = new ConcurrentHashMap<>();
    protected final ConcurrentHashMap<InetSocketAddress, Long> lastRetrieveAtMillisMap = new ConcurrentHashMap<>();
    protected final ConcurrentHashMap<InetSocketAddress, ScheduledFuture<?>> pendingRetrieveFutureMap = new ConcurrentHashMap<>();
    
    protected final ConcurrentHashMap<InetSocketAddress, ConcurrentHashMap<Integer,AbstractLongTimedRequestAdapter>> lastLongTimedRequestAdapterMap = new ConcurrentHashMap<>();
    
     protected final ConcurrentHashMap<InetSocketAddress, ConcurrentHashMap<Integer,AbstractStreamRequestAdapter>> lastStreamRequestAdapterMap = new ConcurrentHashMap<>();
     
     protected ConcurrentHashMap<InetSocketAddress,ServerSendUdpMesageExecutor> asyncSendUdpMesageExecutorMap = new ConcurrentHashMap<>();
     
     protected ConcurrentHashMap<InetSocketAddress,ChannelFuture> lastSendMessageChannelFutureMap = new ConcurrentHashMap<>();
     
     protected final ConcurrentHashMap<Integer,ByteBuf> cachePingMap = new ConcurrentHashMap<>();
     
     protected final ConcurrentHashMap<Integer,ByteBuf> cachePongMap = new ConcurrentHashMap<>();
     
     protected long frameLastTransportSpeed;//上一个成功send数据帧的传输速率,字节/毫秒(mill),用于流控/帧超时重发
     
    private long frameStartTime;
    private long frameLengthInt;
    private static final int SEGMENT_V2_MARKER = -2;


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
        UdpFrameInbound inbound = udpFrameInboundMap.get(datagramPacket.sender());
        if(inbound==null){//保存第1次入站
            inbound = UdpFrameInbound.build(this,ctx.channel(),datagramPacket.sender(),magic, queueSize);
            udpFrameInboundMap.put(datagramPacket.sender(), inbound);
            //ack出站数据
//            UdpFrameOutbound outbound = udpFrameOutboundMap.get(datagramPacket.sender());
//            if(outbound!=null){
//                outbound.channelRead0(ctx, datagramPacket);
//                return;
//            }
            //outbound = UdpFrameOutbound.build(this,ctx.channel(),datagramPacket.sender(),magic, queueSize);
            //udpFrameOutboundMap.put(datagramPacket.sender(), bound);
       
        }
        
        inbound.channelRead0(ctx, datagramPacket);
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
    protected void cacheLastResponse(InetSocketAddress remoteAddess, ByteBuf buffer, int seq){
        buffer.retain();//pipeline会自动release buffer,计数+1
        buffer.markReaderIndex();
        lastMessageMap.put(remoteAddess, buffer);
        lastMessageSeqMap.put(remoteAddess, seq);
    }
    
    /**
     * complete远程端点的处理结果
     * @param remoteAddess
     */
    public void completeLastResponse(InetSocketAddress remoteAddess){
        long dt = System.currentTimeMillis() - frameStartTime;
        if (dt <= 0) {
            dt = 1;
        }
        if (frameLengthInt > 0) {
            frameLastTransportSpeed = frameLengthInt / dt;
        }
        ByteBuf buffer = lastMessageMap.remove(remoteAddess);
        lastMessageSeqMap.remove(remoteAddess);
        if (buffer != null) {
            buffer.release();
        }
        //ReferenceCountUtil.safeRelease(buffer);
        
    }
    
    public boolean isCompleteLastResponse(InetSocketAddress remoteAddess){
        
        return  lastMessageMap.containsKey(remoteAddess);
    }
    
    /**
     * 重发远程端点的处理结果
     * @param ctx
     * @param remoteAddess
     */
    protected void retrieveLastResponse(ChannelHandlerContext ctx,InetSocketAddress remoteAddess){
        Integer seq = lastMessageSeqMap.get(remoteAddess);
        ByteBuf buffer = lastMessageMap.get(remoteAddess);
        long waitTimes = 0;
        if (buffer != null && frameLastTransportSpeed > 0) {
            waitTimes = buffer.readableBytes() / frameLastTransportSpeed;
        }

        ServerSendUdpMesageExecutor sendUdpMesageExecutor = asyncSendUdpMesageExecutorMap.get(remoteAddess);
        if (sendUdpMesageExecutor != null && seq != null) {
            if (waitTimes > 0) {
                sendUdpMesageExecutor.setDelayTimes(waitTimes);
            }
            if (sendUdpMesageExecutor.retrieveLastMessage(seq.intValue(), sendUdpMesageExecutor.getNextFrameSeed())) {
                return;
            }
        }

        if (buffer == null) {
            return;
        }

        ScheduledFuture<?> pending = pendingRetrieveFutureMap.get(remoteAddess);
        if (pending != null && !pending.isDone()) {
            return;
        }

        long now = System.currentTimeMillis();
        long minInterval = waitTimes > 0 ? waitTimes : 5;
        Long last = lastRetrieveAtMillisMap.get(remoteAddess);
        if (last != null) {
            long dt = now - last.longValue();
            if (dt < minInterval) {
                long delay = minInterval - dt;
                ScheduledFuture<?> future = ctx.executor().schedule(() -> {
                    pendingRetrieveFutureMap.remove(remoteAddess);
                    lastRetrieveAtMillisMap.put(remoteAddess, System.currentTimeMillis());
                    ByteBuf latest = lastMessageMap.get(remoteAddess);
                    if (latest == null) {
                        return;
                    }
                    Integer latestSeq = lastMessageSeqMap.get(remoteAddess);
                    latest.resetReaderIndex();
                    latest.retain();
                    sendResponse(ctx.channel(), remoteAddess, latestSeq == null ? 0 : latestSeq.intValue(), latest);
                }, delay, TimeUnit.MILLISECONDS);
                pendingRetrieveFutureMap.put(remoteAddess, future);
                return;
            }
        }

        lastRetrieveAtMillisMap.put(remoteAddess, now);
        buffer.resetReaderIndex();
        buffer.retain();
        sendResponse(ctx.channel(), remoteAddess, seq == null ? 0 : seq.intValue(), buffer);
        
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
            Integer lastSeq = lastMessageSeqMap.get(remoteAddess);
            if (lastSeq != null) {
                if (lastSeq == response.getSeq()) {
                    return;
                }
                ByteBuf old = lastMessageMap.remove(remoteAddess);
                lastMessageSeqMap.remove(remoteAddess);
                if (old != null) {
                    old.release();
                }
            }
            ByteBuf buffer;
             //心跳消息特殊处理
            switch (response.getCommand()) {
                case HEART_PONG:
                    buffer = cachePongMap.get(magic);
                    if(buffer == null){
                        buffer = encodeUdpFrame(response, magic);
                        cachePongMap.put(magic, buffer);
                    }   buffer.retain();
                    break;
                case HEART_PING:
                    buffer = cachePingMap.get(magic);
                    if(buffer == null){
                        buffer = encodeUdpFrame(response, magic);
                        cachePingMap.put(magic, buffer);
                    }   buffer.retain();
                    break;
                default://有序发送(send -> ack/retrieve)消息
                    buffer = encodeUdpFrame(response, magic);
                    cacheLastResponse(remoteAddess, buffer, response.getSeq());
                    break;
            }
            if (log.isDebugEnabled()) {
                log.debug("send response:{}", buffer.readableBytes());
            }
            sendResponse(channel, remoteAddess, response.getSeq(), buffer);
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
        Integer seq = lastMessageSeqMap.get(remoteAddess);
        sendResponse(channel, remoteAddess, seq == null ? 0 : seq, buffer);
    }

    public void sendResponse(Channel channel, InetSocketAddress remoteAddess, int seq, ByteBuf buffer) {
        try {
            frameStartTime = System.currentTimeMillis();
            frameLengthInt = buffer.readableBytes();
            //收到udp消息后，可通过此方式原路返回的方式返回消息，例如返回时间戳
            int maxUdpPayloadSize = 1472;
            int udpLimit = Math.min(P2PConfig.UDP_TRANSPORT_LIMIT_SIZE, maxUdpPayloadSize);
            ChannelFuture cf;
            if (buffer.readableBytes() > udpLimit) {
                boolean v2Enabled = Boolean.parseBoolean(System.getProperty("p2p.udp.segment.v2.enabled", "true"));
                if (v2Enabled) {
                    int start = buffer.readerIndex();
                    int magic = buffer.getInt(start + 4);
                    int fullHash = buffer.getInt(start + 8);
                    int totalLen = buffer.getInt(start);
                    int payloadStart = start + 12;
                    int segmentHeaderBytes = 12 + 20;
                    int segPayloadLimit = udpLimit - segmentHeaderBytes;
                    if (totalLen <= 0 || segPayloadLimit <= 0) {
                        cf = channel.writeAndFlush(new DatagramPacket(buffer.retainedDuplicate(), remoteAddess));
                    } else {
                        int segCount = (totalLen + segPayloadLimit - 1) / segPayloadLimit;
                        cf = null;
                        for (int i = 0; i < segCount; i++) {
                            int off = i * segPayloadLimit;
                            int len = Math.min(segPayloadLimit, totalLen - off);
                            ByteBuf out = SerializationUtil.tryGetDirectBuffer(segmentHeaderBytes + len);
                            out.writeInt(SEGMENT_V2_MARKER);
                            out.writeInt(magic);
                            out.writeInt(fullHash);
                            out.writeInt(totalLen);
                            out.writeInt(off);
                            out.writeShort(i);
                            out.writeShort(segCount);
                            out.writeInt(len);
                            out.writeInt(seq);
                            out.writeBytes(buffer, payloadStart + off, len);
                            cf = channel.writeAndFlush(new DatagramPacket(out, remoteAddess));
                        }
                    }
                } else {
                    int length = buffer.readableBytes();
                    int rest = length % udpLimit;
                    int count = length / udpLimit;
                    int start = buffer.readerIndex();
                    cf = null;
                    for (int i = 0; i < count; i++) {
                        ByteBuf slice = buffer.retainedSlice(start + i * udpLimit, udpLimit);
                        cf = channel.writeAndFlush(new DatagramPacket(slice, remoteAddess));
                    }
                    if (rest > 0) {
                        ByteBuf slice = buffer.retainedSlice(start + count * udpLimit, rest);
                        cf = channel.writeAndFlush(new DatagramPacket(slice, remoteAddess));
                    }
                }
            } else {
                cf = channel.writeAndFlush(new DatagramPacket(buffer.retainedDuplicate(), remoteAddess));
            }
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

    private ByteBuf encodeUdpFrame(P2PWrapper response, int magic) {
        byte[] data = SerializationUtil.serialize(response);
        int hash = XXHashUtil.hash32(data);
        ByteBuf buffer = SerializationUtil.tryGetDirectBuffer(data.length + 12);
        buffer.writeInt(data.length);
        buffer.writeInt(magic);
        buffer.writeInt(hash);
        buffer.writeBytes(data);
        return buffer;
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
            P2PServiceManager.initFromConfigOnce();
            doRegister();
            log.info("HANDLER_REGISTRY_MAP ->\n{}", HANDLER_REGISTRY_MAP);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static void scannerClass(String packageName) throws Exception {
        Enumeration<URL> urls = AbstractUdpMessageProcessor.class.getClassLoader().getResources(packageName.replaceAll("\\.", "/"));
        if (urls == null) {
            return;
        }
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            if (url == null) {
                continue;
            }
            System.out.println("scan processors -> " + url.getFile());
            if ("jar".equals(url.getProtocol())) {
                JarURLConnection urlConnection = (JarURLConnection) url.openConnection();
                Enumeration<JarEntry> entries = urlConnection.getJarFile().entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String entryName = entry.getName();
                    if (entryName.startsWith(packageName.replaceAll("\\.", "/")) && entryName.endsWith(".class")) {
                        System.out.println(entryName);
                        String className = entryName.replace("/", ".").replace(".class", "");
                        CLASS_CACHE.add(className);
                    }
                }
            } else {
                File dir = new File(url.getFile());
                if (!dir.exists()) {
                    log.warn("Directory not found: {}", dir.getAbsolutePath());
                    continue;
                }
                scanDir(dir, packageName);
            }
        }
    }
    
    private static void scanDir(File dir, String packageName) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        scanDir(file, packageName + "." + file.getName());
                    } else if (file.getName().endsWith(".class")) {
                         CLASS_CACHE.add(packageName + "." + file.getName().replace(".class", ""));
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
                    P2PCommand cmd = handler.getCommand();
                    P2PCommandHandler prev = ALL_HANDLER_MAP.putIfAbsent(cmd, handler);
                    if (prev != null) {
                        throw new RuntimeException("P2PCommandHandler register confilct:" + cmd
                            + " " + className
                            + " <> " + prev.getClass().getName());
                    }

                    CATEGORY_HANDLER_MAP.computeIfAbsent(cmd.getCategory(), k -> new ConcurrentHashMap<>()).put(cmd, handler);

                    if (P2PServiceManager.isEnabled(cmd.getCategory())) {
                        P2PCommandHandler exist = HANDLER_REGISTRY_MAP.putIfAbsent(cmd, handler);
                        if (exist != null && exist != handler) {
                            throw new RuntimeException("P2PCommandHandler register confilct:" + cmd
                                + " " + className
                                + " <> " + exist.getClass().getName());
                        }
                    }
                }

                //Class<?> interfaces = clazz.getInterfaces()[0];
                //registryMap.put(interfaces.getName(), clazz.newInstance()); 
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void unloadCategory(P2PServiceCategory category) {
        if (category == null) {
            return;
        }
        ConcurrentHashMap<P2PCommand, P2PCommandHandler> handlers = CATEGORY_HANDLER_MAP.get(category);
        if (handlers == null || handlers.isEmpty()) {
            return;
        }
        for (P2PCommand cmd : handlers.keySet()) {
            HANDLER_REGISTRY_MAP.remove(cmd);
        }
    }

    public static void loadCategory(P2PServiceCategory category) {
        if (category == null) {
            return;
        }
        ConcurrentHashMap<P2PCommand, P2PCommandHandler> handlers = CATEGORY_HANDLER_MAP.get(category);
        if (handlers == null || handlers.isEmpty()) {
            return;
        }
        for (P2PCommand cmd : handlers.keySet()) {
            P2PCommandHandler handler = handlers.get(cmd);
            if (handler == null) {
                continue;
            }
            P2PCommandHandler exist = HANDLER_REGISTRY_MAP.putIfAbsent(cmd, handler);
            if (exist != null && exist != handler) {
                throw new RuntimeException("P2PCommandHandler register confilct:" + cmd
                    + " " + handler.getClass().getName()
                    + " <> " + exist.getClass().getName());
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
