package db;


import cn.hutool.core.date.StopWatch;
import cn.hutool.crypto.digest.MD5;
import org.opencv.core.Mat;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;


/**
 *
 * @author karl
 */
public class ImgNode {

	public ImgNode parent;
	public String label;

	public BigInteger key;
	public int level;
	public int size;

	public int index;

//	public int left;
//	public int right;

	public byte[] data;

	//Conflict	nodes
	public Map<BigInteger, ImgNode> branches = new HashMap<>();

	@Override
	public String toString() {
		return  "data length=" + data.length +",size=" + size + ",index=" + index+ ",label=" + label + ", branches=" +branches.size()  + ", key=" + (key.bitLength()>>3) + " bytes";
	}

	public ImgNode() {
	}

	public ImgNode(String label, Mat mat, int size, ImgNode parent) {
		this.label = label;
		byte[] data = new byte[mat.cols()* mat.rows()* mat.channels()];
		mat.get(0, 0, data);
		this.data = data;
		//System.out.println(size);
		byte[] keyBytes = sumarryArray(data);

		this.key = new BigInteger(keyBytes);
		this.size = size;
		this.parent = parent;
	}

	public static final byte[] sumarryArray(byte[] data) {

		//System.out.println(size);
		byte[] keyBytes = new byte[data.length/8];
		int index = 0;
		for(int i=0;i<keyBytes.length;i++){
			int val = 0;
			for(int j=0;j<8;j++){
				switch (j) {
					case 0 -> val += (data[index] & 0b10000000);
					case 1 -> val += (data[index] & 0b10000000) >> 1;
					case 2 -> val += (data[index] & 0b10000000) >> 2;
					case 3 -> val += (data[index] & 0b10000000) >> 3;
					case 4 -> val += (data[index] & 0b10000000) >> 4;
					case 5 -> val += (data[index] & 0b10000000) >> 5;
					case 6 -> val += (data[index] & 0b10000000) >> 6;
					case 7 -> val += (data[index] & 0b10000000) >> 7;
				}
				index++;
			}
			keyBytes[i] = (byte) val;
		}

		return keyBytes;
	}

	public ImgNode(String label, BigInteger key, int size, ImgNode parent) {
		this.label = label;
		this.data = key.toByteArray();
		this.key = key;
		this.size = size;
		this.parent = parent;
	}

	public ImgNode(String label, byte[] data) {
		this.label = label;
		this.data = data;
		this.key = new BigInteger(data);
	}

	public ImgNode(int size, String label, byte[] data, ImgNode parent) {
		this.size = size;
		this.label = label;
		this.data = data;
		this.key = new BigInteger(data);
		this.parent = parent;
	}


	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		ImgNode aiNode = (ImgNode) o;
		//size = ainode.size && data.length == ainode.data.length &&
		return key.equals(aiNode.key) &&label.equals(aiNode.label);
	}

	@Override
	public int hashCode() {

		return key.hashCode();
	}

	public ImgNode addNode(ImgNode node) {
		//System.out.println(this.size + ":"+node.size);
		if(node.size != this.size ) {
			if (this.parent != null) {
				if(node.size > this.size ) {
					return this.parent.addNode(node);
				}else{
					return this.addNode(node.parent);
				}

			} else throw new IllegalArgumentException(this.size + ":size not find -> "+node.size);

		}
//		if(node.data.length != this.data.length ){
//			System.out.println(node.data.length+":"+this.data.length);
//			throw new IllegalArgumentException("data size not match");
//		}
		if(this.key.equals(node.key)) {
			
//			if(this.equals(node)) {
			//推理树主干
			//AiNode parent = this.parent;
			if(this.parent == null||node.parent == null ) {//已达顶层，不再添加
				if(node.size != this.size ){
					throw new IllegalArgumentException("size not match");
				}
				this.branches.put(node.key, node);
				return node;
			}
			return this.parent.addNode(node.parent);
		}


		ImgNode old = branches.get(node.key);
		if(old == null) {
			return branches.put(node.key, node);
		}

		if(old.parent == null||node.parent == null ) {//已达顶层，不再添加
			if(node.size != old.size ){
				throw new IllegalArgumentException("size not match");
			}
			old.branches.put(node.key, node);
			return node;
		}

		//return old;
		return  old.parent.addNode(node.parent);
	}



	public ImgNode predict(ImgNode node, int stopsize,float like) {

		if(node.size != this.size ) {
			if (this.parent != null) {
				if(node.size > this.size ) {
					return this.parent.predict(node,stopsize,like);
				}else{
					return this.predict(node.parent,stopsize,like);
				}

			} else throw new IllegalArgumentException("size not find -> "+node.size);

		}
		if(stopsize== this.size ) {
			if(binaryCompare(this.data,node.data,like)) {
				//System.out.println(node.label+":find match -> "+o.label);
				return this;
			}
			for(ImgNode o:this.branches.values()){
				if(binaryCompare(o.data,node.data,like)) {
					//System.out.println(node.label+":find match -> "+o.label);
					return o;
				}
			}
//			Convolution.null_count++;
			return null;
		}
//		if(node.size != this.size ){
//			throw new IllegalArgumentException("size not match");
//		}
//		if(node.data.length != this.data.length ){
//			throw new IllegalArgumentException("key size not match");
//		}
//		if(this.size == stopsize){
//			return this;
//		}
		if(this.key.equals(node.key)) {
			//推理树主干
			//AiNode parent = this.parent;
			if(this.parent == null||node.parent==null) {//已达顶层，不再继续推理
				Convolution.node_key_count++;
				//this.branches.put(node.key, node);
				if(!this.label.equals(node.label)){
					return null;
					//System.out.println(node+"\n: not find match this-> \n"+this);
//					try {
//						save(this,dir);
//						save(node,dir);
//					} catch (IOException e) {
//						throw new RuntimeException(e);
//					}
//					System.out.println(node.key+"\n: not find match this-> \n"+this.key);
//					System.out.println(Img.toString(this.data,16,16));
//					System.out.println(Img.toBinaryString(sumarryArray(this.data),16,16));
//					System.out.println(Img.toString(node.data,16,16));
//					System.out.println(Img.toBinaryString(sumarryArray(node.data),16,16));
				}else{

					//System.out.println(node.label+":find match -> "+this.label);
				}
				return this;
			}
			return this.parent.predict(node.parent,stopsize,like);
		}


		ImgNode old = branches.get(node.key);
		if(old == null) {
			if(this.parent == null||node.parent==null) {//已达顶层，不再继续推理
				for(ImgNode o:this.branches.values()){
					if(binaryCompare(o.data,node.data,like)) {
						//System.out.println(node.label+":find match -> "+o.label);
						return o;
					}
				}
				return null;
			}
			for(ImgNode o:this.branches.values()){
				if(binaryCompare(o.data,node.data,like)) {
					//System.out.println(node.label+":find match -> "+o.label);
					return o;
				}
			}
			Convolution.null_count++;
			return null;
		}
		Convolution.old_not_null_count++;
		if(old.key.equals(node.key)) {

			//推理树主干
			//AiNode parent = this.parent;
			if(old.parent == null||node.parent==null) {//已达顶层，不再继续推理
				Convolution.old_key_count++;
				//this.branches.put(node.key, node);
				if(!old.label.equals(node.label)){
					//System.out.println(node.label+": not find match old-> "+old.label);
					//Img.toString(this.data,16,16);
					//Img.toString(node.data,16,16);
				}else{

					//System.out.println(node.label+":find match -> "+this.label);
				}
				return old;
			}
			return old.parent.predict(node.parent,stopsize,like);
		}

		if(old.parent == null||node.parent==null) {//已达顶层，不再继续推理
			if(node.size != old.size ){
				throw new IllegalArgumentException("size not match");
			}
			//old.branches.put(node.key, node);
			for(ImgNode o:this.branches.values()){
				if(binaryCompare(o.data,node.data,like)) {
					System.out.println(node.label+":find match -> "+o.label);
					return o;
				}
			}
			//System.out.println(node.label+": not find match -> "+old.label);
			return null;
		}

		//return old;
		return  old.parent.predict(node.parent,stopsize,like);
	}

	/**
	 * 比较两个字节数组是否相似
	 * @param src
	 * @param dst
	 * @param similarity 0-1之间的数，表示相似度
	 * @return
	 */
	public boolean binaryCompare(byte[] src,byte[] dst,float similarity) {
		if(src.length != dst.length) {
			return false;
		}
		//int count = 0;
		int error = src.length - (int)(src.length*similarity);
		for(int i=0;i<src.length;i++) {
			if(src[i] != dst[i]) {
				error--;
				if(error <0) return false;
			}
		}
		return true;
	}

	public static void main(String[] args) {
		StopWatch stopWatch = new StopWatch();
		System.out.println( "-执行开始...");
		stopWatch.start();
		CRC32 crc32 = new CRC32();
//        crc32.update("pSoLCT0gOz18lmzXP53U34驾驶人电子档案采集eusHINHqZj8PjB1jsjzX17".getBytes());
		crc32.update("1170508026449ENC(1170508026449ENC(x7MSC7H0clHT7DzcD5krzNVzYr83bNUl)".getBytes());
		stopWatch.stop();
		//统计执行时间（毫秒）
		System.out.println("执行时长：" + stopWatch.getLastTaskTimeNanos() + " 纳秒.");
		System.out.println(crc32.getValue());
		stopWatch.start();
//        crc32.update("pSoLCT0gOz18lmzXP53U34驾驶人电子档案采集eusHINHqZj8PjB1jsjzX17".getBytes());
		long a =calculateCRC64("1170508026449ENC(1170508026449ENC(x7MSC7H0clHT7DzcD5krzNVzYr83bNUl)1".getBytes());
		stopWatch.stop();
		//统计执行时间（毫秒）
		System.out.println("执行时长：" + stopWatch.getLastTaskTimeNanos() + " 纳秒.");
		System.out.println(a);
		stopWatch.start();
		MD5.create().digest("1170508026449ENC(1170508026449ENC(x7MSC7H0clHT7DzcD5krzNVzYr83bNUl)".getBytes());
		stopWatch.stop();
		//统计执行时间（毫秒）
		System.out.println("执行时长：" + stopWatch.getLastTaskTimeNanos() + " 纳秒.");
		Long l = 1170508026449L;
		BigInteger bi = new BigInteger("1170508026449");

		BigInteger bi2 = new BigInteger("1170508026449");
		stopWatch.start();

		l.hashCode();
		stopWatch.stop();
		//统计执行时间（毫秒）
		System.out.println("执行时长：" + stopWatch.getLastTaskTimeNanos() + " 纳秒.");
		stopWatch.start();
		bi.hashCode();
		stopWatch.stop();
		//统计执行时间（毫秒）
		System.out.println("执行时长：" + stopWatch.getLastTaskTimeNanos() + " 纳秒.");

		System.out.println(9849/10000.0);

		//new BigInteger(bytes)

	}

	public static File dir = new File("E:\\ai\\MNIST");
	public static void save(ImgNode node, File dir) throws IOException {


			File file = new File(dir, node.label+"_"+node.index+".bin");
//			if(!file.exists()) {
//				file.mkdirs();
//			}
		Files.write(file.toPath(), node.data);


	}

	static int WIDTH = (8 * 4);// change this to  8*16 for 128,  8*8 for 64,8*4 for int, and 8 * 2 for 16 bits
	static int TOPBIT = (1 << (WIDTH - 1));
	static int POLYNOMIAL = 0xD8; /* 11011 followed by 0's */
	static long CRCFunc(final byte[] message)
	{
		//final byte  = msg.getBytes();
		int nBytes = message.length;
		if(nBytes<1) return 0;
		long rem = 0;
		int b;
		for(b=0;b<nBytes;++b)
		{
			rem ^= ((long) message[b] << (WIDTH - 8));
			byte bit;
			for(bit=8;bit>0;--bit)
			{
				if ((rem & TOPBIT)>0)
				{
					rem = (rem<< 1) ^ POLYNOMIAL;
				}
				else
				{
					rem = (rem << 1);
				}
			}
		}
		return rem;
	}

	// CRC表，使用多项式0x00000000000000C93C
	private static final long[] CRC_TABLE = new long[256];

	// 静态初始化器，填充CRC表
	static {
		for (int i = 0; i < 256; i++) {
			long c = (long) i << 64;
			for (int j = 0; j < 8; j++) {
				if ((c & 1L << 63) != 0) {
					c = (c << 1) ^ 0x00000000000000C93C;
				} else {
					c <<= 1;
				}
			}
			CRC_TABLE[i] = c;
		}
	}

	// 计算数据的CRC-128值
	public static long[] calculateCRC128(byte[] data) {
		long crc1 = 0xFFFFFFFFFFFFFFFFL;
		long crc2 = crc1;

		for (byte b : data) {
			long index = (crc1 ^ (b & 0xff)) & 0xff;
			crc1 = CRC_TABLE[(int)index] ^ (crc1 >>> 8);
			crc2 = CRC_TABLE[(int)(crc2 ^ index) & 0xff] ^ (crc2 >>> 8);
		}

		// 返回CRC值，通常需要反转
		return new long[]{ ~crc1, ~crc2 };
	}

	public static long calculateCRC64(byte[] data) {
		long crc1 = 0xFFFFFFFFFFFFFFFFL;
		//long crc2 = crc1;

		for (byte b : data) {
			long index = (crc1 ^ (b & 0xff)) & 0xff;
			crc1 = CRC_TABLE[(int)index] ^ (crc1 >>> 8);
			//crc2 = CRC_TABLE[(int)(crc2 ^ index) & 0xff] ^ (crc2 >>> 8);
		}

		// 返回CRC值，通常需要反转
		return crc1;
	}

	
}
