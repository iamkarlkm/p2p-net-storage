package javax.net.p2p.server.handler;

import com.q3lives.chdfs.CosUtil;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.CloudFilesCommandModel;
import javax.net.p2p.model.P2PWrapper;

/**
 *
 * @author Administrator
 */
public class CosCommandServerHandler implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.COS_COMMAND;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand().getValue() == P2PCommand.COS_COMMAND.getValue()) {
                //P2PWrapper r = null;
                CloudFilesCommandModel payload = (CloudFilesCommandModel) request.getData();
                if (null == payload.getCommand()) {
                    return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "command不能为空！");
                } else {
                    switch (payload.getCommand()) {
                        case "exists":
                            if (CosUtil.exists(payload.getParams()[0])) {
                                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, null);
                            }
                            break;
                        case "rm":
                            if (CosUtil.remove(payload.getParams()[0])) {
                                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, null);
                            }
                            break;

                        case "ls":
                            payload.setData(CosUtil.listObjects(payload.getParams()[0]));
                            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, payload);
                        default:
                            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "未知命令！当前支持的命令集：exists,rm,ls");
                    }
                }
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "未知错误");
            } else {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "指令分发内部校验错误！");
            }
        } catch (Exception e) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, e.toString());
        }

    }

}
