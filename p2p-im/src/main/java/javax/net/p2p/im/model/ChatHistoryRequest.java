package javax.net.p2p.im.model;

import java.io.Serializable;
import java.util.List;

/**
 * 聊天历史消息请求模型
 * 
 * @author IM System
 * @version 1.0
 * @since 2026
 */
public class ChatHistoryRequest implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /** 查询者用户ID */
    private String userId;

    /** 对端用户ID（点对点聊天时使用） */
    private String peerId;

    /** 目标ID（用户ID或群组ID） */
    private String targetId;
    
    /** 是否是群聊 */
    private boolean isGroup;
    
    /** 开始时间 */
    private long startTime;
    
    /** 结束时间 */
    private long endTime;
    
    /** 消息数量限制 */
    private int limit;
    
    /** 偏移量 */
    private int offset;
    
    public ChatHistoryRequest() {
        this.limit = 50;
        this.offset = 0;
    }
    
    // Getter和Setter
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getPeerId() {
        return peerId;
    }

    public void setPeerId(String peerId) {
        this.peerId = peerId;
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
    
    public int getLimit() {
        return limit;
    }
    
    public void setLimit(int limit) {
        this.limit = limit;
    }
    
    public int getOffset() {
        return offset;
    }
    
    public void setOffset(int offset) {
        this.offset = offset;
    }
}
