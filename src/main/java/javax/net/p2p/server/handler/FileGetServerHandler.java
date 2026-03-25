package javax.net.p2p.server.handler;

import java.io.File;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractLongTimedRequestAdapter;
import javax.net.p2p.config.P2PConfig;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.FileDataModel;
import javax.net.p2p.model.FileSegmentsDataModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.FileUtil;
import javax.net.p2p.utils.SecurityUtils;

/**
 *
 * @author Administrator
 */
public class FileGetServerHandler extends AbstractLongTimedRequestAdapter implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.GET_FILE;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand().getValue() == P2PCommand.GET_FILE.getValue()) {
                FileDataModel payload = (FileDataModel) request.getData();
                File file = FileUtil.getAndCheckExistsSandboxFile(payload.storeId, payload.path);
                if (file.length() <= P2PConfig.DATA_BLOCK_SIZE) {
                    payload.data = FileUtil.loadFile(file);
                    payload.length = file.length();
                    return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_GET_FILE, payload);
                } else {
                    FileSegmentsDataModel segments = new FileSegmentsDataModel(payload.storeId, payload.path);
                    segments.start = 0;
                    segments.blockIndex = 0;
                    segments.length = file.length();
                    segments.blockSize = P2PConfig.DATA_BLOCK_SIZE;
                    segments.blockData = FileUtil.loadFile(file, segments.start, segments.blockSize);
                    segments.blockMd5 = SecurityUtils.toMD5(segments.blockData);
                    segments.md5 = SecurityUtils.getFileMD5String(file);
                    return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_GET_FILE_SEGMENTS, segments);
                }
            } else {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "指令内部校验错误！");
            }
        } catch (Exception e) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, e.toString());
        }

    }

}
