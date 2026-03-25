package javax.net.p2p.server.handler;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.IMGroupModel;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;

/**
 * IM群组管理处理器
 * 处理群组创建、解散、成员变更
 */
@Slf4j
public class IMGroupHandler implements P2PCommandHandler {
    
    // 模拟群组存储
    private static final Map<String, IMGroupModel> GROUPS = new ConcurrentHashMap<>();

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.IM_GROUP_CREATE;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand().getValue() != P2PCommand.IM_GROUP_CREATE.getValue()) {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "Invalid command for GroupHandler");
            }
            
            IMGroupModel group = (IMGroupModel) request.getData();
            if (group == null || group.getName() == null) {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "Group data or name cannot be null");
            }
            
            log.info("Processing group creation request: {}", group.getName());
            
            // 1. 生成群ID
            String groupId = "group_" + System.currentTimeMillis();
            group.setGroupId(groupId);
            group.setCreateTime(System.currentTimeMillis());
            group.setStatus("ACTIVE");
            
            // 2. 验证创建者权限
            // TODO: check owner permissions
            
            // 3. 存储群组信息
            GROUPS.put(groupId, group);
            
            log.info("Group created successfully: {} ({})", group.getName(), groupId);
            
            // 返回创建后的群组信息
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, group);
            
        } catch (Exception e) {
            log.error("Group creation failed", e);
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "Group creation failed: " + e.getMessage());
        }
    }
    
    /**
     * 获取群组信息
     */
    public static IMGroupModel getGroup(String groupId) {
        return GROUPS.get(groupId);
    }
    
    /**
     * 更新群组信息
     */
    public static void updateGroup(IMGroupModel group) {
        if (group != null && group.getGroupId() != null) {
            GROUPS.put(group.getGroupId(), group);
        }
    }
    
    /**
     * 删除群组
     */
    public static void removeGroup(String groupId) {
        GROUPS.remove(groupId);
    }
}
