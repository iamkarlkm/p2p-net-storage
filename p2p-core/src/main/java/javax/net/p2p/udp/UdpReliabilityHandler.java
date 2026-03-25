package javax.net.p2p.udp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import java.net.InetSocketAddress;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SerializationUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * UDP可靠性处理器 - Netty ChannelHandler实现
 * 
 * 功能：
 * 1. 拦截UDP消息，添加可靠性处理
 * 2. 处理ACK消息，更新可靠性状态
 * 3. 重传超时消息
 * 4. 处理乱序消息重组
 * 
 * 集成到Netty Pipeline的方式：
 * ┌─────────────────────────────────────────────┐
 * │         Netty Channel Pipeline              │
 * ├─────────────────────────────────────────────┤
 * │ IdleStateHandler      (连接空闲检测)        │
 * ├─────────────────────────────────────────────┤
 * │ UdpReliabilityHandler (可靠性处理)          │
 * ├─────────────────────────────────────────────┤
 * │ BusinessProcessor    (业务逻辑处理器)       │
 * └─────────────────────────────────────────────┘
 * 
 * @author karl
 */
@Slf4j
public class UdpReliabilityHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    
    private final UdpReliabilityManager reliabilityManager;
    private final boolean enableReliability;
    
    /**
     * 构造函数
     * @param enableReliability 是否启用可靠性机制
     */
    public UdpReliabilityHandler(boolean enableReliability) {
        this.enableReliability = enableReliability;
        this.reliabilityManager = enableReliability ? new UdpReliabilityManager() : null;
    }
    
    /**
     * 构造函数（使用自定义的可靠性管理器）
     * @param reliabilityManager 可靠性管理器
     */
    public UdpReliabilityHandler(UdpReliabilityManager reliabilityManager) {
        this.enableReliability = reliabilityManager != null;
        this.reliabilityManager = reliabilityManager;
    }
    
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        InetSocketAddress sender = packet.sender();
        ByteBuf content = packet.content();
        
        try {
            // 解析消息
            P2PWrapper<?> message = parseMessage(content);
            if (message == null) {
                log.warn("Failed to parse UDP message from {}", sender);
                return;
            }
            
            // 处理ACK消息
            if (message.getCommand() == P2PCommand.UDP_FRAME_ACK) {
                handleAckMessage(message, sender);
                return;
            }
            
            // 处理普通数据消息
            handleDataMessage(ctx, message, sender, content);
            
        } finally {
            // 注意：这里不能释放content，因为后续的handler可能还需要使用
            // packet会由Netty自动管理引用计数
        }
    }
    
    /**
     * 处理ACK消息
     */
    private void handleAckMessage(P2PWrapper<?> ackMessage, InetSocketAddress sender) {
        if (!enableReliability || reliabilityManager == null) {
            return;
        }
        
        int ackSeq = ackMessage.getSeq();
        reliabilityManager.processAck(ackSeq, sender);
        
        log.debug("Processed ACK seq={} from {}", ackSeq, sender);
    }
    
    /**
     * 处理数据消息
     */
    private void handleDataMessage(ChannelHandlerContext ctx, P2PWrapper<?> message, 
                                   InetSocketAddress sender, ByteBuf content) {
        if (!enableReliability || reliabilityManager == null) {
            // 可靠性未启用，直接传递给下一个handler
            forwardToNextHandler(ctx, message, sender, content);
            return;
        }
        
        int seq = message.getSeq();
        
        // 处理数据消息，检查是否需要ACK
        boolean needAck = reliabilityManager.processDataMessage(seq, content.retain(), sender);
        
        if (needAck) {
            // 发送ACK
            reliabilityManager.sendAck(ctx.channel(), sender, seq);
            log.debug("Sent ACK for seq={} to {}", seq, sender);
        }
        
        // 将消息传递给下一个handler
        forwardToNextHandler(ctx, message, sender, content);
    }
    
    /**
     * 发送可靠消息
     * @param ctx ChannelHandlerContext
     * @param remoteAddress 远程地址
     * @param message 消息
     * @return 消息序列号，-1表示发送失败或窗口已满
     */
    public int sendReliableMessage(ChannelHandlerContext ctx, InetSocketAddress remoteAddress, 
                                   P2PWrapper<?> message) {
        if (!enableReliability || reliabilityManager == null) {
            // 可靠性未启用，直接发送
            sendUnreliableMessage(ctx, remoteAddress, message);
            return message.getSeq();
        }
        
        return reliabilityManager.sendReliableMessage(ctx.channel(), remoteAddress, message);
    }
    
    /**
     * 发送不可靠消息
     * @param ctx ChannelHandlerContext
     * @param remoteAddress 远程地址
     * @param message 消息
     */
    public void sendUnreliableMessage(ChannelHandlerContext ctx, InetSocketAddress remoteAddress, 
                                      P2PWrapper<?> message) {
        if (reliabilityManager != null) {
            reliabilityManager.sendUnreliableMessage(ctx.channel(), remoteAddress, message);
        } else {
            // 直接发送
            ByteBuf buffer = serializeMessage(message);
            if (buffer != null) {
                try {
                    ctx.writeAndFlush(new DatagramPacket(buffer, remoteAddress));
                } finally {
                    buffer.release();
                }
            }
        }
    }
    
    /**
     * 转发消息给下一个handler
     */
    private void forwardToNextHandler(ChannelHandlerContext ctx, P2PWrapper<?> message, 
                                      InetSocketAddress sender, ByteBuf content) {
        // 创建新的DatagramPacket，保持原始内容
        DatagramPacket newPacket = new DatagramPacket(content.retain(), sender, (InetSocketAddress) ctx.channel().localAddress());
        
        // 将消息传递给下一个handler
        ctx.fireChannelRead(newPacket);
    }
    
    /**
     * 解析消息
     */
    private P2PWrapper<?> parseMessage(ByteBuf buffer) {
        try {
            // 使用项目现有的反序列化工具
            // 这里需要适配现有的序列化格式
            // 假设使用SerializationUtil进行反序列化
            return SerializationUtil.deserializeWrapper(P2PWrapper.class,buffer);
        } catch (Exception e) {
            log.error("Failed to parse UDP message: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 序列化消息
     */
    private ByteBuf serializeMessage(P2PWrapper<?> message) {
        try {
            // 使用项目现有的序列化工具
            return SerializationUtil.serializeToByteBuf(message, 0); // 0表示默认magic
        } catch (Exception e) {
            log.error("Failed to serialize message: {}", e.getMessage(), e);
            return null;
        }
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("UDP reliability handler exception: {}", cause.getMessage(), cause);
        ctx.close();
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 清理资源
        if (reliabilityManager != null) {
            reliabilityManager.shutdown();
        }
        super.channelInactive(ctx);
    }
    
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        // 清理资源
        if (reliabilityManager != null) {
            reliabilityManager.shutdown();
        }
        super.handlerRemoved(ctx);
    }
    
    /**
     * 获取可靠性管理器
     */
    public UdpReliabilityManager getReliabilityManager() {
        return reliabilityManager;
    }
    
    /**
     * 获取统计信息
     */
    public String getStatistics() {
        return reliabilityManager != null ? reliabilityManager.getStatistics() : "Reliability disabled";
    }
    
    /**
     * 清理指定地址的资源
     */
    public void cleanupForAddress(InetSocketAddress remoteAddress) {
        if (reliabilityManager != null) {
            reliabilityManager.cleanupForAddress(remoteAddress);
        }
    }
}