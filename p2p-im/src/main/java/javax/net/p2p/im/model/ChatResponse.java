package javax.net.p2p.im.model;

import java.io.Serializable;

/**
 * 聊天消息响应模型
 * 
 * @author IM System
 * @version 1.0
 * @since 2026
 */
public class ChatResponse implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /** 是否成功 */
    private boolean success;
    
    /** 响应消息 */
    private String message;
    
    /** 消息ID */
    private String messageId;
    
    /** 响应时间戳 */
    private long timestamp;
    
    public ChatResponse() {
        this.timestamp = System.currentTimeMillis();
    }
    
    // Getter和Setter
    public boolean isSuccess() {
        return success;
    }
    
    public void setSuccess(boolean success) {
        this.success = success;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    public String getMessageId() {
        return messageId;
    }
    
    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
