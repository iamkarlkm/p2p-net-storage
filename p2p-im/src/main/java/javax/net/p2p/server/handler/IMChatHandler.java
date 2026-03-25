package javax.net.p2p.server.handler;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.IMChatModel;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;

/**
 * IM聊天消息处理器
 * 处理单聊、群聊消息的转发和存储
 */
@Slf4j
public class IMChatHandler implements P2PCommandHandler {
    
    // 模拟消息存储
    // private static final List<IMChatModel> MESSAGE_STORE = new ArrayList<>();

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.IM_CHAT_SEND;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand().getValue() != P2PCommand.IM_CHAT_SEND.getValue()) {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "Invalid command for ChatHandler");
            }
            
            IMChatModel chatMsg = (IMChatModel) request.getData();
            if (chatMsg == null || chatMsg.getSenderId() == null || chatMsg.getReceiverId() == null) {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "Incomplete chat message data");
            }
            
            log.info("Processing chat message: {} -> {} ({})", chatMsg.getSenderId(), chatMsg.getReceiverId(), chatMsg.getMsgType());
            
            // 1. 验证消息完整性
            if (chatMsg.getMsgId() == null) {
                // 生成UUID
                chatMsg.setMsgId(java.util.UUID.randomUUID().toString());
            }
            if (chatMsg.getTimestamp() <= 0) {
                chatMsg.setTimestamp(System.currentTimeMillis());
            }
            
            // 2. 根据接收者类型处理
            if ("USER".equalsIgnoreCase(chatMsg.getReceiverType())) {
                // 单聊：查找接收者在线状态并转发
                // TODO: 实现点对点消息路由
                // P2PClient.sendMessage(receiverIp, receiverPort, chatMsg);
                
            } else if ("GROUP".equalsIgnoreCase(chatMsg.getReceiverType())) {
                // 群聊：获取群成员列表并分发
                // TODO: 实现群消息分发
                // List<String> members = GroupManager.getMembers(chatMsg.getReceiverId());
                // for (String memberId : members) { ... }
                
            } else {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "Unknown receiver type: " + chatMsg.getReceiverType());
            }
            
            // 3. 存储消息记录（可选）
            // MESSAGE_STORE.add(chatMsg);
            
            log.info("Chat message processed successfully: {}", chatMsg.getMsgId());
            
            // 返回成功确认
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, chatMsg.getMsgId());
            
        } catch (Exception e) {
            log.error("Chat processing failed", e);
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "Chat failed: " + e.getMessage());
        }
    }
}
