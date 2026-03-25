package javax.net.p2p.im.model;

import java.io.Serializable;
import java.util.List;

/**
 * 用户登录响应模型
 * 
 * @author IM System
 * @version 1.0
 * @since 2026
 */
public class UserLoginResponse implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /** 是否成功 */
    private boolean success;
    
    /** 响应消息 */
    private String message;
    
    /** 用户ID */
    private String userId;
    
    /** 用户名 */
    private String username;
    
    /** 昵称 */
    private String nickname;
    
    /** 在线用户数量 */
    private int onlineUserCount;
    
    /** 在线用户ID列表 */
    private List<String> onlineUserIds;
    
    /** 响应时间戳 */
    private long timestamp;
    
    public UserLoginResponse() {
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
    
    public String getUsername() {
        return username;
    }
    
    public void setUsername(String username) {
        this.username = username;
    }
    
    public String getNickname() {
        return nickname;
    }
    
    public void setNickname(String nickname) {
        this.nickname = nickname;
    }
    
    public int getOnlineUserCount() {
        return onlineUserCount;
    }
    
    public void setOnlineUserCount(int onlineUserCount) {
        this.onlineUserCount = onlineUserCount;
    }
    
    public List<String> getOnlineUserIds() {
        return onlineUserIds;
    }
    
    public void setOnlineUserIds(List<String> onlineUserIds) {
        this.onlineUserIds = onlineUserIds;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
