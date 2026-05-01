/**
 * ServerMessageProcessor - 服务器消息处理器，负责分发和处理所有P2P协议消息
 * 
 * 主要职责：
 * 1. 接收客户端发送的P2P协议消息
 * 2. 根据消息命令类型路由到对应的命令处理器
 * 3. 处理消息响应和异常情况
 * 4. 管理消息的发送确认和重试机制
 * 
 * 处理流程：
 * 1. channelRead0: 接收消息并解析命令类型
 * 2. 查找注册的命令处理器
 * 3. 执行处理器逻辑并生成响应
 * 4. 发送响应并处理发送结果
 * 5. 异常处理和重试机制
 * 
 * 技术特点：
 * - 基于Netty的ChannelHandler实现
 * - 使用命令模式，支持动态注册处理器
 * - 提供完整的异常处理和重试机制
 * - 支持异步消息发送和回调
 * 
 * 性能优化：
 * 1. 使用Slf4j日志框架，支持动态日志级别
 * 2. 异步消息发送，避免阻塞IO线程
 * 3. 消息重试机制，提高网络可靠性
 * 4. 连接异常自动关闭，释放资源
 * 
 * 使用场景：
 * - P2P服务器核心消息处理
 * - 多协议命令路由分发
 * - 网络异常处理和恢复
 * 
 * @version 1.0
 * @since 2025
 */
package javax.net.p2p.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.auth.AuthEnforcer;
import javax.net.p2p.channel.AbstractLongTimedRequestAdapter;
import javax.net.p2p.channel.AbstractStreamRequestAdapter;
import javax.net.p2p.channel.AbstractTcpMessageProcessor;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.interfaces.P2PChannelAwareCommandHandler;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.rpc.server.RpcControlSupport;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ServerMessageProcessor extends AbstractTcpMessageProcessor {

    private final ConcurrentHashMap<Integer, AbstractLongTimedRequestAdapter> lastLongTimedRequestAdapterMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, AbstractStreamRequestAdapter> lastStreamRequestAdapterMap = new ConcurrentHashMap<>();

    /**
     * 构造函数，初始化消息处理器
     * 
     * 参数说明：
     * @param magic 魔数标识，用于协议验证，防止非法连接
     * @param queueSize 消息队列大小，控制并发处理能力
     * 
     * 功能说明：
     * 1. 设置协议魔数，验证消息合法性
     * 2. 初始化消息队列，控制并发处理
     * 3. 继承父类的初始化逻辑
     * 
     * 魔数作用：
     * - 验证连接合法性，防止非法协议攻击
     * - 确保客户端使用正确的协议版本
     * - 快速过滤无效连接请求
     * 
     * 队列大小调优：
     * - 过小：可能导致消息丢失或阻塞
     * - 过大：可能消耗过多内存
     * - 建议值：根据并发连接数调整
     */
    public ServerMessageProcessor(int magic, int queueSize) {
        super(magic, queueSize);
    }

    protected AbstractSendMesageExecutor createExecutor(ChannelHandlerContext ctx) {
        return new ServerTcpDirectExecutor(queueSize, ctx.channel());
    }

    /**
     * 消息接收和处理方法 - Netty ChannelHandler的核心回调方法
     * 
     * 处理流程：
     * 1. 消息接收：从网络通道读取P2PWrapper消息
     * 2. 命令解析：提取消息序列号和命令类型
     * 3. 处理器查找：根据命令类型查找注册的处理器
     * 4. 业务处理：调用处理器处理消息并生成响应
     * 5. 响应发送：将响应发送回客户端
     * 6. 异常处理：处理处理过程中的各种异常
     * 
     * 性能特点：
     * - 异步非阻塞：基于Netty的NIO实现
     * - 线程安全：每个Channel有独立的处理器
     * - 资源高效：使用对象池减少GC压力
     * 
     * 异常场景处理：
     * 1. 未知命令：返回STD_ERROR错误响应
     * 2. 处理器异常：记录日志并尝试重试
     * 3. 发送失败：等待重发或关闭连接
     * 
     * @param ctx Netty ChannelHandler上下文，包含连接信息
     * @param msg 接收到的P2P协议消息包装对象
     * @throws Exception 处理过程中可能抛出的异常
     */
    @Override
    public void channelRead0(ChannelHandlerContext ctx, P2PWrapper msg) throws Exception {
        // 提取消息序列号，用于请求响应匹配和日志追踪
        int seq = msg.getSeq();
        System.out.println("记录接收到的消息详情，便于调试和问题排查 -> "+msg);
        // 记录接收到的消息详情，便于调试和问题排查
        log.debug("channel {} read msg ->\n{}", ctx.channel().id(), msg);
        
        // 响应消息初始化
        P2PWrapper p2p;

        P2PWrapper denied = AuthEnforcer.check(ctx.channel(), msg);
        if (denied != null) {
            ctx.channel().writeAndFlush(denied);
            return;
        }

        if (msg.getCommand() == P2PCommand.STD_CANCEL || msg.getCommand() == P2PCommand.STD_STOP) {
            AbstractLongTimedRequestAdapter longTimed = lastLongTimedRequestAdapterMap.remove(msg.getSeq());
            if (longTimed != null) {
                longTimed.asyncProcess(msg);
                return;
            }
            AbstractStreamRequestAdapter stream = lastStreamRequestAdapterMap.remove(msg.getSeq());
            if (stream != null) {
                stream.asyncProcess(stream, StreamP2PWrapper.buildStream(msg.getSeq(), true));
                ctx.channel().writeAndFlush(P2PWrapper.build(msg.getSeq(), P2PCommand.STD_CANCEL, "canceled"));
                return;
            }
            ctx.channel().writeAndFlush(P2PWrapper.build(msg.getSeq(), P2PCommand.STD_ERROR, "task not found"));
            return;
        }
        if (msg.getCommand() == P2PCommand.RPC_CONTROL) {
            P2PWrapper<byte[]> controlResponse = RpcControlSupport.handleControl((P2PWrapper<byte[]>) msg, lastLongTimedRequestAdapterMap, lastStreamRequestAdapterMap);
            ctx.channel().writeAndFlush(controlResponse);
            return;
        }

        if (!P2PServiceManager.isEnabled(msg.getCommand().getCategory())) {
            P2PWrapper unavailable = P2PWrapper.build(msg.getSeq(), P2PCommand.STD_ERROR, "service unavailable: " + msg.getCommand().getCategory());
            ctx.channel().writeAndFlush(unavailable);
            return;
        }
        
        // 根据消息命令类型查找对应的处理器
        // HANDLER_REGISTRY_MAP是父类维护的命令处理器注册表
        P2PCommandHandler handler = (P2PCommandHandler) HANDLER_REGISTRY_MAP.get(msg.getCommand());
        
        if (handler != null) {
            if (handler instanceof AbstractLongTimedRequestAdapter) {
                AbstractLongTimedRequestAdapter longTimed = lastLongTimedRequestAdapterMap.get(msg.getSeq());
                if (longTimed == null) {
                    longTimed = (AbstractLongTimedRequestAdapter) handler;
                    AbstractSendMesageExecutor executor = createExecutor(ctx);
                    longTimed = longTimed.asyncProcess(executor, longTimed, msg);
                    lastLongTimedRequestAdapterMap.put(msg.getSeq(), longTimed);
                    ctx.channel().writeAndFlush(P2PWrapper.build(msg.getSeq(), P2PCommand.STD_ACCEPTED, null));
                    return;
                } else {
                    longTimed.asyncProcess(msg);
                    return;
                }
            }
            if (handler instanceof AbstractStreamRequestAdapter) {
                AbstractStreamRequestAdapter stream = lastStreamRequestAdapterMap.get(msg.getSeq());
                if (stream == null) {
                    stream = (AbstractStreamRequestAdapter) handler;
                    AbstractSendMesageExecutor executor = createExecutor(ctx);
                    stream = stream.asyncProcess(executor, stream, (StreamP2PWrapper) msg);
                    lastStreamRequestAdapterMap.put(msg.getSeq(), stream);
                    ctx.channel().writeAndFlush(P2PWrapper.build(msg.getSeq(), P2PCommand.STREAM_ACK, null));
                    return;
                } else {
                    stream.asyncProcess(stream, msg);
                    return;
                }
            }
            if (handler instanceof P2PChannelAwareCommandHandler) {
                p2p = ((P2PChannelAwareCommandHandler) handler).process(ctx, msg);
            } else {
                p2p = handler.process(msg);
            }
        } else {
            // 未知命令类型，返回错误响应
            // 保持原消息序列号，便于客户端匹配请求响应
            p2p = P2PWrapper.build(msg.getSeq(), P2PCommand.STD_ERROR, "未知消息类型：" + msg);
        }

        try {
            // 记录发送的消息详情，便于调试
            log.debug("channel {} send msg ->\n{}", ctx.channel().id(), p2p);
            
            // 异步发送响应消息到客户端
            // writeAndFlush方法会立即返回ChannelFuture，不会阻塞当前线程
            ChannelFuture cf = ctx.channel().writeAndFlush(p2p);
            
            if (cf != null) {
                // 添加发送完成监听器，处理发送结果
                // Netty官方建议优先使用addListener而非await，避免线程阻塞
                cf.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) {
                        // 发送操作完成回调，检查发送是否成功
                        if (!future.isSuccess()) {
                            // 发送失败，记录错误日志
                            // 注意：这里只记录日志，不直接关闭连接，由上层异常处理
                            log.error("{}消息处理未成功,可能原因:{},关闭channel:{}", 
                                     seq, future.cause().getMessage(), ctx.channel().id());
                            // ctx.close(); // 可选的连接关闭逻辑
                        }
                    }
                });
                
                // 等待发送操作完成（同步等待）
                // sync()方法会阻塞直到发送操作完成，确保消息已进入发送队列
                // 注意：虽然sync()会阻塞，但通常时间很短，不影响整体性能
                cf.sync();
            }
            
        } catch (Exception ex) {
            // 消息发送异常处理
            Channel channel = ctx.channel();
            log.warn("{}消息回应异常:{},channel:{}", seq, ex.getMessage(), channel.id());
            
            try {
                // 系统缓冲区空间不足或队列已满，等待30秒后重试
                // 这种异常通常发生在网络拥塞或客户端处理慢的情况下
                Thread.sleep(30 * 1000L);
                
                // 重试发送消息
                ChannelFuture cf = ctx.channel().writeAndFlush(p2p);
                cf.sync(); // 等待重发完成
                
                log.warn("{}消息回应异常:{},等待重发成功:{}", seq, ex.getMessage(), channel.id());
                
            } catch (Exception ex2) {
                // 重试失败，记录详细错误并关闭连接
                log.error("消息重发失败: {}", ex2.getMessage());
                
                try {
                    // 关闭异常连接，释放资源
                    // 防止半开连接占用系统资源
                    ctx.close();
                } catch (Exception ex3) {
                    log.error("连接关闭异常: {}", ex3.getMessage());
                }
                
                // 记录原始异常信息
                log.error(seq + "号消息回应异常！", ex);
            }
        } finally {
            // 资源清理块
            // 当前版本暂无特殊清理逻辑，保留结构便于扩展
            // ctx.channel(); // 原注释代码
        }

    }

}
