package javax.net.p2p.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * IM用户信息模型
 * 用于用户登录、状态更新、信息查询等
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IMUserModel {
    /** 用户ID */
    private String userId;
    
    /** 用户名 */
    private String username;
    
    /** 昵称 */
    private String nickname;
    
    /** 密码/Token (仅登录时使用) */
    private String token;
    
    /** 在线状态 (ONLINE, OFFLINE, BUSY, AWAY) */
    private String status;
    
    /** IP地址 */
    private String ip;
    
    /** 端口 */
    private int port;
    
    /** RSA公钥 */
    private String publicKey;
    
    /** 头像URL */
    private String avatar;
    
    /** 个性签名 */
    private String signature;
    
    /** 扩展字段 */
    private String extra;
}
