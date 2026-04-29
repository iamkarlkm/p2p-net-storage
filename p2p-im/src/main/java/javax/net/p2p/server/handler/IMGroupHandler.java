package javax.net.p2p.server.handler;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.im.runtime.ImRuntime;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.IMGroupModel;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;

/**
 * IM群组管理处理器
 * 处理群组创建、解散、成员变更
 */
@Slf4j
public class IMGroupHandler implements P2PCommandHandler {

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
            IMGroupModel out = ImRuntime.createGroup(group);
            log.info("Group created successfully: {} ({})", out.getName(), out.getGroupId());
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, out);
            
        } catch (Exception e) {
            log.error("Group creation failed", e);
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "Group creation failed: " + e.getMessage());
        }
    }
}
