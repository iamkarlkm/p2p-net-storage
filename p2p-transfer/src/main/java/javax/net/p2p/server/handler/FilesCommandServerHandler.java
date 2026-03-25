package javax.net.p2p.server.handler;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.FilesCommandModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.storage.SharedStorage;
import javax.net.p2p.utils.FileUtil;

/**
 *
 * @author Administrator
 */
public class FilesCommandServerHandler implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.FILES_COMMAND;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand().getValue() == P2PCommand.FILES_COMMAND.getValue()) {
                FilesCommandModel payload = (FilesCommandModel) request.getData();
                if (null == payload.getCommand()) {
                    return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "FilesCommandModel.command不能为空！");
                } else {
                    File parent = SharedStorage.getStorageLocation(payload.storeId);
                    if (parent == null) {
                        return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "存储ID对应目录不存在 -> " + payload.storeId);
                    }
                    File file = FileUtil.getSandboxFile(payload.storeId, payload.getParams()[0]);
                    switch (payload.getCommand()) {
                        case "exists":
                            if (file.exists()) {
                                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, null);
                            }
                            break;
                        case "rm":
                            if (file.delete()) {
                                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, null);
                            }
                            break;
                        case "mkdirs":
                            if (file.mkdirs()) {
                                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, null);
                            }
                            break;
                        case "rename":
                            if (file.renameTo(new File(parent, payload.getParams()[1]))) {
                                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, null);
                            }
                            break;
                        case "ls":
                            File[] files = file.listFiles();
                            List<String> list = new ArrayList();
                            for (File f : files) {
                                list.add(f.getAbsolutePath());
                            }
                            payload.setData(list);
                            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, payload);
                        default:
                            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "未知命令！当前支持的命令集：exists,rm,mkdirs,rename,ls");
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
