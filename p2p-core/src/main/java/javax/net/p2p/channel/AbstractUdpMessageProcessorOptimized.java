//package javax.net.p2p.channel;
//
//import io.netty.buffer.ByteBuf;
//import io.netty.channel.Channel;
//import io.netty.channel.ChannelHandlerContext;
//import io.netty.channel.socket.DatagramPacket;
//import java.net.InetSocketAddress;
//import javax.net.p2p.api.P2PCommand;
//import javax.net.p2p.common.pool.HybridObjectPool;
//import javax.net.p2p.model.P2PWrapper;
//import javax.net.p2p.utils.SerializationUtil;
//import lombok.extern.slf4j.Slf4j;
//
///**
// *
// * 优化后的UDP消息处理器（应用对象池）
// *
// * 优化点：
// *
// * 使用对象池创建P2PWrapper 及时回收不再使用的对象 监控对象池性能
// *
// * @author iamkarl@163.com
// */
//@Slf4j
//public abstract class AbstractUdpMessageProcessorOptimized extends AbstractUdpMessageProcessor {
//
//    public AbstractUdpMessageProcessorOptimized(int magic, Integer queueSize) {
//        super(magic, queueSize);
//    }
//
//    /**
//     *
//     * 发送响应消息（优化版本）
//     *
//     * 改进：
//     *
//     * 使用对象池创建P2PWrapper 序列化后立即回收对象 减少对象创建开销
//     */
//    @Override
//    public void sendResponse(Channel channel, InetSocketAddress remoteAddess, P2PWrapper response, int magic) {
//        P2PWrapper pooledResponse = null;
//        try {
//// 检查是否有未ACK的消息
//            if (lastMessageMap.containsKey(remoteAddess)) {
//                if (log.isDebugEnabled()) {
//                    log.debug("丢弃新消息: 远程地址={}, 命令={}, 原因: 存在未ACK的消息", remoteAddess, response.getCommand());
//                }
//                return;
//            }
//
//            ByteBuf buffer;
//            // 心跳消息特殊处理：使用缓存
//            switch (response.getCommand()) {
//                case HEART_PONG:
//                    buffer = cachePongMap.get(magic);
//                    if (buffer == null) {
//                        // 从对象池获取对象
//                        pooledResponse = HybridObjectPool.acquire(0, P2PCommand.HEART_PONG);
//                        buffer = SerializationUtil.serializeToByteBuf(pooledResponse, magic);
//                        cachePongMap.put(magic, buffer);// 序列化完成，立即回收
//                        HybridObjectPool.recycle(pooledResponse);
//                        pooledResponse = null;
//                    }
//                    buffer.retain();
//                    break;
//
//                case HEART_PING:
//                    buffer = cachePingMap.get(magic);
//                    if (buffer == null) {
//                        // 从对象池获取对象
//                        pooledResponse = HybridObjectPool.acquire(0, P2PCommand.HEART_PING);
//                        buffer = SerializationUtil.serializeToByteBuf(pooledResponse, magic);
//                        cachePingMap.put(magic, buffer);
//                        // 序列化完成，立即回收
//                        HybridObjectPool.recycle(pooledResponse);
//                        pooledResponse = null;
//                    }
//                    buffer.retain();
//                    break;
//
//                default:
//                    // 普通消息：直接序列化传入的response
//                    // 注意：这里的response可能来自外部，不一定是池化对象
//                    buffer = SerializationUtil.serializeToByteBuf(response, magic);
//                    cacheLastResponse(remoteAddess, buffer);
//                    break;
//            }
//
//            if (log.isDebugEnabled()) {
//                log.debug("发送响应消息: 远程地址={}, 命令={}, 大小={}字节",
//                    remoteAddess, response.getCommand(), buffer.readableBytes());
//            }
//
//            sendResponse(channel, remoteAddess, buffer);
//        } catch (Exception ex) {
//            log.warn("消息处理异常: 消息={}, 错误={}, 关闭通道={}",
//                response, ex.getMessage(), channel.id());
//            try {
//                channel.close();
//            } catch (Exception ex2) {
//                log.error("关闭通道失败: {}", ex2.getMessage());
//            }
//        } finally {
//// 确保对象被回收
//            if (pooledResponse != null) {
//                HybridObjectPool.recycle(pooledResponse);
//            }
//        }
//    }
//
//    /**
//     *
//     * 创建响应消息（使用对象池）
//     *
//     * @param seq 序列号
//     * @param command 命令类型
//     * @return P2PWrapper实例
//     */
//    protected P2PWrapper createResponse(int seq, P2PCommand command) {
//        return HybridObjectPool.acquire(seq, command);
//    }
//
//    /**
//     *
//     * 创建响应消息（带数据，使用对象池）
//     *
//     * @param seq 序列号
//     * @param command 命令类型
//     * @param data 数据对象
//     * @return P2PWrapper实例
//     */
//    protected P2PWrapper createResponse(int seq, P2PCommand command, Object data) {
//        P2PWrapper wrapper = HybridObjectPool.acquire(seq, command);
//        wrapper.setData(data);
//        return wrapper;
//    }
//
//    /**
//     *
//     * 回收响应消息
//     *
//     * @param wrapper 待回收的对象
//     */
//    protected void recycleResponse(P2PWrapper wrapper) {
//        HybridObjectPool.recycle(wrapper);
//    }
//
//    /**
//     *
//     * Handler移除时的回调（优化版本）
//     *
//     * 改进：
//     *
//     * 输出对象池监控指标 清理资源
//     */
//    @Override
//    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception { // 输出对象池监控指标 if (log.isInfoEnabled()) { log.info("对象池监控指标:\n{}", HybridObjectPool.getMetrics()); }
//        super.handlerRemoved(ctx);
//    }
//}
