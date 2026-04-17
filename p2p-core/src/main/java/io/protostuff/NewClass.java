/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package io.protostuff;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.nio.AbstractNioByteChannel;
import io.protostuff.LinkedBuffer;
import javax.net.p2p.utils.SerializationUtil;

/**
 *
 * @author karl
 */
public class NewClass {
    
    public static void main(String[] args) {
        AbstractNioByteChannel d;
        ByteBuf out = SerializationUtil.tryGetDirectBuffer(512);
        System.out.println(out.maxCapacity()+" : "+out.getClass());
//        LinkedBuffer node = new LinkedBuffer(512);
//        
//        int offset = 0, len;
//        final byte[] buf = new byte[3];
//        do
//        {
//            if ((len = node.offset - node.start) > 0)
//            {
//                System.arraycopy(node.buffer, node.start, buf, offset, len);
//                out.writeBytes(in, len);
//                offset += len;
//            }
//        } while ((node = node.next) != null);
        /**
     * Returns a single byte array containg all the contents written to the buffer(s).
     */
//    public final byte[] toByteArray()
//    {
//        
//
//        return buf;
//    }
    }
    
}
