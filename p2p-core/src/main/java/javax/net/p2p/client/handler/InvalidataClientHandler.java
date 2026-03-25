/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package javax.net.p2p.client.handler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;

/**
 *
 * @author Administrator
 */
public class InvalidataClientHandler implements P2PCommandHandler {

	@Override
	public P2PCommand getCommand() {
		return P2PCommand.INVALID_DATA;
	}


    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand().getValue() == P2PCommand.INVALID_DATA.getValue()) {
               return null;
            } else {
                return P2PWrapper.build(request.getSeq(),P2PCommand.STD_ERROR, "指令内部校验错误！");
            }
        } catch (Exception e) {
            return P2PWrapper.build(request.getSeq(),P2PCommand.STD_ERROR, e.getMessage());
        }

    }

    public byte[] loadFile(File file) throws FileNotFoundException, IOException {
        if (file.exists()) {
            //handle file read
//            byte[] bytearray = new byte[(int) file.length()];
//            try (FileInputStream fis = new FileInputStream(file);
//                    BufferedInputStream bis = new BufferedInputStream(fis);
//                    DataInputStream dis = new DataInputStream(bis);) {
//                dis.readFully(bytearray, 0, bytearray.length);
//                return bytearray;
//            } catch (Exception e) {
//                e.printStackTrace();
//                throw new RuntimeException(e.getMessage());
//            }
                //zero copy :
            try (
                    RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");
                    FileChannel fileChannel = randomAccessFile.getChannel();
                    //                ByteBuffer buffer = ByteBuffer.allocate((int)file.length());
                    //                byte[] bytearray;
                    //                if(buffer.hasArray()){
                    //                    bytearray = buffer.array();
                    //                }else{
                    //                    bytearray = new byte[(int) file.length()];
                    //                }
                    ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length());
                    WritableByteChannel wc = Channels.newChannel(out);) {
                fileChannel.transferTo(0, file.length(), wc);
                out.flush();
                return out.toByteArray();
            }
        } else {
            throw new RuntimeException("文件不存在->" + file.getAbsolutePath());
        }

    }
}
