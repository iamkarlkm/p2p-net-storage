package javax.net.p2p.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.util.List;
import java.util.Map;

/**
 * IM聊天消息模型
 * 用于点对点聊天、群聊、消息转发等
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IMChatModel {
    /** 消息唯一ID (UUID) */
    private String msgId;
    
    /** 发送者ID */
    private String senderId;
    
    /** 接收者ID (用户ID或群组ID) */
    private String receiverId;
    
    /** 接收者类型 (USER, GROUP) */
    private String receiverType;
    
    /** 消息类型 (TEXT, IMAGE, FILE, AUDIO, VIDEO, SYSTEM) */
    private String msgType;
    
    /** 消息内容 (文本或文件URL/ID) */
    private String content;
    
    /** 消息发送时间戳 */
    private long timestamp;
    
    /** 消息扩展数据 (JSON格式) */
    private String extra;
    
    /** 引用消息ID (用于回复) */
    private String quoteMsgId;
    
    /** @提及的用户列表 */
    private List<String> atUsers;
    
    /** 文件信息 (如果消息类型是文件/图片/语音/视频) */
    private FileDataModel fileInfo;
}
