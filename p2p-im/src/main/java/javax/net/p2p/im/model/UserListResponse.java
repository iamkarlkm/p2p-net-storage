package javax.net.p2p.im.model;

import java.io.Serializable;
import java.util.List;

/**
 * 获取在线用户列表响应模型
 * 
 * @author IM System
 * @version 1.0
 * @since 2026
 */
public class UserListResponse implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /** 是否成功 */
    private boolean success;
    
    /** 响应消息 */
    private String message;
    
    /** 在线用户总数 */
    private int totalCount;
    
    /** 在线用户列表 */
    private List<javax.net.p2p.model.UserInfo> users;
    
    /** 响应时间戳 */
    private long timestamp;
    
    public UserListResponse() {
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
    
    public int getTotalCount() {
        return totalCount;
    }
    
    public void setTotalCount(int totalCount) {
        this.totalCount = totalCount;
    }
    
    public List<javax.net.p2p.model.UserInfo> getUsers() {
        return users;
    }
    
    public void setUsers(List<javax.net.p2p.model.UserInfo> users) {
        this.users = users;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
