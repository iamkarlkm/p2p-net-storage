package javax.net.p2p.codec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.util.Attribute;
import javax.net.p2p.channel.ChannelUtils;
import javax.net.p2p.config.P2PConfig;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SerializationUtil;

/**
 * **************************************************
 * @description 
 * @author   karl
 * @version  1.0, 2018-9-8
 * @see HISTORY
 *      Date        Desc          Author      Operation
 *  	2018-9-8   创建文件       karl        create
 * @since 2017 Phyrose Science & Technology (Kunming) Co., Ltd.
 **************************************************/
@ChannelHandler.Sharable
public class P2PWrapperEncoder extends MessageToByteEncoder<P2PWrapper> {
	
    /**
	 * 应用数据包验证以及自定义动态协议标记
	 */
    public static int MAGIC = 0xf0f00f0f;
			
    @Override
    protected void encode(ChannelHandlerContext ctx, P2PWrapper msg, ByteBuf out) throws Exception {
        //MAGIC连接建立及登录后可被动态改变
         Attribute<Integer> attributeMagic = ctx.channel().attr(ChannelUtils.MAGIC);
        SerializationUtil.serialize(out, attributeMagic.get(), out);
        //byte[] bytes = SerializationUtil.serialize(msg);
        //写入frame头
        //out.writeInt(bytes.length);
		//out.writeInt(MAGIC);
//        System.out.println(msg+" -> encoding total lenth:" + bytes.length);
//        int cap = bytes.length+128;
//        if(out.capacity()<cap){
//            out.capacity(cap);
//        }
//        if(bytes.length>P2PConfig.TRANSPORT_LIMIT_SIZE){
//            //分包发送,以防止中间网络路由问题导致传输问题(超时),实测数据域映射端口超过64k tcp包经常超时
//            int rest = (int) bytes.length % P2PConfig.TRANSPORT_LIMIT_SIZE;
//            int count = (int) bytes.length / P2PConfig.TRANSPORT_LIMIT_SIZE ;
//            //System.out.println(bytes.length+" -> "+count+" rest "+rest);
//            for(int i=0;i<count;i++){
//               out.writeBytes(bytes,i*P2PConfig.TRANSPORT_LIMIT_SIZE,P2PConfig.TRANSPORT_LIMIT_SIZE);
//               ctx.flush(); 
//            }
//            if(rest>0){
//                out.writeBytes(bytes,count*P2PConfig.TRANSPORT_LIMIT_SIZE,rest);
//                ctx.flush();
//            }	
//        }else{
//            out.writeBytes(bytes);
//            ctx.flush();
//        }

    }
}
