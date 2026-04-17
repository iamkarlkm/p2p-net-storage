package com.q3lives.utils;

import io.netty.buffer.ByteBuf;
import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.runtime.RuntimeSchema;
import java.io.Serializable;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * **************************************************
 * @description Protostuff 序列化/反序列化工具类
 * @author iamkarl@163.com
 * @version 2.0, 2018-08-07
 * @history
 *      Date        Desc          Author      Operation
 *  	2017-4-7 创建文件 karl create Date Desc
 * Author Operation 2018-08-07 创建文件 karl Protostuff 不支持序列化/反序列化数组、集合等对象,特殊处理
 * @since 2017 Phyrose Science & Technology (Kunming) Co., Ltd.
 **************************************************/
public class SerializationUtil {


    //private static final Log log = LogFactory.getLog(SerializationUtil.class);

    //public static final AtomicInteger IntegerSequence = new AtomicInteger();
    /**
     * 线程局部变量
     */
    private static final ThreadLocal<LinkedBuffer> BUFFERS = new ThreadLocal<>();

    /**
     * 序列化/反序列化包装类 Schema 对象
     */
    private static final Schema<SerializeWrapper> WRAPPER_SCHEMA = RuntimeSchema.getSchema(SerializeWrapper.class);


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
     * @return 序列化后的二进制数组
     */
    @SuppressWarnings("unchecked")
    public static ByteBuf serializeToByteBuf(Object obj) {
        Class<?> clazz = obj.getClass();
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
            byte[] bytes = ProtostuffIOUtil.toByteArray(serializeObject, schema, buffer);
            ByteBuf out = io.netty.buffer.ByteBufUtil.threadLocalDirectBuffer();
            if (out == null) {

                out = io.netty.buffer.Unpooled.directBuffer(bytes.length);

            }

            out.writeInt(bytes.length);
            //System.out.println(out.getClass()+" -> encoding total lenth:" + bytes.length);
//        int cap = bytes.length+LinkedBuffer.MIN_BUFFER_SIZE;
//        if(out.capacity()<cap){
//            out.capacity(cap);
//        }
            out.writeBytes(bytes);

            return  out;
        } finally {
            buffer.clear();
        }
    }

    /**
     * 序列化对象
     *
     * @param obj 需要序列化的对象
     * @param magic 序列化标识
     * @return 序列化后的ByteBuf
     */
    @SuppressWarnings("unchecked")
    public static ByteBuf serializeToByteBuf(Object obj,int magic) {
        Class<?> clazz =  obj.getClass();
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
            byte[] bytes = ProtostuffIOUtil.toByteArray(serializeObject, schema, buffer);
            ByteBuf out = io.netty.buffer.ByteBufUtil.threadLocalDirectBuffer();
            if (out == null) {

                out = io.netty.buffer.Unpooled.directBuffer(bytes.length);

            }

            out.writeInt(bytes.length);
            out.writeInt(magic);
            //System.out.println(out.getClass()+" -> encoding total lenth:" + bytes.length);
//        int cap = bytes.length+LinkedBuffer.MIN_BUFFER_SIZE;
//        if(out.capacity()<cap){
//            out.capacity(cap);
//        }
            out.writeBytes(bytes);

            return  out;
        } finally {
            buffer.clear();
        }
    }

    /**
     * 序列化对象
     *
     * @param obj 需要序列化的对象
     * @param out ByteBuf
     * @param magic 序列化标识
     * @return 序列化后的二进制数组
     */
    @SuppressWarnings("unchecked")
    public static void serializeToByteBuf(Object obj,ByteBuf out,int magic) {
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
            byte[] bytes = ProtostuffIOUtil.toByteArray(serializeObject, schema, buffer);


            out.writeInt(bytes.length);
            out.writeInt(magic);
            //System.out.println(out.getClass()+" -> encoding total lenth:" + bytes.length);
//        int cap = bytes.length+LinkedBuffer.MIN_BUFFER_SIZE;
//        if(out.capacity()<cap){
//            out.capacity(cap);
//        }
            out.writeBytes(bytes);

            //return  out;
        } finally {
            buffer.clear();
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
    public static <T> T deserializeFromByteBuf(Class<T> clazz,ByteBuf in) {
        if (in.readableBytes() < 4) {
            //new PooledByteBufAllocator(false);
            throw new RuntimeException("Buffer readableBytes is less than 4 byte,current size:"+in.readableBytes());
        }

        //int beginIndex = in.readerIndex();
        int length = in.readInt();
        //System.out.println(in.readableBytes()+" vs "+length);
        if (in.readableBytes() < length) {
//           in.readerIndex(beginIndex);
//           if (in.capacity() < length) {
//               in.capacity(length + 128);
//           }
//           in.capacity(length);
            //System.out.println(length+":"+in.writerIndex()+" decoding in.readableBytes():" + in.readableBytes());
            throw new RuntimeException("Buffer readableBytes is not enough,expected size:"+length+",current size:"+in.readableBytes());
        }

        try {//先获取可读字节数
            final byte[] data = new byte[length];
            in.readBytes(data);
            //System.out.println("Buffer read success...");
            //in.clear();
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

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * 反序列化对象
     * @param clazz 反序列化后的对象class
     * @param in    需要反序列化的ByteBuf
     * @param magic 序列化标识
     * @return
     * @param <T>
     */
    public static <T> T deserializeFromByteBuf(Class<T> clazz,ByteBuf in,int magic) {
        if (in.readableBytes() < 8) {
            //new PooledByteBufAllocator(false);
            throw new RuntimeException("Buffer readableBytes is less than 4 byte,current size:"+in.readableBytes());
        }

        //int beginIndex = in.readerIndex();
        int length = in.readInt();
        //System.out.println(in.readableBytes()+" vs "+length);
        if (in.readableBytes() < length) {
//           in.readerIndex(beginIndex);
//           if (in.capacity() < length) {
//               in.capacity(length + 128);
//           }
//           in.capacity(length);
            //System.out.println(length+":"+in.writerIndex()+" decoding in.readableBytes():" + in.readableBytes());
            throw new RuntimeException("Buffer readableBytes is not enough,expected size:"+length+",current size:"+in.readableBytes());
        }
        int magicNum = in.readInt();
        //System.out.println(in.readableBytes()+" vs "+length);
        if (magicNum!= magic) { //校验标识
            throw new RuntimeException("magic number is not match,expected magic number:"+magic+",current magic number:"+magicNum);
        }
        try {//先获取可读字节数
            final byte[] data = new byte[length];
            in.readBytes(data);
            //System.out.println("Buffer read success...");
            //in.clear();
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
     * @param clazz 反序列化后的对象class
     * @param data 二进制数组
     * @param offset 需要反序列化的偏移量
     * @param length 需要反序列化的长度
     * @param <T> 反序列化后的对象类型
     * @return 反序列化后的实例对象
     */
    public static <T> T deserialize(Class<T> clazz, byte[] data, int offset, int length) {
        if (clazz.isArray() || Collection.class.isAssignableFrom(clazz)
                || Map.class.isAssignableFrom(clazz) || Set.class.isAssignableFrom(clazz)) {//Protostuff 不支持序列化/反序列化数组、集合等对象,特殊处理
            SerializeWrapper<T> wrapper = new SerializeWrapper<>();
            ProtostuffIOUtil.mergeFrom(data ,offset,length,wrapper, WRAPPER_SCHEMA);
            return wrapper.getData();
        } else {
            Schema<T> schema = RuntimeSchema.getSchema(clazz);
            T message = schema.newMessage();
            ProtostuffIOUtil.mergeFrom(data ,offset,length, message, schema);
            return message;
        }
    }


    public static void main(String[] args) throws Exception {
        System.out.println("String[abcd]:" + SerializationUtil.serialize("abcd").length);
        byte[] array = "abcd".getBytes();
        Class<?> c = String[].class;
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
	
	static class SerializeWrapper<T> {

    private T data;

    public static <T> SerializeWrapper<T> builder(T data) {
        SerializeWrapper<T> wrapper = new SerializeWrapper<>();
        wrapper.setData(data);
        return wrapper;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

}

}