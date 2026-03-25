package javax.net.p2p.im.model;

import java.io.Serializable;

/**
 * 用户心跳请求/响应模型
 * 
 * @author IM System
 * @version 1.0
 * @since 2026
 */
public class UserHeartbeat implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /** 用户ID */
    private String userId;
    
    /** 最后活动时间 */
    private long lastActiveTime;
    
    /** 心跳时间戳 */
    private long heartbeatTime;
    
    /** 在线用户数量 */
    private int onlineUserCount;
    
    public UserHeartbeat() {
        this.lastActiveTime = System.currentTimeMillis();
        this.heartbeatTime = System.currentTimeMillis();
    }
    
    // Getter和Setter
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public long getLastActiveTime() {
        return lastActiveTime;
    }
    
    public void setLastActiveTime(long lastActiveTime) {
        this.lastActiveTime = lastActiveTime;
    }
    
    public long getHeartbeatTime() {
        return heartbeatTime;
    }
    
    public void setHeartbeatTime(long heartbeatTime) {
        this.heartbeatTime = heartbeatTime;
    }
    
    public int getOnlineUserCount() {
        return onlineUserCount;
    }
    
    public void setOnlineUserCount(int onlineUserCount) {
        this.onlineUserCount = onlineUserCount;
    }
}
