package javax.net.p2p.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.Attribute;
import java.util.List;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.ChannelUtils;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SerializationUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * **************************************************
 *
 * @description @author karl
 * @version 1.0, 2018-9-8
 * @see HISTORY Date Desc Author Operation 2018-9-8 创建文件 karl create
 * @since 2017 Phyrose Science & Technology (Kunming) Co., Ltd.
 * ************************************************
 */
@Slf4j
public class P2PWrapperDecoder extends ByteToMessageDecoder {

    private int frameLengthInt = -1;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        	System.out.println("decoding..."+in.readableBytes());
        if (frameLengthInt == -1) { // new frame
            if (in.readableBytes() < 8) {//头长度
                //new PooledByteBufAllocator(false);
                return;
            }
            //int beginIndex = in.readerIndex();
            frameLengthInt = in.readInt();//保存frame字节数
            int magic = in.readInt();
            Attribute<Integer> attrMagic = ctx.channel().attr(ChannelUtils.MAGIC);
            if (magic == attrMagic.get()) {
                if (in.readableBytes() < frameLengthInt) {
                    //in.readerIndex(beginIndex);
                    if (in.capacity() < frameLengthInt) {
                        in.capacity(frameLengthInt + 128);
                    }
                    //in.capacity(length + 128);
                    // System.out.println(length+":"+in.writerIndex()+" decoding in.readableBytes():" + in.readableBytes());
                    log.debug("WrapperDecoder -> expected " + frameLengthInt + ",actual:" + in.readableBytes());
                    return;
                }

            } else {
                in.skipBytes(in.readableBytes());
                frameLengthInt = -1; // start processing the next frame
                log.error("WrapperDecoder error header -> expected magic number " + attrMagic.get() + ",actual:" + magic);
                
                P2PWrapper response = P2PWrapper.build(0, P2PCommand.INVALID_PROTOCOL, in.readableBytes());
                ctx.writeAndFlush(response);
                return;
            }
        }
        //等待frame传输完成
        if (in.readableBytes() < frameLengthInt) {
            //in.readerIndex(beginIndex);
            if (in.capacity() < frameLengthInt) {
                in.capacity(frameLengthInt);
            }
            //in.capacity(length + 128);
            // System.out.println(length+":"+in.writerIndex()+" decoding in.readableBytes():" + in.readableBytes());
            log.debug("WrapperDecoder -> expected " + frameLengthInt + ",actual:" + in.readableBytes());
            return;
        }

        try {//先获取可读字节数
            // extract frame
            //int readerIndex = in.readerIndex();
//            final byte[] array = new byte[frameLengthInt];
//            in.readBytes(array);
            //System.out.println("decoding total length:" + length);
//            Object result = SerializationUtil.deserialize(P2PWrapper.class, array);
//            System.out.println("Object result = "+result);
            //in.readerIndex(readerIndex + frameLengthInt);
             //MAGIC连接建立及登录后可被动态改变
            Attribute<Integer> attributeMagic = ctx.channel().attr(ChannelUtils.MAGIC);
            P2PWrapper result = SerializationUtil.deserialize(P2PWrapper.class, in,attributeMagic.get());
            out.add(result);
        } catch (Exception e) {
            //ctx.channel().pipeline().remove(this);
        } finally {
            frameLengthInt = -1; // start processing the next frame
        }
    }

}
