
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;



/**
 *
 * @author karl
 */
public class CompositeByteBufTest {
	
	/**
	 * 数据文件元数据结构
	 */
	private int[] db32info = new int[16];
	public static void main(String[] args) throws Exception {
		PooledByteBufAllocator pool= new PooledByteBufAllocator();
		CompositeByteBuf compositeByteBuf = pool.compositeBuffer(4096);
		//cbuf2.addComponent(true, 0, cbuf2);
		
		String a = "ccc";
        String b = "dddd";
        ByteBuf buf1 = Unpooled.wrappedBuffer(a.getBytes(CharsetUtil.UTF_8));
        ByteBuf buf2 = Unpooled.wrappedBuffer(b.getBytes(CharsetUtil.UTF_8));
       compositeByteBuf.addComponents(true,buf1,buf2);
     System.out.println(compositeByteBuf.readCharSequence(compositeByteBuf.readableBytes(), CharsetUtil.UTF_8));
	 compositeByteBuf.addComponent(true, 1, Unpooled.wrappedBuffer("222".getBytes(CharsetUtil.UTF_8)));
	 compositeByteBuf.readerIndex(0);
	 System.out.println(compositeByteBuf.readCharSequence(compositeByteBuf.readableBytes(), CharsetUtil.UTF_8));
       compositeByteBuf.readerIndex(0); 
	 int size = compositeByteBuf.readableBytes();
        byte[] bytes = new byte[size];
        compositeByteBuf.readBytes(bytes);
        String value = new String(bytes,CharsetUtil.UTF_8);
		
		//compositeByteBuf.readCharSequence(size, charset);
        System.out.println("composite buff result : " + value);
		 System.out.println("****************");
		 /**
		  * 使用位运算：

例如，如果你有一个int类型的有符号整数，并且你知道它的值不会超过Integer.MAX_VALUE（即2^31-1），你可以将其转换为无符号整数，方法是将其与0xFFFFFFFF进行按位与操作：
int unsignedInt = signedInt & 0xFFFFFFFF;
对于byte和short类型，可以使用类似的按位与操作：
int unsignedByte = signedByte & 0xFF;  // 对于byte类型
int unsignedShort = signedShort & 0xFFFF;  // 对于short类型
		  */
		 compositeByteBuf.writeByte(254);
		compositeByteBuf.writeInt(-1);
		System.out.println(Integer.toBinaryString(Integer.MAX_VALUE-2)+"/"+Integer.toBinaryString(Integer.MAX_VALUE));
		System.out.println(Integer.toHexString(Integer.MIN_VALUE)+"/"+Integer.toHexString(Integer.MAX_VALUE));
		System.out.println(Integer.MIN_VALUE+"/"+Integer.MAX_VALUE);
		System.out.println(compositeByteBuf.readByte()& 0xFF);
		System.out.println(Integer.toUnsignedLong(compositeByteBuf.readInt()));
		     
        byte[] bytesSrc = a.getBytes(CharsetUtil.UTF_8);
        ByteBuf byteBuf = Unpooled.wrappedBuffer(bytesSrc);
        //byteBuf.writeBytes(bytesSrc);
		//byteBuf = Unpooled.compositeBuffer(byteBuf);
		//compositeByteBuf.addComponent(buf1);//不要使用这个方法，它不会增加writeIndex
        //compositeByteBuf.addComponent(true,buf1);//一定要使用这个方法
		 byteBuf = Unpooled.compositeBuffer(4096).addComponent(byteBuf);
				System.out.println(byteBuf.readerIndex());
				System.out.println(byteBuf.writerIndex());
				System.out.println(byteBuf.readableBytes());
				System.out.println(byteBuf.capacity());
				System.out.println(byteBuf.readCharSequence(3, CharsetUtil.UTF_8));
				System.out.println(byteBuf.readerIndex());
				byteBuf.capacity(4096);
				size = byteBuf.writeCharSequence("我在昆明", CharsetUtil.UTF_8);
				System.out.println("size:"+size);
				System.out.println(byteBuf.readCharSequence(size, CharsetUtil.UTF_8));

	}
}
