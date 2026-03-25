package javax.net.p2p.server.handler;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractLongTimedRequestAdapter;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.FileDataModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.FileUtil;

/**
 *
 * @author Administrator
 */
public class FilePutServerHandler extends AbstractLongTimedRequestAdapter implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.PUT_FILE;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand().getValue() == P2PCommand.PUT_FILE.getValue()) {
                FileDataModel payload = (FileDataModel) request.getData();
                File file = FileUtil.getSandboxFileForWrite(payload.storeId, payload.path);
                if (payload.length != payload.data.length) {
                    throw new RuntimeException("文件长度记录不一致:expected length=" + payload.length + ",actual length=" + payload.data.length);
                }
               
                FileUtil.storeFile(file, 0, payload.data.length, payload.data);
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, null);
            } else {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "指令内部校验错误！");
            }
        } catch (Exception e) {
            //e.printStackTrace();
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, e.toString());
        }

    }

   
    

}
