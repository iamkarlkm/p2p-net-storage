package javax.net.p2p.server.handler;

import java.io.File;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractStreamRequestAdapter;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.config.P2PConfig;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.FileDataModel;
import javax.net.p2p.model.FileSegmentsDataModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.server.ServerSendUdpMesageExecutor;
import javax.net.p2p.utils.FileUtil;

/**
 *
 * @author Administrator
 */
public class FileGetStreamServerHandler extends AbstractStreamRequestAdapter implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.GET_FILE_STREAM;
    }

    @Override
    public void processStream(AbstractSendMesageExecutor executor, P2PWrapper request) throws InterruptedException {
        try {
            if (request.getCommand() == P2PCommand.GET_FILE_STREAM) {
                FileDataModel payload = (FileDataModel) request.getData();
                File file = FileUtil.getAndCheckExistsSandboxFile(payload.storeId, payload.path);
                if (file.length() <= P2PConfig.DATA_BLOCK_SIZE) {
                    payload.length = file.length();
                    FileSegmentsDataModel segments = new FileSegmentsDataModel(payload.storeId, payload.path);
                    segments.start = 0;
                    segments.blockIndex = 0;
                    segments.length = file.length();
                    segments.blockSize = (int) file.length();
                    segments.blockData = FileUtil.loadFile(file);
                    executor.sendResponse(StreamP2PWrapper.buildStream(request.getSeq(),0, P2PCommand.R_OK_GET_FILE_STREAM, segments, true));
                } else {
                    FileSegmentsDataModel segments = new FileSegmentsDataModel(payload.storeId, payload.path);
                    segments.start = 0;
                    segments.blockIndex = 0;
                    segments.length = file.length();
                    segments.blockSize = P2PConfig.DATA_BLOCK_SIZE;
                    segments.blockData = FileUtil.loadFile(file, segments.start, segments.blockSize);
                    //segments.blockMd5 = SecurityUtils.toMD5(segments.blockData);
                    executor.sendResponse(StreamP2PWrapper.buildStream(request.getSeq(),0, P2PCommand.R_OK_GET_FILE_STREAM, segments));
                    int rest = (int) (file.length() % P2PConfig.DATA_BLOCK_SIZE);
                    int count = file.length() / P2PConfig.DATA_BLOCK_SIZE + rest == 0 ? 0 : 1;
                    int last = count - 1;
                    
                    for (int i = 1; i < count; i++) {
                        if (continued) {
                            segments.start += P2PConfig.DATA_BLOCK_SIZE;
                            segments.blockIndex = i;
                            segments.length = file.length();
                            segments.blockSize = P2PConfig.DATA_BLOCK_SIZE;

                            if (last > i) {
                                segments.blockData = FileUtil.loadFile(file, segments.start, segments.blockSize);
                                //segments.blockMd5 = SecurityUtils.toMD5(segments.blockData);
                                executor.sendResponse(StreamP2PWrapper.buildStream(request.getSeq(), i, P2PCommand.R_OK_GET_FILE_STREAM, segments));
                            } else {
                                if (rest > 0) {
                                    segments.blockSize = rest;
                                }
                                segments.blockData = FileUtil.loadFile(file, segments.start, segments.blockSize);
                                //segments.blockMd5 = SecurityUtils.toMD5(segments.blockData);
                                //segments.md5 = SecurityUtils.getFileMD5String(file);
                                executor.sendResponse(StreamP2PWrapper.buildStream(request.getSeq(), i, P2PCommand.R_OK_GET_FILE_STREAM, segments, true));
                            }
                        }
                    }
                }
            } else {
                executor.sendResponse(P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "指令内部校验错误！"));
            }
        } catch (Exception e) {
            executor.sendResponse(P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, e.toString()));
        }
    }
}
