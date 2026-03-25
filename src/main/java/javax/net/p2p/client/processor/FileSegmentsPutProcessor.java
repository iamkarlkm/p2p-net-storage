
package javax.net.p2p.client.processor;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.p2p.interfaces.P2PFileService;
import javax.net.p2p.model.FileSegmentsDataModel;
import javax.net.p2p.utils.FileUtil;
import javax.net.p2p.utils.P2PUtils;
import javax.net.p2p.utils.SecurityUtils;

public class FileSegmentsPutProcessor implements Runnable {

	
	
	private final P2PFileService node;
	private final FileSegmentsDataModel model;
    private final CountDownLatch countDownLatch;
	private  final CountDownLatch errorCountDown;
	private  final AtomicBoolean errorTag;
	
	private File data;
    private final RandomAccessFile infoFile;

	public FileSegmentsPutProcessor(P2PFileService node, FileSegmentsDataModel model, RandomAccessFile infoFile, CountDownLatch countDownLatch, CountDownLatch errorCountDown, AtomicBoolean errorTag) {
		this.node = node;
		this.model = model;
		this.countDownLatch = countDownLatch;
		this.errorCountDown = errorCountDown;
		this.errorTag = errorTag;
		this.infoFile = infoFile;
		//model.blockData = Arrays.copyOfRange(data, (int)model.start, (int)(model.start+model.blockSize));
		//model.blockMd5 = SecurityUtils.toMD5(model.blockData);
	}
	
	public FileSegmentsPutProcessor(P2PFileService node, FileSegmentsDataModel model,File data, RandomAccessFile infoFile, CountDownLatch countDownLatch, CountDownLatch errorCountDown, AtomicBoolean errorTag) {
		this.node = node;
		this.model = model;
		this.countDownLatch = countDownLatch;
		this.errorCountDown = errorCountDown;
		this.errorTag = errorTag;
		this.data = data;
		this.infoFile = infoFile;
		//model.blockData = Arrays.copyOfRange(data, (int)model.start, (int)(model.start+model.blockSize));
		//model.blockMd5 = SecurityUtils.toMD5(model.blockData);
	}

    @Override
    public void run() {
		try {
			if(data!=null && model.blockData ==null){
				model.blockData = FileUtil.loadFile(data, model.start, model.blockSize);
				//System.out.println(Hex.encodeHexString(model.blockData));
				model.blockMd5 = SecurityUtils.toMD5(model.blockData);
			}
			//if(model.blockIndex==0)
			node.putFileSegment(model);
			FileUtil.concurentAppend(infoFile, (model.blockIndex+"\n").getBytes());
		} catch (Exception ex) {
			Logger.getLogger(FileSegmentsPutProcessor.class.getName()).log(Level.SEVERE, null, ex);
			errorTag.set(true);
		}finally{
			 // 子线程中，业务处理完成后，利用countDown的特性，计数器减一操作
			countDownLatch.countDown();
			//System.out.println("::"+model.blockIndex);
		}
      
        // 子阻塞，直到其他子线程完成操作
        try {
            errorCountDown.await();
        } catch (Exception e) {
            errorTag.set(true);
        }
        //log.info("handleTestTwo-子线程执行完成");
        if (errorTag.get()) {
            // 抛出异常，回滚数据
            throw new RuntimeException("子线程业务执行异常 ->"+model);
        }
    }
	

}

