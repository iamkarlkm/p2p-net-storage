package javax.net.p2p.im.model;

import java.io.Serializable;

/**
 * 用户登录请求模型
 * 
 * @author IM System
 * @version 1.0
 * @since 2026
 */
public class UserLoginRequest implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /** 用户ID */
    private String userId;
    
    /** 用户名 */
    private String username;
    
    /** 密码（加密后） */
    private String password;
    
    /** 用户昵称 */
    private String nickname;
    
    /** 头像URL */
    private String avatar;
    
    /** 客户端IP地址 */
    private String clientIp;
    
    /** 客户端端口 */
    private int clientPort;
    
    /** 登录时间戳 */
    private long timestamp;
    
    public UserLoginRequest() {
        this.timestamp = System.currentTimeMillis();
    }
    
    // Getter和Setter
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
    
    public String getPassword() {
        return password;
    }
    
    public void setPassword(String password) {
        this.password = password;
    }
    
    public String getNickname() {
        return nickname;
    }
    
    public void setNickname(String nickname) {
        this.nickname = nickname;
    }
    
    public String getAvatar() {
        return avatar;
    }
    
    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }
    
    public String getClientIp() {
        return clientIp;
    }
    
    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }
    
    public int getClientPort() {
        return clientPort;
    }
    
    public void setClientPort(int clientPort) {
        this.clientPort = clientPort;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
