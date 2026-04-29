package javax.net.p2p.server.handler;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.im.runtime.ImRuntime;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.IMUserModel;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;

/**
 * IM用户登录处理器
 * 处理用户登录、身份验证和会话建立
 */
@Slf4j
public class IMUserLoginHandler implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.IM_USER_LOGIN;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand().getValue() != P2PCommand.IM_USER_LOGIN.getValue()) {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "Invalid command for LoginHandler");
            }
            
            IMUserModel user = (IMUserModel) request.getData();
            if (user == null || user.getUserId() == null) {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "User data or ID cannot be null");
            }
            
            log.info("Processing login request for user: {}", user.getUserId());
            
            // TODO: 验证Token或密码
            // TODO: 验证RSA公钥
            
            // 更新在线状态
            ImRuntime.login(user);
            
            log.info("User {} logged in successfully", user.getUsername());
            
            // 返回更新后的用户信息（包含Token等）
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, user);
            
        } catch (Exception e) {
            log.error("Login processing failed", e);
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "Login failed: " + e.getMessage());
        }
    }
    
    /**
     * 获取在线用户
     */
    public static IMUserModel getOnlineUser(String userId) {
        return ImRuntime.getOnlineUser(userId);
    }
    
    /**
     * 移除在线用户
     */
    public static void removeUser(String userId) {
        ImRuntime.logout(userId);
    }
}
