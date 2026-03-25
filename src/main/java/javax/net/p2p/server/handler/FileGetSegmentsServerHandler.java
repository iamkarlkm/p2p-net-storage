package javax.net.p2p.server.handler;

import java.io.File;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractLongTimedRequestAdapter;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.FileSegmentsDataModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.FileUtil;
import javax.net.p2p.utils.SecurityUtils;

/**
 *
 * @author Administrator
 */
public class FileGetSegmentsServerHandler extends AbstractLongTimedRequestAdapter implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.GET_FILE_SEGMENTS;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand().getValue() == P2PCommand.GET_FILE_SEGMENTS.getValue()) {
                FileSegmentsDataModel payload = (FileSegmentsDataModel) request.getData();
                File file = FileUtil.getAndCheckExistsSandboxFile(payload.storeId, payload.path);
                payload.blockData = FileUtil.loadFile(file, payload.start, payload.blockSize);
                payload.blockMd5 = SecurityUtils.toMD5(payload.blockData);
                if (payload.md5 == null) {
                    payload.md5 = SecurityUtils.getFileMD5String(file);
                }
                return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_GET_FILE_SEGMENTS, payload);
            } else {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "指令内部校验错误！");
            }
        } catch (Exception e) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, e.toString());
        }

    }

}
