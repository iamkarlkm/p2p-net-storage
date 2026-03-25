package db;


//import cn.hutool.core.date.StopWatch;
//import com.google.common.io.Files;
//import com.mint.dzda.kit.SerializationUtil;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.TimeUnit;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

/**
 *
 * @author karl
 */
public class AiLoadData implements AutoCloseable {
	private static final Logger log = LoggerFactory.getLogger(AiLoadData.class);

	public static byte[] loadFile(File file,long start,int length) throws FileNotFoundException, IOException {
		if (file.exists()) {
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
					ByteArrayOutputStream out = new ByteArrayOutputStream(length);
					WritableByteChannel wc = Channels.newChannel(out);) {
				fileChannel.transferTo(start, length, wc);
				out.flush();
				return out.toByteArray();
			}
		} else {
			throw new RuntimeException("文件不存在->" + file.getAbsolutePath());
		}

	}
	public final static int MNIST_IMAGE_SIZE = 28*28;
	
	public final static int MNIST_IMAGE_WIDTH = 28;
	public final static int MNIST_IMAGE_HEIGHT = 28;
	
	public final static long getImageStartWithMNIST(int index ){
		return 16+MNIST_IMAGE_SIZE*index;
	}
	
	public final static long getLableStartWithMNIST(int index ){
		return 8+index;
	}

	private RandomAccessFile rafTrainLableWithMNIST;

	private RandomAccessFile rafTrainImageWithMNIST;

	private RandomAccessFile rafTestLableWithMNIST;

	private RandomAccessFile rafTestImageWithMNIST;

	private FileChannel fileChannelTrainImageWithMNIST;
	private FileChannel fileChannelTestImageWithMNIST;

	public AiLoadData(File dirWithMNIST) throws FileNotFoundException, IOException {
		this.rafTrainImageWithMNIST = new RandomAccessFile(new File(dirWithMNIST, "train-images.idx3-ubyte"), "r");
		this.rafTrainLableWithMNIST = new RandomAccessFile(new File(dirWithMNIST, "train-labels.idx1-ubyte"), "r");
		this.rafTestImageWithMNIST = new RandomAccessFile(new File(dirWithMNIST, "t10k-images.idx3-ubyte"), "r");
		this.rafTestLableWithMNIST = new RandomAccessFile(new File(dirWithMNIST, "t10k-labels.idx1-ubyte"), "r");
		this.fileChannelTrainImageWithMNIST = rafTrainImageWithMNIST.getChannel();
		this.fileChannelTestImageWithMNIST = rafTestImageWithMNIST.getChannel();

	}
	public String loadTrainLable(int index) throws FileNotFoundException, IOException {
		rafTrainLableWithMNIST.seek(getLableStartWithMNIST(index));
		return rafTrainLableWithMNIST.readByte()+"";

	}

	public byte[] loadTrainImage(int index) throws FileNotFoundException, IOException {
			//zero copy :
			try (
					//                ByteBuffer buffer = ByteBuffer.allocate((int)file.length());
					//                byte[] bytearray;
					//                if(buffer.hasArray()){
					//                    bytearray = buffer.array();
					//                }else{
					//                    bytearray = new byte[(int) file.length()];
					//                }
					ByteArrayOutputStream out = new ByteArrayOutputStream(MNIST_IMAGE_SIZE);
					WritableByteChannel wc = Channels.newChannel(out);) {
				fileChannelTrainImageWithMNIST.transferTo(getImageStartWithMNIST(index), MNIST_IMAGE_SIZE, wc);
				out.flush();
				return out.toByteArray();
			}

	}

	public String loadTestLable(int index) throws FileNotFoundException, IOException {
		rafTestLableWithMNIST.seek(getLableStartWithMNIST(index));
		return rafTestLableWithMNIST.readByte()+"";

	}

	public byte[] loadTestImage(int index) throws FileNotFoundException, IOException {
		//zero copy :
		try (
			 //                ByteBuffer buffer = ByteBuffer.allocate((int)file.length());
			 //                byte[] bytearray;
			 //                if(buffer.hasArray()){
			 //                    bytearray = buffer.array();
			 //                }else{
			 //                    bytearray = new byte[(int) file.length()];
			 //                }
			 ByteArrayOutputStream out = new ByteArrayOutputStream(MNIST_IMAGE_SIZE);
			 WritableByteChannel wc = Channels.newChannel(out);) {
			fileChannelTestImageWithMNIST.transferTo(getImageStartWithMNIST(index), MNIST_IMAGE_SIZE, wc);
			out.flush();
			return out.toByteArray();
		}

	}
	public static String loadLableWithMNIST(File file,int index) throws FileNotFoundException, IOException {
		if (file.exists()) {
			//zero copy :
			try (
					RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r")){
					randomAccessFile.seek(getLableStartWithMNIST(index));
					return randomAccessFile.readByte()+"";
			}
		} else {
			throw new RuntimeException("文件不存在->" + file.getAbsolutePath());
		}

	}
	
	public static byte[] loadImageWithMNIST(File file,int index) throws FileNotFoundException, IOException {
		if (file.exists()) {
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
					ByteArrayOutputStream out = new ByteArrayOutputStream(MNIST_IMAGE_SIZE);
					WritableByteChannel wc = Channels.newChannel(out);) {
				fileChannel.transferTo(getImageStartWithMNIST(index), MNIST_IMAGE_SIZE, wc);
				out.flush();
				return out.toByteArray();
			}
		} else {
			throw new RuntimeException("文件不存在->" + file.getAbsolutePath());
		}

	}
	public static String readLableWithMNIST(RandomAccessFile randomAccessFile,int index) throws FileNotFoundException, IOException {
					randomAccessFile.seek(getLableStartWithMNIST(index));
					return randomAccessFile.readByte()+"";
	}
	
	public static byte[] loadImageWithMNIST(RandomAccessFile randomAccessFile,int index) throws FileNotFoundException, IOException {
			//zero copy :
			try (
					FileChannel fileChannel = randomAccessFile.getChannel();
					ByteArrayOutputStream out = new ByteArrayOutputStream(MNIST_IMAGE_SIZE);
					WritableByteChannel wc = Channels.newChannel(out);) {
				fileChannel.transferTo(getImageStartWithMNIST(index), MNIST_IMAGE_SIZE, wc);
				out.flush();
				return out.toByteArray();
			}

	}
	
	public static void main(String[] args) throws Exception {
//		try (
//					RandomAccessFile imgFile = new RandomAccessFile(new File("E:\\ai\\MNIST\\train-images.idx3-ubyte"), "r");
//				RandomAccessFile lableFile = new RandomAccessFile(new File("E:\\ai\\MNIST\\train-labels.idx1-ubyte"), "r");){
//			AiTreeNodeMap  modelsMap = new AiTreeNodeMap(16);
//			for(int i=1;i<=60000;i++){
//				AiTreeNode node = new AiTreeNode(readLableWithMNIST(lableFile,i),
//						Convolution.convolve2dTo(loadImageWithMNIST(imgFile,i), MNIST_IMAGE_WIDTH, MNIST_IMAGE_HEIGHT));
//				System.out.println(i);
//				modelsMap.putNode(node);
//				log.info(node.toString());
//			}
//			System.out.println(modelsMap);
//			Files.write(SerializationUtil.serialize(modelsMap), new File("E:\\ai\\MNIST\\train-modles-map.bin"));
//		}
		
			File imgFile = new File("E:\\ai\\MNIST\\train-images.idx3-ubyte");
			File lableFile = new File("E:\\ai\\MNIST\\train-labels.idx1-ubyte");
			AiTreeNodeMap  modelsMap = new AiTreeNodeMap(16);
			for(int i=0;i<60000;i++){
				AiTreeNode node = new AiTreeNode(loadLableWithMNIST(lableFile,i),
						Convolution2.convolve2dTo(loadImageWithMNIST(imgFile,i), MNIST_IMAGE_WIDTH, MNIST_IMAGE_HEIGHT));
				System.out.println(i);
				modelsMap.putNode(node);
				log.info(node.toString());
			}
			System.out.println(modelsMap);
			//Files.write(SerializationUtil.serialize(modelsMap), new File("E:\\ai\\MNIST\\train-modles-map.bin"));
		//byte[] d =loadFile(new File("E:\\ai\\MNIST\\train-images.idx3-ubyte"), 16, 28*28);
		//System.out.println(Hex.encodeHexString(d));
		//Convolution.convolve2dTo(loadImageWithMNIST(new File("E:\\ai\\MNIST\\train-images.idx3-ubyte"),1), MNIST_IMAGE_WIDTH, MNIST_IMAGE_HEIGHT);
		//OpenCVImage.show(readLableWithMNIST(new File("E:\\ai\\MNIST\\train-labels.idx1-ubyte"),1),
		//		loadImageWithMNIST(new File("E:\\ai\\MNIST\\train-images.idx3-ubyte"),1));
		//System.out.println(readLableWithMNIST(new File("E:\\ai\\MNIST\\train-labels.idx1-ubyte"),2));
	}


	@Override
	public void close() throws Exception {
		if (rafTrainImageWithMNIST != null) {
			rafTrainImageWithMNIST.close();
		}
		if (rafTrainLableWithMNIST != null) {
			rafTrainLableWithMNIST.close();
		}
		if (rafTestImageWithMNIST != null) {
			rafTestImageWithMNIST.close();
		}
		if (rafTestLableWithMNIST != null) {
			rafTestLableWithMNIST.close();
		}
	}
}
