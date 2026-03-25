package javax.net.p2p.channel;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedFile;
import io.netty.util.Attribute;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.jar.JarEntry;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SerializationUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractQuicMessageProcessor extends SimpleChannelInboundHandler<ByteBuf> {

    protected final static HashMap<P2PCommand, P2PCommandHandler> HANDLER_REGISTRY_MAP = new HashMap<>();

    private static final List<String> CLASS_CACHE = new ArrayList<>();

    static {
        //注册命令处理器
        registerProcessors();
    }
    protected int headerSize = 12;
    protected int frameLengthInt = -1;
    
    protected int frameReaded = 0;
    
    protected int lastRest = 0;

    protected int magic;

    protected int queueSize;
    protected boolean connected = false;
    
    private ByteBuf inBuffer;//DatagramPacket缓冲区尺寸最大2048,必须copy收集足够一数据帧


    public AbstractQuicMessageProcessor(int magic,int queueSize) {
        this.magic = magic;
        this.queueSize = queueSize;
    }

    protected abstract void processMessage(ChannelHandlerContext ctx, P2PWrapper message);

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        System.out.println("readableBytes:"+in.readableBytes());
        if (lastRest >0 ){
             if (lastRest < 8 && in.readableBytes()>headerSize) {//头长度
                inBuffer.writeBytes(in, headerSize);
            }else{
                 inBuffer.writeBytes(in);
             }
             if (inBuffer.readableBytes() < headerSize) {//头长度
                //new PooledByteBufAllocator(false);
                return;
            }
            frameLengthInt = inBuffer.readInt();//保存frame字节数
            magic = inBuffer.readInt();
            //int hash = inBuffer.readInt();
            //System.out.printf("lastRest=%d,magic=%s,hash=%s,frameLengthInt=%d\n",lastRest,Integer.toHexString(magic),hash,frameLengthInt);
            lastRest = 0;
        } else if(frameLengthInt == -1) { // new frame
            if (in.readableBytes() < headerSize) {//头长度
                //new PooledByteBufAllocator(false);
                return;
            }
            frameLengthInt = in.readInt();//保存frame字节数
            magic = in.readInt();
            //int hash = in.readInt();
            //System.out.printf("magic=%s,hash=%s,frameLengthInt=%d\n",Integer.toHexString(magic),hash,frameLengthInt);

            if(inBuffer==null){
                inBuffer = SerializationUtil.tryGetDirectBuffer(frameLengthInt);
            }else if(inBuffer.capacity()<frameLengthInt){
               inBuffer.capacity(frameLengthInt);
            }
                        
            //System.out.println("after readableBytes:"+in.readableBytes());
            //ctx.fireChannelReadComplete();
        }
        //等待frame传输完成
        if (inBuffer.readableBytes() < frameLengthInt) {
            int rest = frameLengthInt-frameReaded;
            if (in.readableBytes() <= rest) {
               frameReaded += in.readableBytes();
               inBuffer.writeBytes(in); 
            }else{
                frameReaded += rest;
                inBuffer.writeBytes(in, rest);
            }
            
            if (inBuffer.readableBytes() < frameLengthInt) {
                //System.out.println("after readableBytes:"+in.readableBytes());
                //ctx.fireChannelReadComplete();
                if (log.isDebugEnabled()) {
                    log.debug("WrapperDecoder -> expected " + frameLengthInt + ",actual:" + in.readableBytes());
                }
                return;
            }
        }

        Attribute<Integer> attrMagic = ctx.channel().attr(ChannelUtils.MAGIC);
        if (magic == attrMagic.get()) {
//            System.out.println("frameLengthInt:"+frameLengthInt);
//            System.out.println("data rix:"+inBuffer.readerIndex());
//            System.out.println("readableBytes:"+inBuffer.readableBytes());
//            System.out.println(Integer.toHexString(magic)+" datagramPacket.sender() ->"+datagramPacket.sender()); 
//            System.out.println(Integer.toHexString(magic)+" datagramPacket.recipient() ->"+datagramPacket.recipient());  
            try {//先获取可读字节数
                byte[] data = new byte[frameLengthInt];
                inBuffer.readBytes(data);
                //int hashIn = XXHashUtil.hash32(data);
                //System.out.println("hashIn:"+hashIn);
                P2PWrapper request = SerializationUtil.deserialize(P2PWrapper.class, data);
                //P2PWrapper request = SerializationUtil.deserialize(P2PWrapper.class, inBuffer, magic, frameLengthInt);
//                System.out.println("Object result = "+request.getData().toString().length());
//                System.out.println(Arrays.toString(request.getData().toString().getBytes()));
//                System.out.println("Server echo -> test udp-0".length());
                processMessage(ctx,  request);
            } catch (Exception ex) {
                log.warn("{} resolve error:{}, \n close channel:{}", inBuffer, ex, ctx.channel().id());

            } finally {
                frameLengthInt = -1; // start processing the next frame
                frameReaded = 0;
                inBuffer.clear();
                lastRest = in.readableBytes();
                inBuffer.writeBytes(in);
            }
        } else {
            //in.skipBytes(in.readableBytes());
            inBuffer.skipBytes(inBuffer.readableBytes());
            log.error("skip {} bytes -> expected magic number {},actual:{}", frameLengthInt, attrMagic.get(), magic);
            P2PWrapper response = P2PWrapper.build(0, P2PCommand.INVALID_PROTOCOL, in.readableBytes());
            sendResponse(ctx, response, magic);
            frameLengthInt = -1; // start processing the next frame
            frameReaded = 0;
        }

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
        URL url = AbstractQuicMessageProcessor.class.getClassLoader().getResource(packageName.replaceAll("\\.", "/"));
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
        //channelPool.release(ctx.channel());
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
