package javax.net.p2p.server.handler;

import java.io.File;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.FileDataModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.FileUtil;

public class FileMkdirsServerHandler implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.FILE_MKDIRS;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand().getValue() == P2PCommand.FILE_MKDIRS.getValue()) {
                FileDataModel payload = (FileDataModel) request.getData();
                File dir = FileUtil.getSandboxFileForWrite(payload.storeId, payload.path);
                if (dir.mkdirs()) {
                    return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, null);
                }
                if (dir.exists() && dir.isDirectory()) {
                    return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, null);
                }
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "mkdirs failed");
            } else {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "指令分发内部校验错误！");
            }
        } catch (Exception e) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, e.toString());
        }
    }
}

