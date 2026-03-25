package javax.net.p2p.utils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.PooledByteBufAllocator;
import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.ProtostuffOutputWithByteBuf;
import io.protostuff.Schema;
import io.protostuff.runtime.RuntimeSchema;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.p2p.exception.DataLengthLimitedException;
import javax.net.p2p.model.SerializeWrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * **************************************************
 * @description Protostuff 序列化/反序列化工具类
 * @author iamkarl@163.com
 * @version 2.0, 2018-08-07
 * @see HISTORY Date Desc Author Operation 2017-4-7 创建文件 karl create Date Desc
 * Author Operation 2018-08-07 创建文件 karl Protostuff 不支持序列化/反序列化数组、集合等对象,特殊处理
 * @since 2017 Phyrose Science & Technology (Kunming) Co., Ltd.
 *************************************************
 */
public class SerializationUtil {

    private static final Log log = LogFactory.getLog(SerializationUtil.class);

    public static final AtomicInteger IntegerSequence = new AtomicInteger();
    /**
     * 线程局部变量
     */
    private static final ThreadLocal<LinkedBuffer> BUFFERS = new ThreadLocal();

    /**
     * 序列化/反序列化包装类 Schema 对象
     */
    private static final Schema<SerializeWrapper> WRAPPER_SCHEMA = RuntimeSchema.getSchema(SerializeWrapper.class);

//    static{
//        if(PlatformDependent.directBufferPreferred()){
//            System.out.println("netty directBufferPreferred -> true;");
//            System.out.println("io.netty.buffer.ByteBufUtil.threadLocalDirectBuffer() -> "
//                +io.netty.buffer.ByteBufUtil.threadLocalDirectBuffer());
//        }else{
//            //详细分析一下ThreadLocalDirectBuffer()：
////【但是默认情况下我们是不使用ThreadLocalDirectBuffer的，
//System.out.println("netty directBufferPreferred -> false;\n"
//    + "我们需要显示指定jvm参数：io.netty.threadLocalDirectBuffer=true，这样才会创建ThreadLocal-Stack对象池的直接内存】");
////System.out.println("io.netty.threadLocalDirectBuffer -> "+SystemPropertyUtil.getBoolean("io.netty.threadLocalDirectBuffer", false));
//        }
//    }
    /**
     * 序列化对象
     *
     * @param obj 需要序列化的对象
     * @return 序列化后的二进制数组
     */
    @SuppressWarnings("unchecked")
    public static byte[] serialize(Object obj) {
        Class<?> clazz = (Class<?>) obj.getClass();
        LinkedBuffer buffer = BUFFERS.get();
        if (buffer == null) {//存储buffer到线程局部变量中，避免每次序列化操作都分配内存提高序列化性能
            buffer = LinkedBuffer.allocate(512);
            BUFFERS.set(buffer);
        }
        try {
            Object serializeObject = obj;
            Schema schema = WRAPPER_SCHEMA;
            if (clazz.isArray() || Collection.class.isAssignableFrom(clazz)
                || Map.class.isAssignableFrom(clazz) || Set.class.isAssignableFrom(clazz)) {//Protostuff 不支持序列化/反序列化数组、集合等对象,特殊处理
                serializeObject = SerializeWrapper.builder(obj);
            } else {
                schema = RuntimeSchema.getSchema(clazz);
            }
            return ProtostuffIOUtil.toByteArray(serializeObject, schema, buffer);
        } finally {
            buffer.clear();
        }
    }
    
    
    /**
     * 序列化对象
     *
     * @param obj 需要序列化的对象
     * @param magic 协议标志
     * @param byteBuf
     */
    @SuppressWarnings("unchecked")
    public static void serialize(Object obj,int magic,ByteBuf byteBuf) {
        Class<?> clazz = (Class<?>) obj.getClass();
        
            Object serializeObject = obj;
            Schema schema = WRAPPER_SCHEMA;
            if (clazz.isArray() || Collection.class.isAssignableFrom(clazz)
                || Map.class.isAssignableFrom(clazz) || Set.class.isAssignableFrom(clazz)) {//Protostuff 不支持序列化/反序列化数组、集合等对象,特殊处理
                serializeObject = SerializeWrapper.builder(obj);
            } else {
                schema = RuntimeSchema.getSchema(clazz);
            }
            serialize(serializeObject, schema, magic, byteBuf);
    }
    
     /**
     * Serializes the {@code message} into a byte array using the given schema.
     * 
     * @return the byte array containing the data.
     */
    private static <T> void serialize(T message, Schema<T> schema,int magic,ByteBuf byteBuf)
    {
        
        int frameLenthOffset = byteBuf.writerIndex();
        byteBuf.writeInt(0);
        byteBuf.writeInt(magic);
        final ProtostuffOutputWithByteBuf output = new ProtostuffOutputWithByteBuf(byteBuf);
        try
        {
            schema.writeTo(output, message);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Serializing to a byte array threw an IOException " +
                    "(should never happen).", e);
        }
        byteBuf.markWriterIndex();
        byteBuf.writeByte(0);
        byteBuf.resetWriterIndex();
        byteBuf.setInt(frameLenthOffset,output.getSize());
    }
    
    /**
     * Serializes the {@code message} into a byte array using the given schema.
     * 
     * @return the byte array containing the data.
     */
    private static <T> void serialize(T message, Schema<T> schema,ByteBuf byteBuf)
    {
        
        final ProtostuffOutputWithByteBuf output = new ProtostuffOutputWithByteBuf(byteBuf);
        try
        {
            schema.writeTo(output, message);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Serializing to a byte array threw an IOException " +
                    "(should never happen).", e);
        }
    }

    /**
     * 序列化对象 非netty流水线(自动管理ByteBuf)环境使用完buffer必须释放 * {@snippet lang=java :
     * try{
     * ....buffer ops...
     * }finally {
     * if (buffer != null) {
     * buffer.clear();
     * ReferenceCountUtil.safeRelease(buffer);
     * }
     * }
     * }
     *
     * @param obj 需要序列化的对象
     * @return 序列化后的二进制数组
     */
    @SuppressWarnings("unchecked")
    public static ByteBuf serializeToByteBuf(Object obj) {
        Class<?> clazz = (Class<?>) obj.getClass();
//        LinkedBuffer buffer = BUFFERS.get();
//        if (buffer == null) {//存储buffer到线程局部变量中，避免每次序列化操作都分配内存提高序列化性能
//            buffer = LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE);
//            BUFFERS.set(buffer);
//        }
            Object serializeObject = obj;
            Schema schema = WRAPPER_SCHEMA;
            if (clazz.isArray() || Collection.class.isAssignableFrom(clazz)
                || Map.class.isAssignableFrom(clazz) || Set.class.isAssignableFrom(clazz)) {//Protostuff 不支持序列化/反序列化数组、集合等对象,特殊处理
                serializeObject = SerializeWrapper.builder(obj);
            } else {
                schema = RuntimeSchema.getSchema(clazz);
            }
//            byte[] bytes = ProtostuffIOUtil.toByteArray(serializeObject, schema, buffer);
            ByteBuf out = tryGetDirectBuffer(256);
//            out.writeBytes(bytes);
            serialize(serializeObject, schema, out);
            return out;
    }
    
     /**
     * 序列化对象(写入buffer一个数据长度,以便对端正确反序列化) 非netty流水线(自动管理ByteBuf)环境使用完buffer必须释放 
     * {@snippet lang=java :
     * try{
     * ....buffer ops...
     * }finally {
     * if (buffer != null) {
     * buffer.clear();
     * ReferenceCountUtil.safeRelease(buffer);
     * }
     * }
     * }
     *
     * @param obj 需要序列化的对象
     * @param magic 协议标识
     * @return 序列化后的二进制数组
     */
    @SuppressWarnings("unchecked")
    public static ByteBuf serializeToByteBuf(Object obj,int magic) {
        Class<?> clazz = (Class<?>) obj.getClass();
//        LinkedBuffer buffer = BUFFERS.get();
//        if (buffer == null) {//存储buffer到线程局部变量中，避免每次序列化操作都分配内存提高序列化性能
//            buffer = LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE);
//            BUFFERS.set(buffer);
//        }
//        try {
            Object serializeObject = obj;
            Schema schema = WRAPPER_SCHEMA;
            if (clazz.isArray() || Collection.class.isAssignableFrom(clazz)
                || Map.class.isAssignableFrom(clazz) || Set.class.isAssignableFrom(clazz)) {//Protostuff 不支持序列化/反序列化数组、集合等对象,特殊处理
                serializeObject = SerializeWrapper.builder(obj);
            } else {
                schema = RuntimeSchema.getSchema(clazz);
            }
            ByteBuf out = tryGetDirectBuffer(512);
            //out.retain();
            serialize(serializeObject, schema, magic, out);
//            byte[] bytes = ProtostuffIOUtil.toByteArray(serializeObject, schema, buffer);
//            
//
//            out.writeInt(bytes.length);
//            out.writeInt(magic);
//            out.writeBytes(bytes);

            return out;
//        } finally {
//            buffer.clear();
//        }
    }
    
   

    /**
     * 非netty流水线(自动管理ByteBuf)环境使用完buffer必须释放 
     * {@snippet lang=java :
     * try{
     * ....buffer ops...
     * }finally {
     * if (buffer != null) {
     * buffer.clear();
     * ReferenceCountUtil.safeRelease(buffer);
     * }
     * }
     * }
     *
     * @param size
     * @return
     */
    public static ByteBuf tryGetDirectBuffer(int size) {
        ByteBuf out;
        final ByteBufAllocator alloc = PooledByteBufAllocator.DEFAULT; //1.池化的直接内存
        if (alloc.isDirectBufferPooled()) {
            out = alloc.directBuffer(size);
        } else {//2.ThreadLocal构建的Stack对象池的
            out = io.netty.buffer.ByteBufUtil.threadLocalDirectBuffer();
        }
        if (out == null) {
            out = io.netty.buffer.Unpooled.directBuffer(size);
        }
        return out;
    }

    /**
     * 序列化对象(写入buffer一个数据长度,以便对端正确反序列化) 非netty流水线(自动管理ByteBuf)环境使用完buffer必须释放 
     *
     * @param obj 需要序列化的对象
     * @param magic
     * @param capacity
     * @return 序列化后的二进制数组
     */
    @SuppressWarnings("unchecked")
    public static ByteBuf serializeToByteBuf(Object obj, int magic,int capacity) {
        Class<?> clazz = (Class<?>) obj.getClass();
//        LinkedBuffer buffer = BUFFERS.get();
//        if (buffer == null) {//存储buffer到线程局部变量中，避免每次序列化操作都分配内存提高序列化性能
//            buffer = LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE);
//            BUFFERS.set(buffer);
//        }
        try {
            Object serializeObject = obj;
            Schema schema = WRAPPER_SCHEMA;
            if (clazz.isArray() || Collection.class.isAssignableFrom(clazz)
                || Map.class.isAssignableFrom(clazz) || Set.class.isAssignableFrom(clazz)) {//Protostuff 不支持序列化/反序列化数组、集合等对象,特殊处理
                serializeObject = SerializeWrapper.builder(obj);
            } else {
                schema = RuntimeSchema.getSchema(clazz);
            }
            
            ByteBuf out = tryGetDirectBuffer(capacity);
            serialize(serializeObject, schema, magic, out);
//            byte[] bytes = ProtostuffIOUtil.toByteArray(serializeObject, schema, buffer);
//            if (out == null) {
//
//                out = io.netty.buffer.Unpooled.directBuffer(bytes.length);
//
//            }
//
//            out.writeInt(bytes.length);
//            out.writeInt(magic);
            //System.out.println(out.getClass()+" -> encoding total lenth:" + bytes.length);
//        int cap = bytes.length+LinkedBuffer.MIN_BUFFER_SIZE;
//        if(out.capacity()<cap){
//            out.capacity(cap);
//        }
           // out.writeBytes(bytes);

            return out;
        } finally {
           // buffer.clear();
        }
    }

    /**
     * 序列化对象(写入buffer一个数据长度,以便对端正确反序列化) 非netty流水线(自动管理ByteBuf)环境使用完buffer必须释放 
     * {@snippet lang=java :
     * try{
     * ....buffer ops...
     * }finally {
     * if (buffer != null) {
     * buffer.clear();
     * ReferenceCountUtil.safeRelease(buffer);
     * }
     * }
     * }
     *
     * @param obj 需要序列化的对象
     * @param out with reads data from the specified buffer starting at the current readerIndex and ending at the current writerIndex
     * @param magic 预期的协议标识,不相符抛出异常
     * @return 序列化后的二进制数组
     */
    @SuppressWarnings("unchecked")
    public static ByteBuf serializeToByteBuf(Object obj, ByteBuf out, int magic) {
        Class<?> clazz = (Class<?>) obj.getClass();
//        LinkedBuffer buffer = BUFFERS.get();
//        if (buffer == null) {//存储buffer到线程局部变量中，避免每次序列化操作都分配内存提高序列化性能
//            buffer = LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE);
//            BUFFERS.set(buffer);
//        }
        try {
            Object serializeObject = obj;
            Schema schema = WRAPPER_SCHEMA;
            if (clazz.isArray() || Collection.class.isAssignableFrom(clazz)
                || Map.class.isAssignableFrom(clazz) || Set.class.isAssignableFrom(clazz)) {//Protostuff 不支持序列化/反序列化数组、集合等对象,特殊处理
                serializeObject = SerializeWrapper.builder(obj);
            } else {
                schema = RuntimeSchema.getSchema(clazz);
            }
            serialize(serializeObject, schema, magic, out);
//            byte[] bytes = ProtostuffIOUtil.toByteArray(serializeObject, schema, buffer);
//
//            out.writeInt(bytes.length);
//            out.writeInt(magic);
//           
//            out.writeBytes(bytes);

            return out;
        } finally {
            //buffer.clear();
        }
    }
    
    /**
     * 反序列化对象
     * ByteBuf in.readerIndex() 必须存储有数据长度
     * 并且满足条件in.readableBytes() >= in.readInt()
     * @param in 需要反序列化的ByteBuf with reads data from the specified buffer starting at the current readerIndex and ending at the current writerIndex
     * @param clazz 反序列化后的对象class
     * @param <T> 反序列化后的对象类型
     * @param magic 预期的协议标识,不相符抛出异常
     * 
     * @return 反序列化后的实例对象
     */
    public static <T> T deserialize(Class<T> clazz, ByteBuf in,int magic) {
        if (in.readableBytes() < 4) {
            //new PooledByteBufAllocator(false);
            throw new DataLengthLimitedException("Buffer readableBytes is less than 4 byte,current size:" + in.readableBytes());
        }
        int length = in.readInt();
        int magicNow = in.readInt();
        if(magicNow!=magic){
            throw new RuntimeException("无效协议标识");
        }
        return deserialize(clazz, in, magic,length);
    }
    
    /**
     * 反序列化对象
     * ByteBuf in.readerIndex() 必须存储有数据长度
     * 并且满足条件in.readableBytes() >= in.readInt()
     * @param in 需要反序列化的ByteBuf with reads data from the specified buffer starting at the current readerIndex and ending at the current writerIndex
     * @param clazz 反序列化后的对象class
     * @param <T> 反序列化后的对象类型
     * 
     * @return 反序列化后的实例对象
     */
    public static <T> T deserialize(Class<T> clazz, ByteBuf in) {
        
        try {//先获取可读字节数
            //final byte[] data = new byte[length];
            //in.readBytes(data);
            //System.out.println("data:"+data.length);
            //in.clear();
            ByteBufInputStream input = new ByteBufInputStream(in);
            if (clazz.isArray() || Collection.class.isAssignableFrom(clazz)
                || Map.class.isAssignableFrom(clazz) || Set.class.isAssignableFrom(clazz)) {//Protostuff 不支持序列化/反序列化数组、集合等对象,特殊处理
                SerializeWrapper<T> wrapper = new SerializeWrapper<>();

                ProtostuffIOUtil.mergeFrom(input, wrapper, WRAPPER_SCHEMA);
//                ProtostuffIOUtil.mergeFrom(data, wrapper, WRAPPER_SCHEMA);
                return wrapper.getData();
            } else {
                Schema<T> schema = RuntimeSchema.getSchema(clazz);
                T message = schema.newMessage();
                //ProtostuffIOUtil.mergeFrom(data, message, schema);
                ProtostuffIOUtil.mergeFrom(input, message, schema);
                return message;
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * 反序列化对象
     *
     * @param in 需要反序列化的ByteBuf
     * @param clazz 反序列化后的对象class
     * @param <T> 反序列化后的对象类型
     * @return 反序列化后的实例对象
     */
    public static <T> T deserializeWrapper(Class<T> clazz, ByteBuf in) {
        if (in.readableBytes() < 8) {
            throw new DataLengthLimitedException("Buffer readableBytes is not enough,expected header size:" + 8 + ",current size:" + in.readableBytes());
        }
        int length = in.readInt();
        int magic = in.readInt();
        return deserialize(clazz, in, magic, length);
    }

    /**
     * 反序列化对象
     *
     * @param in 需要反序列化的ByteBuf
     * @param clazz 反序列化后的对象class
     * @param <T> 反序列化后的对象类型
     * @param magic 协议标识
     * @param length
     * @return 反序列化后的实例对象
     */
    public static <T> T deserialize(Class<T> clazz, ByteBuf in,int magic,int length) {
        
        if (in.readableBytes() < length) {
            throw new DataLengthLimitedException("Buffer readableBytes is not enough,expected size:" + length + ",current size:" + in.readableBytes());
        }
        try {//先获取可读字节数
            final byte[] data = new byte[length];
            in.readBytes(data);
            System.out.println("data:"+data.length);
            //ByteBufInputStream input = new ByteBufInputStream(in);
            if (clazz.isArray() || Collection.class.isAssignableFrom(clazz)
                || Map.class.isAssignableFrom(clazz) || Set.class.isAssignableFrom(clazz)) {//Protostuff 不支持序列化/反序列化数组、集合等对象,特殊处理
                SerializeWrapper<T> wrapper = new SerializeWrapper<>();

                //ProtostuffIOUtil.mergeFrom(input, wrapper, WRAPPER_SCHEMA);
                ProtostuffIOUtil.mergeFrom(data, wrapper, WRAPPER_SCHEMA);
                return wrapper.getData();
            } else {
                Schema<T> schema = RuntimeSchema.getSchema(clazz);
                T message = schema.newMessage();
                ProtostuffIOUtil.mergeFrom(data, message, schema);
                //ProtostuffIOUtil.mergeFrom(input, message, schema);
                return message;
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * 反序列化对象
     *
     * @param data 需要反序列化的二进制数组
     * @param clazz 反序列化后的对象class
     * @param <T> 反序列化后的对象类型
     * @return 反序列化后的实例对象
     */
    public static <T> T deserialize(Class<T> clazz, byte[] data) {
        if (clazz.isArray() || Collection.class.isAssignableFrom(clazz)
            || Map.class.isAssignableFrom(clazz) || Set.class.isAssignableFrom(clazz)) {//Protostuff 不支持序列化/反序列化数组、集合等对象,特殊处理
            SerializeWrapper<T> wrapper = new SerializeWrapper<>();
            ProtostuffIOUtil.mergeFrom(data, wrapper, WRAPPER_SCHEMA);
            return wrapper.getData();
        } else {
            Schema<T> schema = RuntimeSchema.getSchema(clazz);
            T message = schema.newMessage();
            ProtostuffIOUtil.mergeFrom(data, message, schema);
            return message;
        }
    }

    /**
     * 反序列化对象
     *
     * @param data 需要反序列化的二进制数组
     * @param clazz 反序列化后的对象class
     * @param <T> 反序列化后的对象类型
     * @param offset
     * @param length
     * @return 反序列化后的实例对象
     */
    public static <T> T deserialize(Class<T> clazz, byte[] data, int offset, int length) {
        if (clazz.isArray() || Collection.class.isAssignableFrom(clazz)
            || Map.class.isAssignableFrom(clazz) || Set.class.isAssignableFrom(clazz)) {//Protostuff 不支持序列化/反序列化数组、集合等对象,特殊处理
            SerializeWrapper<T> wrapper = new SerializeWrapper<>();
            ProtostuffIOUtil.mergeFrom(data, offset, length, wrapper, WRAPPER_SCHEMA);
            return wrapper.getData();
        } else {
            Schema<T> schema = RuntimeSchema.getSchema(clazz);
            T message = schema.newMessage();
            ProtostuffIOUtil.mergeFrom(data, offset, length, message, schema);
            return message;
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("String[abcd]:" + SerializationUtil.serialize("abcd").length);
        byte[] array = "abcd".getBytes();
        Class c = String[].class;
        //System.out.println(new String(SerializationUtil.deserialize(byte[].class, SerializationUtil.serialize(array))));
        System.out.println("array:" + SerializationUtil.serialize(array).length);
        System.out.println(SerializationUtil.deserialize(byte[].class, SerializationUtil.serialize(array)).getClass());
        //System.out.println(SerializationUtil.deserialize(byte[].class, SerializationUtil.serialize(array)).getClass());

        //LinkedList<Object> list = new LinkedList<>();
        Set<Object> list = new HashSet<>();
        list.add("aa");
        list.add("bb");
        System.out.println(SerializationUtil.serialize(list).length);

        //System.out.println(SerializationUtil.deserialize(LinkedList.class, SerializationUtil.serialize(list)));
        //System.out.println(SerializationUtil.deserialize(LinkedList.class, SerializationUtil.serialize(list)).getClass());
        System.out.println(SerializationUtil.deserialize(HashSet.class, SerializationUtil.serialize(list)));
        System.out.println(SerializationUtil.deserialize(HashSet.class, SerializationUtil.serialize(list)).getClass());
    }

}
