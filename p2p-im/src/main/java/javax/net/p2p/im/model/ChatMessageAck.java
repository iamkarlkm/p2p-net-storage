package javax.net.p2p.im.model;

import java.io.Serializable;

/**
 * 消息确认请求/响应模型
 * 
 * @author IM System
 * @version 1.0
 * @since 2026
 */
public class ChatMessageAck implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /** 消息ID */
    private String messageId;
    
    /** 确认类型：DELIVERED-已送达, READ-已读 */
    private String ackType;
    
    /** 确认时间 */
    private long ackTime;
    
    /** 确认者用户ID */
    private String userId;
    
    public ChatMessageAck() {
        this.ackTime = System.currentTimeMillis();
    }
    
    // Getter和Setter
    public String getMessageId() {
        return messageId;
    }
    
    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }
    
    public String getAckType() {
        return ackType;
    }
    
    public void setAckType(String ackType) {
        this.ackType = ackType;
    }
    
    public long getAckTime() {
        return ackTime;
    }
    
    public void setAckTime(long ackTime) {
        this.ackTime = ackTime;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
}
