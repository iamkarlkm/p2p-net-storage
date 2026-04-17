package javax.net.p2p.server.handler;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractLongTimedRequestAdapter;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.FileListEntry;
import javax.net.p2p.model.FileListRequest;
import javax.net.p2p.model.FileListResponse;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.FileUtil;

public class FileListServerHandler extends AbstractLongTimedRequestAdapter implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.FILE_LIST;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand().getValue() == P2PCommand.FILE_LIST.getValue()) {
                FileListRequest payload = (FileListRequest) request.getData();
                int page = payload.page <= 0 ? 1 : payload.page;
                int pageSize = payload.pageSize <= 0 ? 100 : payload.pageSize;
                if (pageSize > 1000) {
                    pageSize = 1000;
                }

                File dir = FileUtil.getAndCheckExistsSandboxFile(payload.storeId, payload.path);
                if (!dir.isDirectory()) {
                    return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "not a directory");
                }

                File[] files = dir.listFiles();
                if (files == null) {
                    return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "listFiles failed");
                }
                Arrays.sort(files, Comparator.comparing(File::getName));

                int total = files.length;
                int start = (page - 1) * pageSize;
                int end = Math.min(start + pageSize, total);
                List<FileListEntry> items = new ArrayList<>();
                if (start < total) {
                    for (int i = start; i < end; i++) {
                        File f = files[i];
                        String name = f.getName();
                        String p = payload.path.endsWith("/") ? payload.path + name : payload.path + "/" + name;
                        boolean isDir = f.isDirectory();
                        long size = isDir ? 0 : f.length();
                        long modifiedMs = f.lastModified();
                        items.add(new FileListEntry(name, p, isDir, size, modifiedMs));
                    }
                } else {
                    page = 1;
                }

                FileListResponse resp = new FileListResponse(page, pageSize, total, items);
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, resp);
            } else {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "指令分发内部校验错误！");
            }
        } catch (Exception e) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, e.toString());
        }
    }
}

