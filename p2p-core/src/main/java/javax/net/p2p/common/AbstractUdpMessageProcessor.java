package javax.net.p2p.common;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractLongTimedRequestAdapter;
import javax.net.p2p.channel.AbstractStreamRequestAdapter;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.interfaces.P2PMessageService;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.common.UdpFrameInbound;
import javax.net.p2p.server.ServerSendUdpMesageExecutor;
import javax.net.p2p.utils.SerializationUtil;
import lombok.extern.slf4j.Slf4j;

/**

UDP消息处理抽象基类
*核心功能：

UDP数据帧管理：为每个远程地址维护独立的UdpFrameInbound
消息缓存与重传：缓存最近发送的消息，支持超时重传
流量控制：基于传输速率动态调整发送策略
命令处理器注册：自动扫描并注册P2PCommandHandler
心跳消息优化：缓存心跳消息避免重复序列化
异步消息发送：支持长时间请求和流式请求
*设计模式：

模板方法模式：定义消息处理流程，子类实现具体逻辑
策略模式：通过命令处理器实现不同命令的处理策略
对象池模式：复用UdpFrameInbound对象
线程安全：

使用ConcurrentHashMap保证多线程安全
每个远程地址的处理逻辑相互独立
@author Administrator
*/
@Slf4j
public abstract class AbstractUdpMessageProcessor extends SimpleChannelInboundHandler {

//==================== 命令处理器注册表 ====================

/** 命令处理器注册表：命令 -> 处理器 */
protected static final HashMap<P2PCommand, P2PCommandHandler> HANDLER_REGISTRY_MAP = new HashMap<>();

/** 类扫描缓存，避免重复扫描 */
private static final List<String> CLASS_CACHE = new ArrayList<>();

static {
// 静态初始化：注册所有命令处理器
registerProcessors();
}

// ==================== 服务与配置 ====================

/** P2P消息服务接口 */
protected P2PMessageService messageService;

/** 协议魔数，用于识别合法数据包 */
protected int magic;

/** 队列大小配置 */
protected Integer queueSize;

/** 连接状态标志 */
protected boolean connected = false;

// ==================== 数据帧管理 ====================

/**

UDP数据帧入站缓冲映射表
Key: 远程地址
Value: 对应的UdpFrameInbound实例
功能：为每个远程地址维护独立的数据帧缓冲和处理逻辑 */ protected final Map<InetSocketAddress, UdpFrameInbound> udpFrameInboundMap = new ConcurrentHashMap<>();
// ==================== 消息缓存与重传 ====================

/**

最近发送消息缓存
Key: 远程地址
Value: 已发送的消息ByteBuf
功能：
支持超时重传：远程端点未ACK时可重发
避免重复发送：检测到未ACK的消息时丢弃新消息 */ protected final Map<InetSocketAddress, ByteBuf> lastMessageMap = new ConcurrentHashMap<>();
/**

长时间请求适配器映射表
Key: 请求ID
Value: 长时间请求适配器
功能：处理需要长时间等待的请求（如文件传输、大数据查询） */ protected final Map<Integer, AbstractLongTimedRequestAdapter> lastLongTimedRequestAdapterMap = new ConcurrentHashMap<>();
/**

流式请求适配器映射表
Key: 请求ID
Value: 流式请求适配器
功能：处理流式数据传输（如视频流、日志流）

<append_file>
AbstractUdpMessageProcessor.java
     */
protected final Map<Integer, AbstractStreamRequestAdapter> lastStreamRequestAdapterMap = new ConcurrentHashMap<>();

/**
 * 异步UDP消息发送执行器映射表
 * Key: 远程地址
 * Value: 异步发送执行器
 * 
 * 功能：支持异步批量发送，提高吞吐量
 */
protected Map<InetSocketAddress, ServerSendUdpMesageExecutor> asyncSendUdpMesageExecutorMap = new ConcurrentHashMap<>();
/**
 * 最近发送消息的ChannelFuture映射表
 * Key: 远程地址
 * Value: 发送操作的Future
 * 
 * 功能：跟踪发送状态，支持取消和超时处理
 */
protected Map<InetSocketAddress, ChannelFuture> lastSendMessageChannelFutureMap = new ConcurrentHashMap<>();
 
// ==================== 心跳消息缓存 ====================

/**
 * PING消息缓存
 * Key: magic值
 * Value: 序列化后的PING消息ByteBuf
 * 
 * 优化：避免每次心跳都重新序列化
 */
protected final Map<Integer, ByteBuf> cachePingMap = new ConcurrentHashMap<>();
/**
 * PONG消息缓存
 * Key: magic值
 * Value: 序列化后的PONG消息ByteBuf
 * 
 * 优化：避免每次心跳都重新序列化
 */
protected final Map<Integer, ByteBuf> cachePongMap = new ConcurrentHashMap<>();
 
// ==================== 流量控制相关 ====================

/** 上一个成功发送数据帧的传输速率（字节/毫秒），用于流控和超时计算 */
protected long frameLastTransportSpeed;
/** 数据帧开始发送时间戳（毫秒） */
private long frameStartTime;

/** 数据帧长度（字节） */
private long frameLengthInt;

/**
 * 构造函数
 * 
 * @param magic 协议魔数
 * @param queueSize 队列大小
 */
public AbstractUdpMessageProcessor(int magic, Integer queueSize) {
    this.magic = magic;
    this.queueSize = queueSize;}

/**
 * 构造函数（带消息服务）
 * 
 * @param messageService P2P消息服务
 * @param magic 协议魔数
 * @param queueSize 队列大小
 */
public AbstractUdpMessageProcessor(P2PMessageService messageService, int magic, Integer queueSize) {
    this.messageService = messageService;
    this.magic = magic;
    this.queueSize = queueSize;
}

/**
 * 获取最近发送消息缓存
 * 
 * @return 消息缓存映射表
 */
public Map<InetSocketAddress, ByteBuf> getLastMessageMap() {
    return lastMessageMap;
}

/**
 * 处理消息的抽象方法，由子类实现具体业务逻辑
 * 
 * @param ctx Netty上下文
 * @param datagramPacket UDP数据包
 * @param message P2P消息包装对象
 */
public abstract void processMessage(ChannelHandlerContext ctx, DatagramPacket datagramPacket, P2PWrapper message);

/**
 * 处理入站UDP数据包
 * 
 * 核心逻辑：
 * 1. 根据远程地址获取或创建UdpFrameInbound
 * 2. 委托给UdpFrameInbound处理数据帧
 * 
 * @param ctx Netty上下文
 * @param datagramPacket UDP数据包
 * @throws Exception 处理异常
 */
//@Override
protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket datagramPacket) throws Exception {
    InetSocketAddress sender = datagramPacket.sender();
    UdpFrameInbound inbound = udpFrameInboundMap.get(sender);
    
    if (inbound == null) {
        // 首次接收该远程地址的数据，创建新的UdpFrameInbound
        //inbound = UdpFrameInbound.build(this, ctx.channel(), sender, magic, queueSize);
        udpFrameInboundMap.put(sender, inbound);
        
        if (log.isDebugEnabled()) {
            log.debug("创建新的UdpFrameInbound: 远程地址={}, magic={}", sender, Integer.toHexString(magic));
        }
    }
    
    // 委托给UdpFrameInbound处理
    inbound.channelRead0(ctx, datagramPacket);
}

/**
 * 异常处理
 * 
 * @param ctx Netty上下文
 * @param cause 异常原因
 * @throws Exception 处理异常
 */
@Override
public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    if (log.isDebugEnabled()) {
        log.debug("通道异常: magic={}, 远程地址={}, 本地地址={}", 
                 Integer.toHexString(magic), 
                 ctx.channel().remoteAddress(), 
                 ctx.channel().localAddress());
    }
    log.error("UDP消息处理异常: {}", cause.getMessage(), cause);
    ctx.close();
    super.exceptionCaught(ctx, cause);
}

/**
 * 发送响应消息（带上下文）
 * 
 * @param ctx Netty上下文
 * @param datagramPacket 原始数据包
 * @param response 响应消息
 */
public void sendResponse(ChannelHandlerContext ctx, DatagramPacket datagramPacket, P2PWrapper response) {
    Attribute<Integer> attrMagic = ctx.channel().attr(javax.net.p2p.channel.ChannelUtils.MAGIC);
    Integer magicChannel = attrMagic.get();
    
    if (magicChannel != null) {
        sendResponse(ctx.channel(), datagramPacket.sender(), response, magicChannel);
    } else {
        sendResponse(ctx.channel(), datagramPacket.sender(), response, magic);
    }
}

/**
 * 缓存远程端点的处理结果
 * 
 *功能：
 * 1. 增加ByteBuf引用计数，防止被pipeline自动释放
 * 2. 标记读索引，支持重传时重置
 * 3. 存入缓存映射表
 * 
 * @param remoteAddess 远程地址
 * @param buffer 响应数据缓冲区
 */
protected void cacheLastResponse(InetSocketAddress remoteAddess, ByteBuf buffer) {
    buffer.retain(); // pipeline会自动release buffer，这里计数+1防止被释放
    buffer.markReaderIndex(); // 标记读索引，重传时可重置
    lastMessageMap.put(remoteAddess, buffer);
    if (log.isDebugEnabled()) {
        log.debug("缓存响应消息: 远程地址={}, 大小={}字节", remoteAddess, buffer.readableBytes());
    }
}

/**
 * 完成远程端点的处理结果（收到ACK后调用）
 * 
 * 功能：
 * 1. 计算传输速率
 * 2. 从缓存中移除消息
 * 3. 释放ByteBuf资源
 * 
 * @param remoteAddess 远程地址
 */
public void completeLastResponse(InetSocketAddress remoteAddess) {
    ByteBuf buffer = lastMessageMap.remove(remoteAddess);
    if (buffer != null) {
        // 计算传输速率
        long duration = System.currentTimeMillis() - frameStartTime;
        frameLastTransportSpeed = duration > 0 ? frameLengthInt / duration : frameLengthInt;
        
        // 释放资源
        buffer.release();
        
        if (log.isDebugEnabled()) {
            log.debug("完成响应消息: 远程地址={}, 传输速率={} 字节/毫秒", 
                     remoteAddess, frameLastTransportSpeed);
        }
    }
}

/**
 * 检查远程端点是否有未完成的响应
 * 
 * @param remoteAddess 远程地址
 * @return true表示有未完成的响应
 */
public boolean isCompleteLastResponse(InetSocketAddress remoteAddess) {
    return lastMessageMap.containsKey(remoteAddess);
}

/**
 * 重发远程端点的处理结果（超时重传）
 * 
 * 功能：
 * 1. 从缓存中获取消息
 * 2. 重置读索引
 * 3. 增加引用计数
 * 4. 计算等待时间（基于传输速率）
 * 5. 调用异步发送执行器（如果已注册）
 * 6. 重新发送消息
 * 
 * @param ctx Netty上下文
 * @param remoteAddess 远程地址
 */
protected void retrieveLastResponse(ChannelHandlerContext ctx, InetSocketAddress remoteAddess) {
    ByteBuf buffer = lastMessageMap.get(remoteAddess);
    if (buffer == null) {
        log.warn("重传失败: 未找到缓存消息, 远程地址={}", remoteAddess);
        return;
    }
    
    buffer.resetReaderIndex(); // 重置读索引
    buffer.retain(); // 增加引用计数
    
    if (log.isDebugEnabled()) {
        log.debug("重传响应消息: 远程地址={}, 大小={} 字节", remoteAddess, buffer.readableBytes());
    }
    
    // 流控：计算预期等待时间
    long waitTimes = frameLastTransportSpeed > 0 ? buffer.readableBytes() / frameLastTransportSpeed 
                     : 0;
    
    // 如果已注册异步消息处理器，需调用
    ServerSendUdpMesageExecutor sendUdpMesageExecutor = asyncSendUdpMesageExecutorMap.get(remoteAddess);
    if (sendUdpMesageExecutor != null) {
        // TODO: 实现异步发送逻辑
        log.debug("使用异步发送执行器重传: 远程地址={}, 等待时间={} 毫秒", remoteAddess, waitTimes);}
    
    sendResponse(ctx.channel(), remoteAddess, buffer);
}

/**
 * 发送响应消息（P2PWrapper对象）
 * 
 * 核心逻辑：
 * 1. 检查是否有未ACK的消息，有则丢弃新消息（避免乱序）
 * 2. 心跳消息特殊处理：使用缓存避免重复序列化
 * 3. 普通消息：序列化后缓存
 * 4. 调用底层发送方法
 * 
 * @param channel Netty通道
 * @param remoteAddess 远程地址
 * @param response 响应消息
 * @param magic 协议魔数
 */
public void sendResponse(Channel channel, InetSocketAddress remoteAddess, P2PWrapper response, int magic) {
    try {
        // 检查是否有未ACK的消息
        if (lastMessageMap.containsKey(remoteAddess)) {
            // 远程端点尚未ACK，一般来说不应该存在新数据包
            // 可以安全地丢弃之（比如心跳包等）
            if (log.isDebugEnabled()) {
                log.debug("丢弃新消息: 远程地址={}, 命令={}, 原因: 存在未ACK的消息", 
                         remoteAddess, response.getCommand());
            }
            return;
        }
        
        ByteBuf buffer;
        // 心跳消息特殊处理：使用缓存避免重复序列化
        switch (response.getCommand()) {
            case HEART_PONG:
                buffer = cachePongMap.get(magic);
                if (buffer == null) {
                    buffer = SerializationUtil.serializeToByteBuf(response, magic);
                    cachePongMap.put(magic, buffer);
                }
                buffer.retain(); // 增加引用计数
                break;
                
            case HEART_PING:
                buffer = cachePingMap.get(magic);
                if (buffer == null) {
                    buffer = SerializationUtil.serializeToByteBuf(response, magic);
                    cachePingMap.put(magic, buffer);
                }
                buffer.retain(); // 增加引用计数
                break;
                
            default:
                // 有序发送(send ->ack/retrieve)消息
                buffer = SerializationUtil.serializeToByteBuf(response, magic);
                cacheLastResponse(remoteAddess, buffer); // 缓存以支持重传
                break;
        }
        
        if (log.isDebugEnabled()) {
            log.debug("发送响应消息: 远程地址={}, 命令={}, 大小={} 字节", 
                     remoteAddess, response.getCommand(), buffer.readableBytes());
        }
        
        sendResponse(channel, remoteAddess, buffer);
        
    } catch (Exception ex) {
        log.warn("消息处理异常: 消息={}, 错误={}, 关闭通道={}", 
                 response, ex.getMessage(), channel.id());
        try {
            channel.close();
        } catch (Exception ex2) {
            log.error("关闭通道失败: {}", ex2.getMessage());
        }
    }
}

/**
 * 发送响应消息（ByteBuf对象）
 * 
 * 核心逻辑：
 * 1. 记录发送开始时间和数据长度
 * 2. 封装为DatagramPacket并发送
 * 3. 添加监听器跟踪发送状态
 * 4. 同步等待发送完成
 * 
 * @param channel Netty通道
 * @param remoteAddess 远程地址
 * @param buffer 响应数据缓冲区
 */
public void sendResponse(Channel channel, InetSocketAddress remoteAddess, ByteBuf buffer) {
    try {
        frameStartTime = System.currentTimeMillis();
        frameLengthInt = buffer.readableBytes();
        
        // 封装为UDP数据包并发送
        ChannelFuture cf = channel.writeAndFlush(new DatagramPacket(buffer, remoteAddess));
        
        if (cf != null) {
            lastSendMessageChannelFutureMap.put(remoteAddess, cf);
            
            // 添加监听器跟踪发送状态
            cf.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) {
                    lastSendMessageChannelFutureMap.remove(remoteAddess);
                    
                    if (future.isSuccess()) {
                        if (log.isDebugEnabled()) {
                            log.debug("消息发送成功: 远程地址={}, 大小={} 字节", 
                                     remoteAddess, buffer.readableBytes());
                        }
                    } else {
                        log.warn("消息发送失败: 远程地址={}, 大小={} 字节, 原因={}, 关闭通道={}", 
                                 remoteAddess, buffer.readableBytes(), 
                                 future.cause().getMessage(), channel.id());channel.close();
                    }
                }
            });
            
            // 同步等待发送完成
            cf.sync();
        }
    } catch (Exception ex) {
        log.warn("消息发送异常: 远程地址={}, 大小={} 字节, 错误={}, 关闭通道={}", 
                 remoteAddess, buffer.readableBytes(), ex.getMessage(), channel.id());
        try {
            channel.close();
        } catch (Exception ex2) {
            log.error("关闭通道失败: {}", ex2.getMessage());
        }
    }
}

/**
 * 文件传输示例方法（使用零拷贝）
 * 
 * 功能：
 * 1. SSL启用时使用ChunkedFile
 * 2. SSL未启用时使用DefaultFileRegion（零拷贝）
 * 
 * @param ctx Netty上下文
 * @throws IOException IO异常
 */
private void fileRegion(ChannelHandlerContext ctx) throws IOException {
    RandomAccessFile raf = null;
    long length = -1;
    
    try {
        // TODO: 替换为实际文件路径
        raf = new RandomAccessFile("", "r");
        length = raf.length();
    } catch (Exception e) {
        ctx.writeAndFlush("ERR: " + e.getClass().getSimpleName() + ": " + e.getMessage() + '\n');
        return;
    } finally {
        if (length< 0 && raf != null) {
            raf.close();
        }
    }

    ctx.write("OK: " + raf.length());
if (ctx.pipeline().get(SslHandler.class) == null) {
//未启用SSL：使用零拷贝文件传输
ctx.write(new DefaultFileRegion(raf.getChannel(), 0, length));
} else {
// 启用SSL：不能使用零拷贝
ctx.write(new ChunkedFile(raf));
}
}

/** 预定义的PONG消息 */
private static final P2PWrapper HEART_PONG = P2PWrapper.build(0, P2PCommand.HEART_PONG);

/**
 * 发送PONG心跳消息
 * 
 * @param ctx Netty上下文
 * @param datagramPacket 原始数据包
 * @param magic 协议魔数
 */
public void sendPongMsg(ChannelHandlerContext ctx, DatagramPacket datagramPacket, int magic) {
    if (log.isDebugEnabled()) {
        log.debug("发送PONG心跳: 通道={}, 远程地址={}", ctx.channel().id(), datagramPacket.sender());
    }
    sendResponse(ctx.channel(), datagramPacket.sender(), HEART_PONG, magic);
}

/**
 * 注册所有命令处理器
 * 
 * 功能：
 * 1. 扫描指定包下的所有类
 * 2. 查找实现P2PCommandHandler接口的类
 * 3. 实例化并注册到HANDLER_REGISTRY_MAP
 */
public static void registerProcessors() {
    try {
        scannerClass("javax.net.p2p.server.handler");doRegister();log.info("命令处理器注册完成: 共 {} 个处理器", HANDLER_REGISTRY_MAP.size());
        if (log.isDebugEnabled()) {
            log.debug("已注册的处理器: {}", HANDLER_REGISTRY_MAP);
        }
    } catch (Exception ex) {
        throw new RuntimeException("命令处理器注册失败", ex);
    }
}

/**
 * 扫描指定包下的所有类
 * 
 * 支持：
 * 1. 文件系统中的类
 * 2. JAR包中的类
 *
 * @param packageName 包名
 * @throws Exception扫描异常
 */
private static void scannerClass(String packageName) throws Exception {
    URL url = AbstractUdpMessageProcessor.class.getClassLoader().getResource(packageName.replaceAll("\\.", "/"));
    if (url == null) {
        log.warn("包不存在: {}", packageName);
        return;
    }
    
    if (log.isDebugEnabled()) {
        log.debug("扫描处理器: {}", url.getFile());
    }
    
    if ("jar".equals(url.getProtocol())) {
        // JAR包中的类
        JarURLConnection urlConnection = (JarURLConnection) url.openConnection();
        Enumeration<JarEntry> entries = urlConnection.getJarFile().entries();
        
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String entryName = entry.getName();
            
            // 过滤出目标包下的.class文件
            if (entryName.startsWith(packageName.replaceAll("\\.", "/")) 
                && entryName.endsWith(".class")) {
                
                String className = entryName.replace("/", ".").replace(".class", "");
                CLASS_CACHE.add(className);
                
                if (log.isDebugEnabled()) {
                    log.debug("发现类: {}", className);
                }
            }
        }
    } else {
        // 文件系统中的类
        File dir = new File(url.getFile());
        if (!dir.exists()) {
            log.warn("目录不存在: {}", dir.getAbsolutePath());
            return;
        }
        scanDir(dir, packageName);
    }
}

/**
 * 递归扫描目录
 * 
 * @param dir 目录
 * @param packageName 包名
 */
private static void scanDir(File dir, String packageName) {
    if (dir.isDirectory()) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    // 递归扫描子目录
                    scanDir(file, packageName + "." + file.getName());
                } else if (file.getName().endsWith(".class")) {
                    // 添加类到缓存
                    String className = packageName + "." + file.getName().replace(".class", "");
                    CLASS_CACHE.add(className);
                    
                    if (log.isDebugEnabled()) {
                        log.debug("发现类: {}", className);
                    }
                }
            }
        }
    }
}

/**
 * 执行注册逻辑
 * 
 * 功能：
 * 1. 遍历扫描到的所有类
 * 2. 检查是否实现P2PCommandHandler接口
 * 3. 实例化并注册到映射表
 * 4. 检测命令冲突
 */
private static void doRegister() {
    if (CLASS_CACHE.isEmpty()) {
        log.warn("未扫描到任何类");
        return;
    }

    for (String className : CLASS_CACHE) {
        try {
            Class<?> clazz = Class.forName(className);
            
            // 检查是否实现P2PCommandHandler接口
            if (P2PCommandHandler.class.isAssignableFrom(clazz)) {
                P2PCommandHandler handler = (P2PCommandHandler) clazz.getDeclaredConstructor().newInstance();
                
                // 检测命令冲突
                if (HANDLER_REGISTRY_MAP.containsKey(handler.getCommand())) {
                    throw new RuntimeException(
                        String.format("命令处理器注册冲突: 命令=%s, 类1=%s, 类2=%s",
                                     handler.getCommand(),
                                     className,
                                     HANDLER_REGISTRY_MAP.get(handler.getCommand()).getClass().getName())
                    );
                }
                
                HANDLER_REGISTRY_MAP.put(handler.getCommand(), handler);
                if (log.isDebugEnabled()) {
                    log.debug("注册处理器: 命令={}, 类={}", handler.getCommand(), className);
                }
            }
        } catch (Exception e) {
            log.error("注册处理器失败: 类={}, 错误={}", className, e.getMessage(), e);
        }
    }
}

/**
 * Handler移除时的回调
 * 
 * 功能：
 * 1. 标记连接状态为断开
 * 2. 关闭所有UdpFrameInbound
 * 3. 释放资源
 * 
 * @param ctx Netty上下文
 * @throws Exception 处理异常
 */
@Override
public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    connected = false;
    
    // 关闭所有UdpFrameInbound
    for (Map.Entry<InetSocketAddress, UdpFrameInbound> entry : udpFrameInboundMap.entrySet()) {
        try {
            entry.getValue().close();
        } catch (Exception ex) {
            log.error("关闭UdpFrameInbound失败: 远程地址={}, 错误={}", 
                     entry.getKey(), ex.getMessage());
        }
    }
    udpFrameInboundMap.clear();
    
    // 释放缓存的消息
    for (Map.Entry<InetSocketAddress, ByteBuf> entry : lastMessageMap.entrySet()) {
        try {
            entry.getValue().release();
        } catch (Exception ex) {
            log.error("释放缓存消息失败: 远程地址={}, 错误={}", 
                     entry.getKey(), ex.getMessage());
        }
    }
    lastMessageMap.clear();
    
    log.info("UDP消息处理器已移除: 通道={}", ctx.channel().id());
    super.handlerRemoved(ctx);
}

/**
 * Handler添加时的回调
 * 
 * @param ctx Netty上下文
 * @throws Exception 处理异常
 */
@Override
public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    connected = true;
    log.info("UDP消息处理器已添加: 通道={}", ctx.channel().id());
    super.handlerAdded(ctx);
}

/**
 * 获取连接状态
 * 
 * @return true表示已连接
 */
public boolean isConnected() {
    return connected;
}
}
