package javax.net.p2p.server.handler;

import java.io.File;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractLongTimedRequestAdapter;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.FileRenameRequest;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.FileUtil;

public class FileRenameServerHandler extends AbstractLongTimedRequestAdapter implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.FILE_RENAME;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand().getValue() == P2PCommand.FILE_RENAME.getValue()) {
                FileRenameRequest payload = (FileRenameRequest) request.getData();
                File src = FileUtil.getAndCheckExistsSandboxFile(payload.storeId, payload.srcPath);
                File dst = FileUtil.getSandboxFileForWrite(payload.storeId, payload.dstPath);
                boolean ok = src.renameTo(dst);
                if (ok) {
                    return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, null);
                }
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "rename failed");
            } else {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "指令分发内部校验错误！");
            }
        } catch (Exception e) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, e.toString());
        }
    }
}

