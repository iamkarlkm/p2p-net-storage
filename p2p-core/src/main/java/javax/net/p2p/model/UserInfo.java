package javax.net.p2p.model;

import java.io.Serializable;

/**
 * 用户信息模型
 * 
 * 功能说明：
 * 1. 存储用户基本信息（用户ID、用户名、状态等）
 * 2. 用于用户登录、登出和在线状态管理
 * 3. 支持序列化，用于网络传输
 * 
 * 技术特点：
 * - 实现Serializable接口，支持Java序列化
 * - 使用标准JavaBean规范
 * - 提供完整的getter/setter方法
 * 
 * 使用场景：
 * - 用户登录时传递用户信息
 * - 在线用户列表展示
 * - 消息发送者和接收者标识
 * 
 * @author IM System
 * @version 1.0
 * @since 2026
 */
public class UserInfo implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /** 用户唯一标识 */
    private String userId;
    
    /** 用户名 */
    private String username;
    
    /** 用户昵称 */
    private String nickname;
    
    /** 用户状态：0-离线，1-在线，2-忙碌，3-离开 */
    private int status;
    
    /** 登录时间戳 */
    private long loginTime;
    
    /** 最后活动时间戳 */
    private long lastActiveTime;
    
    /** 用户IP地址 */
    private String ipAddress;
    
    private String avatar;
    
    /** 用户端口号 */
    private int port;
    
    public UserInfo() {
    }
    
    public UserInfo(String userId, String username, String nickname) {
        this.userId = userId;
        this.username = username;
        this.nickname = nickname;
        this.status = 1; // 默认在线
        this.loginTime = System.currentTimeMillis();
        this.lastActiveTime = this.loginTime;
    }
    
    // Getter和Setter方法
    
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
    
    public int getStatus() {
        return status;
    }
    
    public void setStatus(int status) {
        this.status = status;
    }
    
    public long getLoginTime() {
        return loginTime;
    }
    
    public void setLoginTime(long loginTime) {
        this.loginTime = loginTime;
    }
    
    public long getLastActiveTime() {
        return lastActiveTime;
    }
    
    public void setLastActiveTime(long lastActiveTime) {
        this.lastActiveTime = lastActiveTime;
    }
    
    public String getIpAddress() {
        return ipAddress;
    }
    
    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }
    
    public int getPort() {
        return port;
    }
    
    public void setPort(int port) {
        this.port = port;
    }
    
    /**
     * 更新最后活动时间
     */
    public void updateLastActiveTime() {
        this.lastActiveTime = System.currentTimeMillis();
    }
    
    /**
     * 判断用户是否在线（状态为1且最近5分钟内活动）
     */
    public boolean isOnline() {
        long currentTime = System.currentTimeMillis();
        return status == 1 && (currentTime - lastActiveTime) < 5 * 60 * 1000;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }
    
    
    
    @Override
    public String toString() {
        return "UserInfo{" +
                "userId='" + userId + '\'' +
                ", username='" + username + '\'' +
                ", nickname='" + nickname + '\'' +
                ", status=" + status +
                ", loginTime=" + loginTime +
                ", lastActiveTime=" + lastActiveTime +
                ", ipAddress='" + ipAddress + '\'' +
                ", port=" + port +
                '}';
    }

}