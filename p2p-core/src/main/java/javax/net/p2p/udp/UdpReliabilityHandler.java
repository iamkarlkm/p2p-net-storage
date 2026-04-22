package javax.net.p2p.udp;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import java.net.PortUnreachableException;
import java.net.InetSocketAddress;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.P2PWrapper;
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
        // 可靠性处理下沉到 UdpFrameInbound（完整帧重组后）执行，这里只透传原始Datagram。
        ctx.fireChannelRead(packet.retain());
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
        return sendReliableMessage(ctx.channel(), remoteAddress, message);
    }

    public int sendReliableMessage(Channel channel, InetSocketAddress remoteAddress,
                                   P2PWrapper<?> message) {
        if (!enableReliability || reliabilityManager == null) {
            // 可靠性未启用，直接发送
            sendUnreliableMessage(channel, remoteAddress, message);
            return message.getSeq();
        }
        return reliabilityManager.sendReliableMessage(channel, remoteAddress, message);
    }
    
    /**
     * 发送不可靠消息
     * @param ctx ChannelHandlerContext
     * @param remoteAddress 远程地址
     * @param message 消息
     */
    public void sendUnreliableMessage(ChannelHandlerContext ctx, InetSocketAddress remoteAddress, 
                                      P2PWrapper<?> message) {
        sendUnreliableMessage(ctx.channel(), remoteAddress, message);
    }

    public void sendUnreliableMessage(Channel channel, InetSocketAddress remoteAddress,
                                      P2PWrapper<?> message) {
        if (reliabilityManager != null) {
            reliabilityManager.sendUnreliableMessage(channel, remoteAddress, message);
        } else {
            throw new IllegalStateException("reliabilityManager is null");
        }
    }

    /**
     * 由 UdpFrameInbound 在“完整帧解码后”调用。
     * @return true=继续交给业务层处理；false=被可靠性层消费（如 ACK）。
     */
    public boolean handleDecodedMessage(Channel channel, InetSocketAddress sender, P2PWrapper<?> message) {
        if (!enableReliability || reliabilityManager == null || message == null) {
            return true;
        }
        if (message.getCommand() == P2PCommand.UDP_FRAME_ACK || message.getCommand() == P2PCommand.UDP_FRAME_RESET) {
            return true;
        }
        if (message.getCommand() == P2PCommand.UDP_RELIABILITY_ACK) {
            reliabilityManager.processAck(message.getSeq(), sender);
            return false;
        }
        boolean needAck = reliabilityManager.processDataMessage(message.getSeq(), null, sender);
        if (needAck) {
            reliabilityManager.sendAck(channel, sender, message.getSeq());
        }
        return true;
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof PortUnreachableException) {
            // UDP 端口不可达属于常见网络异常，不应立即关闭通道，等待超时机制处理
            log.warn("UDP端口不可达: {}", cause.getMessage());
            return;
        }
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

    public boolean isEnabled() {
        return enableReliability && reliabilityManager != null;
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
