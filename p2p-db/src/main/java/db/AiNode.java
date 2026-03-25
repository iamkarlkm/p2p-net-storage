package db;


import cn.hutool.core.date.StopWatch;
import cn.hutool.crypto.digest.MD5;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;


/**
 *
 * @author karl
 */
public class AiNode {

	public AiNode parent;
	public String label;

	public BigInteger key;
	public int level;

	public byte[] data;

	//Conflict	nodes
	public Map<BigInteger, AiNode> branches = new HashMap<>();

	@Override
	public String toString() {
		return  "level=" + level + ",label=" + label + ", branches=" +branches.size()  + ", size=" + (key.bitLength()>>3) + " bytes";
	}

	public AiNode() {
	}

	public AiNode(String label, BigInteger key,int level, AiNode parent) {
		this.label = label;
		this.data = key.toByteArray();
		this.key = key;
		this.level = level;
		this.parent = parent;
	}

	public AiNode(String label, byte[] data) {
		this.label = label;
		this.data = data;
		this.key = new BigInteger(data);
	}

	public AiNode(int level,String label, byte[] data, AiNode parent) {
		this.level = level;
		this.label = label;
		this.data = data;
		this.key = new BigInteger(data);
		this.parent = parent;
	}


	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		AiNode aiNode = (AiNode) o;
		//level = ainode.level && data.length == ainode.data.length &&
		return key.equals(aiNode.key) &&label.equals(aiNode.label);
	}

	@Override
	public int hashCode() {

		return key.hashCode();
	}

	public AiNode addNode(AiNode node) {
		if(node.level != this.level ){
			throw new IllegalArgumentException("level not match");
		}
		if(node.data.length != this.data.length ){
			System.out.println(node.data.length+":"+this.data.length);
			throw new IllegalArgumentException("key size not match");
		}
		if(this.key.equals(node.key)) {
			
//			if(this.equals(node)) {
			//推理树主干
			//AiNode parent = this.parent;
			if(this.parent == null) {//已达顶层，不再添加
				this.branches.put(node.key, node);
				return node;
			}
			return this.parent.addNode(node.parent);
		}


		AiNode old = branches.get(node.key);
		if(old == null) {
			return branches.put(node.key, node);
		}

		//return old;
		return  old.parent.addNode(node.parent);
	}

	public AiNode predict(AiNode node, int stopLevel) {
		if(node.level != this.level ){
			throw new IllegalArgumentException("level not match");
		}
		if(node.data.length != this.data.length ){
			throw new IllegalArgumentException("data size not match");
		}
		if(this.level == stopLevel){
			return this;
		}
		if(this.key.equals(node.key)) {
			//推理树主干
			//AiNode parent = this.parent;
			if(this.parent == null) {//已达顶层
				//this.branches.put(node.key, node);
				return this;
			}
			return this.parent.predict(node.parent,stopLevel);
		}


		AiNode old = branches.get(node.key);
		if(old == null) {
			if(this.parent == null) {//已达顶层
				//this.branches.put(node.key, node);
				return this;
			}
			return null;
		}

		//return old;
		return  old.parent.predict(node.parent,stopLevel);
	}
	
	public static void main(String[] args) throws Exception {
			String path = "总结：如果有自定义my.cnf配置bin-log 目录及文件的6一定要在初始化的时候指定目录/usr/local/mysql/bin/mysqld --log-bin=[自定义的二进制目录]";
			System.out.println(path.hashCode());
	}

	public static void main2(String[] args) {
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

		System.out.println(bi2.equals(bi));

		//new BigInteger(bytes)

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
