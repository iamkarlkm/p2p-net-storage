package javax.net.p2p.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.DESKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class SecurityUtils {

    static Log log = LogFactory.getLog(SecurityUtils.class);
    private final static String DES = "DES";
    private static final String AES = "AES"; 
    private static final Charset DEFAULT_CHARSET = Charset.forName("UTF-8"); 
      
    private static final String DEFAULT_CIPHER_ALGORITHM = "AES/ECB/PKCS5Padding";  
       

    public static void main(String[] args) throws Exception {
//        System.out.println(passwordEncoder("123456", "admin"));
//        String data = "abcd yui";
//        String key = "123456asssshjdk";
        System.err.println(getFileMD5String(new File("E:/test/mtd.mp4")));
//        System.err.println(sha256(key));
//        System.err.println(sha1(key));

    }
    
    public static void mainDes(String[] args) throws Exception {  
        byte[] key = initAesSecretKey();  
        System.out.println("key："+showByteArray(key));  
          
        String data ="AES加密解密数据Test";  
        System.out.println("加密前数据: string:"+data);  
        System.out.println("加密前数据: byte[]:"+showByteArray(data.getBytes()));  
        System.out.println();  
        byte[] encryptData = SecurityUtils.encryptAes(data.getBytes(), key);  
        System.out.println("加密后数据: byte[]:"+showByteArray(encryptData));  
        System.out.println("加密后数据: hexStr:"+Hex.encodeHexString(encryptData));  
        System.out.println();  
        byte[] decryptData = SecurityUtils.decryptAes(encryptData, key);  
        System.out.println("解密后数据: byte[]:"+showByteArray(decryptData));  
        System.out.println("解密后数据: string:"+new String(decryptData));  
          
    }  


    public static String getFileMD5String(File file) {
        RandomAccessFile randomAccessFile = null;
        try {
            randomAccessFile = new RandomAccessFile(file, "r");
            MessageDigest messagedigest = MessageDigest.getInstance("MD5");
            //NIO 实现
                FileChannel fileChannel = randomAccessFile.getChannel();
                int bufferSize = 1024 * 128;
                byte[] byteArr = new byte[bufferSize];
                int nRead = 0, nGet = 0, nioSize = 1024 * 10;
                ByteBuffer buff = ByteBuffer.allocateDirect(nioSize);
                try {
                    while ((nRead = fileChannel.read(buff)) != -1) {
                        if (nRead == 0) {
                            continue;
                        }
                        buff.position(0);
                        buff.limit(nRead);
                        while (buff.hasRemaining()) {
                            nGet = Math.min(buff.remaining(), bufferSize);
                            // read bytes from disk
                            buff.get(byteArr, 0, nGet);
                            // write bytes to output
                           messagedigest.update(byteArr, 0, nGet);
                        }
                        buff.clear();
                    }
                    
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    buff.clear();
                    fileChannel.close();
                }
            return Hex.encodeHexString(messagedigest.digest());
        } catch (Exception e) {
            log.error(e, e);
            throw new RuntimeException(e);
        } finally {
            try {
                if (randomAccessFile != null) {
                    randomAccessFile.close();
                }
            } catch (Exception e) {

            }
        }
    }
    
        public static String getFileSha256(File file) {
        FileInputStream in = null;
        FileChannel channel = null;
        try {
            in = new FileInputStream(file);
            channel = in.getChannel();
            MappedByteBuffer byteBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length());
            MessageDigest messagedigest = MessageDigest.getInstance("sha-256");
            messagedigest.update(byteBuffer);
            return Hex.encodeHexString(messagedigest.digest());
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            try {
                if (channel != null) {
                    channel.close();
                    in.close();
                }
            } catch (Exception e) {

            }
        }
    }
    
    public static String toMD5(byte[] bytes) {
        try {
            MessageDigest messagedigest = MessageDigest.getInstance("MD5");
            messagedigest.update(bytes);
            return Hex.encodeHexString(messagedigest.digest());
        } catch (Exception e) {
            throw new RuntimeException(e);
        } 
    }

 

    /**
     * Description 根据键值进行加密
     *
     * @param data
     * @param key 加密键byte数组
     * @return
     * @throws Exception
     */
    public static String encryptDes(String data, String key) throws Exception {
        byte[] bt = encryptDes(data.getBytes(), md5(key).getBytes());
        String strs = Base64.encodeBase64String(bt);
        return strs;
    }

    /**
     * Description 根据键值进行解密
     *
     * @param data
     * @param key 加密键byte数组
     * @return
     * @throws IOException
     * @throws Exception
     */
    public static String decryptDes(String data, String key) throws IOException,
            Exception {
        if (data == null) {
            return null;
        }
        byte[] buf = Base64.decodeBase64(data);
        byte[] bt = decryptDes(buf, md5(key).getBytes());
        return new String(bt);
    }

    /**
     * Description 根据键值进行加密
     *
     * @param data
     * @param key 加密键byte数组
     * @return
     * @throws Exception
     */
    private static byte[] encryptDes(byte[] data, byte[] key) throws Exception {
        // 生成一个可信任的随机数源
        SecureRandom sr = new SecureRandom();

        // 从原始密钥数据创建DESKeySpec对象
        DESKeySpec dks = new DESKeySpec(key);

        // 创建一个密钥工厂，然后用它把DESKeySpec转换成SecretKey对象
        SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(DES);
        SecretKey securekey = keyFactory.generateSecret(dks);

        // Cipher对象实际完成加密操作
        Cipher cipher = Cipher.getInstance(DES);

        // 用密钥初始化Cipher对象
        cipher.init(Cipher.ENCRYPT_MODE, securekey, sr);

        return cipher.doFinal(data);
    }

    /**
     * Description 根据键值进行解密
     *
     * @param data
     * @param key 加密键byte数组
     * @return
     * @throws Exception
     */
    private static byte[] decryptDes(byte[] data, byte[] key) throws Exception {
        // 生成一个可信任的随机数源
        SecureRandom sr = new SecureRandom();

        // 从原始密钥数据创建DESKeySpec对象
        DESKeySpec dks = new DESKeySpec(key);

        // 创建一个密钥工厂，然后用它把DESKeySpec转换成SecretKey对象
        SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(DES);
        SecretKey securekey = keyFactory.generateSecret(dks);

        // Cipher对象实际完成解密操作
        Cipher cipher = Cipher.getInstance(DES);

        // 用密钥初始化Cipher对象
        cipher.init(Cipher.DECRYPT_MODE, securekey, sr);

        return cipher.doFinal(data);
    }

    /**
     * 二次加密
     *
     * @param inputText
     * @return
     */
    public static String md5WithSha256(String inputText) {
        return md5(sha256(inputText));
    }

    /**
     * md5加密
     *
     * @param inputText
     * @return
     */
    public static String md5(String inputText) {
        return messageDigest(inputText, "md5");
    }

    /**
     * sha加密
     *
     * @param inputText
     * @return
     */
    public static String sha1(String inputText) {
        return messageDigest(inputText, "sha-1");
    }
    
    /**
     * sha-256加密
     *
     * @param inputText
     * @return
     */
    public static String sha256(String inputText) {
        return messageDigest(inputText, "sha-256");
    }
    
  

    /**
     * md5或者sha-1加密
     *
     * @param inputText 要加密的内容
     * @param algorithmName 加密算法名称：md5或者sha-1，不区分大小写
     * @return
     */
    public static String messageDigest(String inputText, String algorithmName) {
        if (inputText == null || "".equals(inputText.trim())) {
            throw new IllegalArgumentException("plain text is empty!");
        }
        if (algorithmName == null || "".equals(algorithmName.trim())) {
            algorithmName = "md5";
        }
        String encryptText = null;
        try {
            MessageDigest m = MessageDigest.getInstance(algorithmName);
            m.update(inputText.getBytes(DEFAULT_CHARSET));
            byte s[] = m.digest();
            // m.digest(inputText.getBytes("UTF8"));
            return Hex.encodeHexString(s);
        } catch (NoSuchAlgorithmException e) {
            ExceptionUtil.logAndThrow(e);
        }
        return encryptText;
    }
    
      
    /** 
     * 初始化密钥 
     *  
     * @return byte[] 密钥  
     */  
    public static byte[] initAesSecretKey() {  
        //返回生成指定算法的秘密密钥的 KeyGenerator 对象  
        KeyGenerator kg = null;  
        try {  
            kg = KeyGenerator.getInstance(AES);  
        } catch (NoSuchAlgorithmException e) {  
            ExceptionUtil.logAndThrow(e);  
        }  
        //初始化此密钥生成器，使其具有确定的密钥大小  
        //AES 要求密钥长度为 128  
        kg.init(128);  
        //生成一个密钥  
        SecretKey  secretKey = kg.generateKey();  
        return secretKey.getEncoded();  
    }  
    
     /** 
     * 初始化密钥 
     *  
     * @return string 密钥(base64编码)  
     */  
    public static String initAesSecretKeyToBase64() {  
        return Base64.encodeBase64String(initAesSecretKey());  
    }  
    
    public static Long initSecureRandomLong(String seed){
        // 生成一个可信任的随机数源
        SecureRandom sr = new SecureRandom();
        sr.setSeed(System.currentTimeMillis()+seed.hashCode());
        return sr.nextLong();
    }
      
    /** 
     * 转换密钥 
     *  
     * @param key   二进制密钥 
     * @return 密钥 
     */  
    private static Key toAesKey(byte[] key){  
        //生成密钥  
        return new SecretKeySpec(key, AES);  
    }  
     
           
    /** 
     * 加密 
     *  
     * @param data  待加密数据 
     * @param key   密钥(base64编码)  
     * @return byte[]   加密数据 
     * @throws Exception 
     */  
    public static byte[] encryptAes(byte[] data,String key) throws Exception{  
        return encryptAes(data, Base64.decodeBase64(key));
    }
    
          
    /** 
     * 解密 
     *  
     * @param data  待解密数据 
     * @param key   密钥(base64编码)   
     * @return byte[]   解密数据 
     * @throws Exception 
     */  
    public static byte[] decryptAes(byte[] data,String key) throws Exception{ 
        return decryptAes(data, Base64.decodeBase64(key));
    }
    
    /** 
     * 加密 
     *  
     * @param data  待加密数据 
     * @param key   密钥(base64编码)  
     * @return String  加密数据   
     * @throws Exception 
     */  
    public static String encryptStringAes(String data,String key) throws Exception{  
        return Base64.encodeBase64String(encryptAes(data.getBytes(DEFAULT_CHARSET), Base64.decodeBase64(key)));
    }
    
          
    /** 
     * 解密 
     *  
     * @param data  待解密数据 
     * @param key   密钥(base64编码)   
     * @return String   解密数据 (base64编码)   
     * @throws Exception 
     */  
    public static String decryptStringAes(String data,String key) throws Exception{ 
        return new String(decryptAes(Base64.decodeBase64(data), Base64.decodeBase64(key)),DEFAULT_CHARSET);
    }
       
    /** 
     * 加密 
     *  
     * @param data  待加密数据 
     * @param key   二进制密钥 
     * @return byte[]   加密数据 
     * @throws Exception 
     */  
    public static byte[] encryptAes(byte[] data,byte[] key) throws Exception{  
        //还原密钥  
        Key k = toAesKey(key);  
        //实例化  
        Cipher cipher = Cipher.getInstance(DEFAULT_CIPHER_ALGORITHM);  
        //使用密钥初始化，设置为加密模式  
        cipher.init(Cipher.ENCRYPT_MODE, k);  
        //执行操作  
        return cipher.doFinal(data);  
    }  
      
 
      
      
      
    /** 
     * 解密 
     *  
     * @param data  待解密数据 
     * @param key   二进制密钥 
     * @return byte[]   解密数据 
     * @throws Exception 
     */  
    public static byte[] decryptAes(byte[] data,byte[] key) throws Exception{  
        //还原密钥  
        Key k = toAesKey(key);  
        //实例化  
        Cipher cipher = Cipher.getInstance(DEFAULT_CIPHER_ALGORITHM);  
        //使用密钥初始化，设置为解密模式  
        cipher.init(Cipher.DECRYPT_MODE, k);  
        //执行操作  
        return cipher.doFinal(data);  
    }  
      
      
    private static String  showByteArray(byte[] data){  
        if(null == data){  
            return null;  
        }  
        StringBuilder sb = new StringBuilder("{");  
        for(byte b:data){  
            sb.append(b).append(",");  
        }  
        sb.deleteCharAt(sb.length()-1);  
        sb.append("}");  
        return sb.toString();  
    }  

}
