package javax.net.p2p.server.handler;

import java.io.File;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractLongTimedRequestAdapter;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.FileDataModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.FileUtil;
import javax.net.p2p.utils.SecurityUtils;

/**
 *
 * @author Administrator
 */
public class FileCheckServerHandler extends AbstractLongTimedRequestAdapter implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.CHECK_FILE;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand().getValue() == P2PCommand.CHECK_FILE.getValue()) {
                FileDataModel payload = (FileDataModel) request.getData();

                File file = FileUtil.getAndCheckExistsSandboxFile(payload.storeId, payload.path);
                if (file.length() != payload.length) {
                    return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, String.format("文件长度不一致 %s <> %s", payload.length, file.length()));
                } else if (payload.md5 != null) {
                    String md5 = SecurityUtils.getFileMD5String(file);
                    if (!md5.equals(payload.md5)) {
                        return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, String.format("MD5校验错误 %s <> %s", payload.md5, md5));
                    }
                }

                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, null);
            } else {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "指令分发内部校验错误！");
            }
        } catch (Exception e) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, e.toString());
        }

    }

}
