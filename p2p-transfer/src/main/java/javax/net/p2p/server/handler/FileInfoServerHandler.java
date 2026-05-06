package javax.net.p2p.server.handler;

import java.io.File;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.config.P2PConfig;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.FileDataModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.storage.SharedStorage;
import javax.net.p2p.utils.SecurityUtils;

/**
 *
 * @author Administrator
 */
public class FileInfoServerHandler implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.INFO_FILE;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand().getValue() == P2PCommand.INFO_FILE.getValue()) {
                FileDataModel payload = (FileDataModel) request.getData();
                File parent = SharedStorage.getStorageLocation(payload.storeId);
                if (parent == null) {
                    return P2PErrors.stdError(request.getSeq(), P2PErrorCode.FILE_OPERATION_FAILED, "存储ID对应目录不存在: " + payload.storeId);
                }
                File file = new File(parent, payload.path);
                if (!file.exists()) {
                    return P2PErrors.stdError(request.getSeq(), P2PErrorCode.FILE_NOT_FOUND, "文件不存在 -> " + payload.path);
                } else {
                    payload.length = file.length();
                    //if (file.length() > P2PConfig.DATA_BLOCK_SIZE) {
                    payload.blockSize = P2PConfig.DATA_BLOCK_SIZE;
                    //}
                    if ("".equals(payload.md5)) {
                        payload.md5 = SecurityUtils.getFileMD5String(file);
                    }
                    return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, payload);
                }
            } else {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH, "指令分发内部校验错误！");
            }
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.FILE_IO_ERROR, e.toString());
        }

    }

}
