package javax.net.p2p.utils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
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

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.exception.DataLengthLimitedException;
import javax.net.p2p.model.P2PWrapper;
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
        //byteBuf.writeInt(hash);
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
        //P2PWrapper request = SerializationUtil.deserialize(P2PWrapper.class, output.byteBuf);
        // byteBuf.markWriterIndex();
        //byteBuf.writeByte(0);
        // byteBuf.resetWriterIndex();
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
       LinkedBuffer buffer = BUFFERS.get();
       if (buffer == null) {//存储buffer到线程局部变量中，避免每次序列化操作都分配内存提高序列化性能
           buffer = LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE);
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
            ByteBuf out = tryGetDirectBuffer(512);
            serialize(serializeObject, schema, magic, out);
        //    byte[] bytes = ProtostuffIOUtil.toByteArray(serializeObject, schema, buffer);
           
        //    out.writeInt(bytes.length);
        //    out.writeInt(magic);
        //    out.writeBytes(bytes);

            return out;
       } finally {
           buffer.clear();
       }
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
        
        ByteBuf inBuffer = Unpooled.buffer();
        SerializationUtil.serializeToByteBuf(P2PWrapper.build(1,P2PCommand.HEART_PONG), inBuffer,3);
        int frameLengthInt = inBuffer.readInt();//保存frame字节数
         int   magic = inBuffer.readInt();
         System.out.println("magic:"+magic);
         System.out.println("frameLengthInt:"+frameLengthInt);
        byte[] data = new byte[frameLengthInt];
                inBuffer.readBytes(data);
 P2PWrapper request = SerializationUtil.deserialize(P2PWrapper.class, data);
 System.out.println(request);
    }

    public static void main2(String[] args) throws Exception {
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

    /**
     * ============================================================================
     * 使用示例 - 完整演示 SerializationUtil 类的各种功能
     * ============================================================================
     * 
     * <pre>
     * <code>
     * // 示例1: 基本对象序列化/反序列化
     * public void basicSerializationExample() {
     *     // 准备数据对象
     *     User user = new User("张三", 30, "zhangsan@example.com");
     *     
     *     // 序列化对象到字节数组
     *     byte[] serializedData = SerializationUtil.serialize(user);
     *     System.out.println("序列化大小: " + serializedData.length + " bytes");
     *     
     *     // 反序列化字节数组回对象
     *     User deserializedUser = SerializationUtil.deserialize(User.class, serializedData);
     *     System.out.println("反序列化结果: " + deserializedUser);
     *     System.out.println("姓名: " + deserializedUser.getName());
     *     System.out.println("年龄: " + deserializedUser.getAge());
     * }
     * 
     * // 示例2: 集合和数组的序列化
     * public void collectionSerializationExample() {
     *     // 创建集合对象
     *     List<String> nameList = Arrays.asList("张三", "李四", "王五");
     *     Map<String, Integer> scoreMap = new HashMap<>();
     *     scoreMap.put("数学", 95);
     *     scoreMap.put("英语", 88);
     *     scoreMap.put("物理", 92);
     *     
     *     // 序列化集合
     *     byte[] listData = SerializationUtil.serialize(nameList);
     *     byte[] mapData = SerializationUtil.serialize(scoreMap);
     *     
     *     System.out.println("列表序列化大小: " + listData.length + " bytes");
     *     System.out.println("Map序列化大小: " + mapData.length + " bytes");
     *     
     *     // 反序列化集合
     *     List<String> deserializedList = SerializationUtil.deserialize(ArrayList.class, listData);
     *     Map<String, Integer> deserializedMap = SerializationUtil.deserialize(HashMap.class, mapData);
     *     
     *     System.out.println("反序列化列表: " + deserializedList);
     *     System.out.println("反序列化Map: " + deserializedMap);
     * }
     * 
     * // 示例3: Netty ByteBuf 序列化（带协议标识）
     * public void nettyByteBufSerializationExample() {
     *     // 准备数据
     *     FileInfo fileInfo = new FileInfo("test.pdf", 1024 * 1024, "application/pdf");
     *     int protocolMagic = 0x12345678; // 协议标识
     *     
     *     // 序列化到ByteBuf（带协议头）
     *     ByteBuf serializedBuf = SerializationUtil.serializeToByteBuf(fileInfo, protocolMagic);
     *     
     *     try {
     *         System.out.println("ByteBuf大小: " + serializedBuf.readableBytes() + " bytes");
     *         System.out.println("ByteBuf协议标识位置: " + serializedBuf.getInt(4)); // 读取协议标识
     *         
     *         // 反序列化（验证协议标识）
     *         FileInfo deserializedInfo = SerializationUtil.deserialize(
     *             FileInfo.class, serializedBuf, protocolMagic);
     *             
     *         System.out.println("反序列化文件信息:");
     *         System.out.println("  文件名: " + deserializedInfo.getFileName());
     *         System.out.println("  文件大小: " + deserializedInfo.getFileSize() + " bytes");
     *         System.out.println("  文件类型: " + deserializedInfo.getMimeType());
     *     } finally {
     *         // 释放ByteBuf资源
     *         if (serializedBuf != null) {
     *             serializedBuf.release();
     *         }
     *     }
     * }
     * 
     * // 示例4: 高性能序列化（使用ByteBuf直接缓冲区）
     * public void highPerformanceSerializationExample() {
     *     // 创建大对象
     *     List<DataPoint> dataPoints = new ArrayList<>();
     *     for (int i = 0; i < 10000; i++) {
     *         dataPoints.add(new DataPoint(i, Math.random(), System.currentTimeMillis()));
     *     }
     *     
     *     // 使用直接缓冲区序列化（高性能）
     *     ByteBuf directBuffer = SerializationUtil.serializeToByteBuf(dataPoints);
     *     
     *     try {
     *         System.out.println("数据点数量: " + dataPoints.size());
     *         System.out.println("序列化后大小: " + directBuffer.readableBytes() + " bytes");
     *         
     *         // 反序列化验证
     *         List<DataPoint> deserializedPoints = SerializationUtil.deserialize(
     *             ArrayList.class, directBuffer);
     *             
     *         System.out.println("反序列化数据点数量: " + deserializedPoints.size());
     *         System.out.println("第一个数据点: " + deserializedPoints.get(0));
     *     } finally {
     *         if (directBuffer != null) {
     *             directBuffer.release();
     *         }
     *     }
     * }
     * 
     * // 示例5: 网络传输场景（带长度和协议验证）
     * public void networkTransferExample() {
     *     // 模拟网络传输数据
     *     TransferCommand command = new TransferCommand(
     *         "DOWNLOAD", 
     *         "/data/files/report.pdf", 
     *         "user123",
     *         System.currentTimeMillis()
     *     );
     *     
     *     int protocolVersion = 2; // 协议版本号
     *     
     *     // 序列化带协议版本
     *     ByteBuf commandBuf = SerializationUtil.serializeToByteBuf(command, protocolVersion, 1024);
     *     
     *     try {
     *         // 模拟网络传输后接收端处理
     *         ByteBuf receivedBuf = commandBuf.duplicate(); // 模拟接收到的数据
     *         
     *         // 验证协议版本
     *         TransferCommand receivedCmd = SerializationUtil.deserialize(
     *             TransferCommand.class, receivedBuf, protocolVersion);
     *             
     *         System.out.println("接收到的命令:");
     *         System.out.println("  操作类型: " + receivedCmd.getOperation());
     *         System.out.println("  文件路径: " + receivedCmd.getFilePath());
     *         System.out.println("  用户: " + receivedCmd.getUserId());
     *         System.out.println("  时间戳: " + receivedCmd.getTimestamp());
     *         
     *     } finally {
     *         if (commandBuf != null) {
     *             commandBuf.release();
     *         }
     *     }
     * }
     * 
     * // 示例6: 错误处理和数据验证
     * public void errorHandlingExample() {
     *     try {
     *         // 准备数据
     *         String testData = "测试数据";
     *         byte[] data = SerializationUtil.serialize(testData);
     *         
     *         // 模拟损坏的数据
     *         byte[] corruptedData = new byte[data.length];
     *         System.arraycopy(data, 0, corruptedData, 0, data.length - 10);
     *         
     *         // 尝试反序列化损坏的数据
     *         String result = SerializationUtil.deserialize(String.class, corruptedData);
     *         System.out.println("反序列化结果: " + result);
     *         
     *     } catch (Exception e) {
     *         System.err.println("反序列化失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
     *         // 处理反序列化失败的情况
     *     }
     * }
     * 
     * // 示例7: 批量数据处理
     * public void batchProcessingExample() {
     *     List<User> userBatch = new ArrayList<>();
     *     for (int i = 1; i <= 100; i++) {
     *         userBatch.add(new User("用户" + i, 20 + i % 40, "user" + i + "@example.com"));
     *     }
     *     
     *     // 批量序列化
     *     List<byte[]> serializedBatch = new ArrayList<>();
     *     for (User user : userBatch) {
     *         serializedBatch.add(SerializationUtil.serialize(user));
     *     }
     *     
     *     System.out.println("批量处理用户数量: " + userBatch.size());
     *     System.out.println("序列化数据包数量: " + serializedBatch.size());
     *     
     *     // 批量反序列化
     *     List<User> deserializedBatch = new ArrayList<>();
     *     for (byte[] data : serializedBatch) {
     *         deserializedBatch.add(SerializationUtil.deserialize(User.class, data));
     *     }
     *     
     *     System.out.println("反序列化成功数量: " + deserializedBatch.size());
     *     
     *     // 验证数据完整性
     *     boolean allMatch = true;
     *     for (int i = 0; i < userBatch.size(); i++) {
     *         if (!userBatch.get(i).getName().equals(deserializedBatch.get(i).getName())) {
     *             allMatch = false;
     *             break;
     *         }
     *     }
     *     System.out.println("数据完整性验证: " + (allMatch ? "通过" : "失败"));
     * }
     * 
     * // 示例8: 自定义序列化场景
     * public void customSerializationExample() {
     *     // 创建复杂对象
     *     ComplexObject complexObj = new ComplexObject();
     *     complexObj.setId(UUID.randomUUID().toString());
     *     complexObj.setProperties(new HashMap<>());
     *     complexObj.getProperties().put("version", "1.0.0");
     *     complexObj.getProperties().put("timestamp", String.valueOf(System.currentTimeMillis()));
     *     complexObj.setDataList(Arrays.asList("item1", "item2", "item3"));
     *     
     *     // 使用自定义缓冲区容量序列化
     *     ByteBuf customBuffer = SerializationUtil.serializeToByteBuf(complexObj, 0xABCD, 4096);
     *     
     *     try {
     *         // 验证序列化结果
     *         ComplexObject deserialized = SerializationUtil.deserialize(
     *             ComplexObject.class, customBuffer, 0xABCD);
     *             
     *         System.out.println("复杂对象序列化验证:");
     *         System.out.println("  ID: " + deserialized.getId());
     *         System.out.println("  属性数量: " + deserialized.getProperties().size());
     *         System.out.println("  数据列表大小: " + deserialized.getDataList().size());
     *         System.out.println("  版本: " + deserialized.getProperties().get("version"));
     *         
     *     } finally {
     *         if (customBuffer != null) {
     *             customBuffer.release();
     *         }
     *     }
     * }
     * 
     * // 示例9: 性能对比测试
     * public void performanceComparisonExample() {
     *     int iterations = 10000;
     *     TestObject testObj = new TestObject("性能测试对象", 12345, 67.89);
     *     
     *     // 测试序列化性能
     *     long startTime = System.nanoTime();
     *     for (int i = 0; i < iterations; i++) {
     *         byte[] data = SerializationUtil.serialize(testObj);
     *     }
     *     long serializationTime = System.nanoTime() - startTime;
     *     
     *     // 测试反序列化性能
     *     byte[] testData = SerializationUtil.serialize(testObj);
     *     startTime = System.nanoTime();
     *     for (int i = 0; i < iterations; i++) {
     *         TestObject obj = SerializationUtil.deserialize(TestObject.class, testData);
     *     }
     *     long deserializationTime = System.nanoTime() - startTime;
     *     
     *     System.out.println("性能测试结果:");
     *     System.out.println("  序列化 " + iterations + " 次耗时: " + 
     *         (serializationTime / 1_000_000.0) + " ms");
     *     System.out.println("  反序列化 " + iterations + " 次耗时: " + 
     *         (deserializationTime / 1_000_000.0) + " ms");
     *     System.out.println("  平均每次序列化: " + 
     *         (serializationTime / (iterations * 1_000.0)) + " μs");
     *     System.out.println("  平均每次反序列化: " + 
     *         (deserializationTime / (iterations * 1_000.0)) + " μs");
     * }
     * 
     * // 示例10: 综合使用场景 - 网络消息处理
     * public void networkMessageProcessingExample() {
     *     // 模拟网络消息处理流水线
     *     NetworkMessage message = new NetworkMessage();
     *     message.setMessageId(1001);
     *     message.setMessageType("FILE_TRANSFER");
     *     message.setPayload(new FileTransferPayload(
     *         "document.pdf",
     *         5 * 1024 * 1024, // 5MB
     *         "user123",
     *         "user456"
     *     ));
     *     message.setTimestamp(System.currentTimeMillis());
     *     
     *     // 序列化消息（带协议标识）
     *     int protocolMagic = 0xCAFEBABE;
     *     ByteBuf messageBuf = SerializationUtil.serializeToByteBuf(message, protocolMagic);
     *     
     *     try {
     *         // 模拟网络传输
     *         System.out.println("网络消息处理示例:");
     *         System.out.println("  消息ID: " + message.getMessageId());
     *         System.out.println("  消息类型: " + message.getMessageType());
     *         System.out.println("  序列化大小: " + messageBuf.readableBytes() + " bytes");
     *         
     *         // 接收端处理
     *         NetworkMessage receivedMessage = SerializationUtil.deserialize(
     *             NetworkMessage.class, messageBuf, protocolMagic);
     *             
     *         // 验证消息完整性
     *         if (receivedMessage.getMessageId() == message.getMessageId() &&
     *             receivedMessage.getMessageType().equals(message.getMessageType())) {
     *             System.out.println("  消息验证: 通过");
     *             
     *             // 处理消息负载
     *             FileTransferPayload payload = (FileTransferPayload) receivedMessage.getPayload();
     *             System.out.println("  文件传输详情:");
     *             System.out.println("    文件名: " + payload.getFileName());
     *             System.out.println("    文件大小: " + payload.getFileSize() + " bytes");
     *             System.out.println("    发送者: " + payload.getSenderId());
     *             System.out.println("    接收者: " + payload.getReceiverId());
     *         } else {
     *             System.out.println("  消息验证: 失败");
     *         }
     *         
     *     } finally {
     *         if (messageBuf != null) {
     *             messageBuf.release();
     *         }
     *     }
     * }
     * 
     * // 数据模型定义示例
     * public static class User {
     *     private String name;
     *     private int age;
     *     private String email;
     *     
     *     // 必须有无参构造函数供Protostuff使用
     *     public User() {}
     *     
     *     public User(String name, int age, String email) {
     *         this.name = name;
     *         this.age = age;
     *         this.email = email;
     *     }
     *     
     *     // getters and setters...
     * }
     * 
     * public static class FileInfo {
     *     private String fileName;
     *     private long fileSize;
     *     private String mimeType;
     *     
     *     // 必须有无参构造函数...
     * }
     * 
     * // 更多数据模型定义...
     * </code>
     * </pre>
     * 
     * 注意事项:
     * 1. 所有需要序列化的Java类必须有无参构造函数
     * 2. 集合类型（List、Set、Map）和数组需要特殊处理，SerializationUtil会自动包装
     * 3. ByteBuf使用后必须正确释放资源，避免内存泄漏
     * 4. 协议标识（magic）用于验证数据完整性和协议版本控制
     * 5. 对于大对象，建议使用ByteBuf直接缓冲区提高性能
     * 6. 序列化数据应包含长度信息，以便接收端正确解析
     * 7. 生产环境建议添加数据校验和错误恢复机制
     */
}
