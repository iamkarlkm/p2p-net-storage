package javax.net.p2p.im.model;

import java.io.Serializable;
import java.util.List;

/**
 * 聊天历史消息响应模型
 * 
 * @author IM System
 * @version 1.0
 * @since 2026
 */
public class ChatHistoryResponse implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /** 是否成功 */
    private boolean success;
    
    /** 响应消息 */
    private String message;
    
    /** 用户ID */
    private String userId;
    
    /** 目标ID */
    private String targetId;
    
    /** 是否是群聊 */
    private boolean isGroup;
    
    /** 开始时间 */
    private long startTime;
    
    /** 结束时间 */
    private long endTime;
    
    /** 聊天消息列表 */
    private List<javax.net.p2p.model.ChatMessage> messages;
    
    /** 是否还有更多消息 */
    private boolean hasMore;
    
    /** 响应时间戳 */
    private long timestamp;
    
    public ChatHistoryResponse() {
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
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public String getTargetId() {
        return targetId;
    }
    
    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }
    
    public boolean isGroup() {
        return isGroup;
    }
    
    public void setGroup(boolean group) {
        isGroup = group;
    }
    
    public long getStartTime() {
        return startTime;
    }
    
    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }
    
    public long getEndTime() {
        return endTime;
    }
    
    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }
    
    public List<javax.net.p2p.model.ChatMessage> getMessages() {
        return messages;
    }
    
    public void setMessages(List<javax.net.p2p.model.ChatMessage> messages) {
        this.messages = messages;
    }
    
    public boolean isHasMore() {
        return hasMore;
    }
    
    public void setHasMore(boolean hasMore) {
        this.hasMore = hasMore;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
