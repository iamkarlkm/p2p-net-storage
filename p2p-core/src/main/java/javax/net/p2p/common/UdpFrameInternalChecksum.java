package javax.net.p2p.common;

import java.util.Arrays;

/**
 * 
 * udp数据帧分段校验,目前netty底层是2048字节限制,所以每个段2048字节
 *
 * @author iamkarl@163.com
 */
public class UdpFrameInternalChecksum {
    public int seq;//消息序列号
    public int size;//数据帧尺寸
    public int count;//段计数
    public int[] checksums;//段32位 hash/checksum

    public UdpFrameInternalChecksum() {}

    public UdpFrameInternalChecksum(int seq,int size, int count, int[] checksums) {
        if(count!=checksums.length){
            throw new IllegalArgumentException();
        }
        this.seq = seq;
        this.size = size;
        this.count = count;
        this.checksums = checksums;
    }

    @Override
    public String toString() {
        return "UdpFrameInternalChecksum{" + "seq=" + seq + ", count=" + count + ", checksums=" + Arrays.toString(checksums) + '}';
    }

    
   

}
