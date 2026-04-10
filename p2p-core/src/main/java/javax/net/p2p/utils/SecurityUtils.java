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
/**
 * SecurityUtils。
 */

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

    /**
     * ============================================================================
     * 使用示例 - 完整演示 SecurityUtils 类的各种功能
     * ============================================================================
     * 
     * <pre>
     * <code>
     * // 示例1: 哈希算法使用示例
     * public void hashExample() {
     *     String text = "Hello World";
     *     
     *     // MD5 哈希
     *     String md5Hash = SecurityUtils.md5(text);
     *     System.out.println("MD5: " + md5Hash); // 输出: b10a8db164e0754105b7a99be72e3fe5
     *     
     *     // SHA-1 哈希
     *     String sha1Hash = SecurityUtils.sha1(text);
     *     System.out.println("SHA-1: " + sha1Hash);
     *     
     *     // SHA-256 哈希
     *     String sha256Hash = SecurityUtils.sha256(text);
     *     System.out.println("SHA-256: " + sha256Hash);
     *     
     *     // 双重加密
     *     String doubleHash = SecurityUtils.md5WithSha256(text);
     *     System.out.println("MD5(SHA-256): " + doubleHash);
     * }
     * 
     * // 示例2: 文件哈希计算
     * public void fileHashExample() throws Exception {
     *     File file = new File("test.txt");
     *     
     *     // 计算文件MD5
     *     String fileMD5 = SecurityUtils.getFileMD5String(file);
     *     System.out.println("文件MD5: " + fileMD5);
     *     
     *     // 计算文件SHA-256
     *     String fileSha256 = SecurityUtils.getFileSha256(file);
     *     System.out.println("文件SHA256: " + fileSha256);
     * }
     * 
     * // 示例3: DES加密解密示例
     * public void desExample() throws Exception {
     *     String plainText = "敏感数据123";
     *     String key = "mySecretKey";
     *     
     *     // DES加密
     *     String encrypted = SecurityUtils.encryptDes(plainText, key);
     *     System.out.println("DES加密结果: " + encrypted);
     *     
     *     // DES解密
     *     String decrypted = SecurityUtils.decryptDes(encrypted, key);
     *     System.out.println("DES解密结果: " + decrypted); // 输出: 敏感数据123
     * }
     * 
     * // 示例4: AES加密解密示例
     * public void aesExample() throws Exception {
     *     String plainText = "高级加密数据";
     *     
     *     // 生成AES密钥
     *     String aesKeyBase64 = SecurityUtils.initAesSecretKeyToBase64();
     *     System.out.println("AES密钥(base64): " + aesKeyBase64);
     *     
     *     // AES加密
     *     String encrypted = SecurityUtils.encryptStringAes(plainText, aesKeyBase64);
     *     System.out.println("AES加密结果: " + encrypted);
     *     
     *     // AES解密
     *     String decrypted = SecurityUtils.decryptStringAes(encrypted, aesKeyBase64);
     *     System.out.println("AES解密结果: " + decrypted); // 输出: 高级加密数据
     * }
     * 
     * // 示例5: 字节数组操作示例
     * public void byteArrayExample() {
     *     String text = "字节数据";
     *     
     *     // 字节数组MD5
     *     String byteMd5 = SecurityUtils.toMD5(text.getBytes());
     *     System.out.println("字节数组MD5: " + byteMd5);
     *     
     *     // 自定义哈希算法
     *     String customHash = SecurityUtils.messageDigest(text, "SHA-384");
     *     System.out.println("SHA-384哈希: " + customHash);
     * }
     * 
     * // 示例6: 安全随机数生成
     * public void secureRandomExample() {
     *     // 生成带种子安全随机数
     *     Long random1 = SecurityUtils.initSecureRandomLong("user123");
     *     System.out.println("安全随机数1: " + random1);
     *     
     *     Long random2 = SecurityUtils.initSecureRandomLong("user456");
     *     System.out.println("安全随机数2: " + random2);
     * }
     * 
     * // 示例7: 综合使用场景 - 文件传输加密
     * public void fileTransferSecurityExample() throws Exception {
     *     File file = new File("confidential.pdf");
     *     
     *     // 1. 计算文件完整性哈希
     *     String fileHash = SecurityUtils.getFileSha256(file);
     *     System.out.println("文件完整性哈希: " + fileHash);
     *     
     *     // 2. 使用AES加密传输密钥
     *     String transmissionKey = SecurityUtils.initAesSecretKeyToBase64();
     *     
     *     // 3. 对文件路径进行DES加密
     *     String filePath = file.getAbsolutePath();
     *     String encryptedPath = SecurityUtils.encryptDes(filePath, "pathKey");
     *     
     *     // 4. 双重验证哈希
     *     String verificationHash = SecurityUtils.md5WithSha256(fileHash + transmissionKey);
     *     
     *     System.out.println("传输密钥: " + transmissionKey);
     *     System.out.println("加密文件路径: " + encryptedPath);
     *     System.out.println("验证哈希: " + verificationHash);
     * }
     * 
     * // 示例8: 密码处理最佳实践
     * public void passwordHandlingExample() {
     *     String userPassword = "user123";
     *     String salt = "randomSalt123";
     *     
     *     // 推荐做法: 密码+盐值进行多次哈希
     *     String passwordWithSalt = userPassword + salt;
     *     String hashedPassword = SecurityUtils.sha256(
     *         SecurityUtils.md5(passwordWithSalt) + salt
     *     );
     *     
     *     System.out.println("加盐哈希密码: " + hashedPassword);
     *     
     *     // 验证密码
     *     String inputPassword = "user123";
     *     String inputWithSalt = inputPassword + salt;
     *     String inputHashed = SecurityUtils.sha256(
     *         SecurityUtils.md5(inputWithSalt) + salt
     *     );
     *     
     *     if (hashedPassword.equals(inputHashed)) {
     *         System.out.println("密码验证成功");
     *     } else {
     *         System.out.println("密码验证失败");
     *     }
     * }
     * 
     * // 示例9: 批量文件哈希验证
     * public void batchFileVerificationExample() {
     *     File[] files = new File("uploads/").listFiles();
     *     if (files != null) {
     *         for (File file : files) {
     *             try {
     *                 String fileMd5 = SecurityUtils.getFileMD5String(file);
     *                 String fileSha256 = SecurityUtils.getFileSha256(file);
     *                 
     *                 System.out.println("文件: " + file.getName());
     *                 System.out.println("  MD5: " + fileMd5);
     *                 System.out.println("  SHA256: " + fileSha256);
     *                 
     *                 // 验证哈希是否匹配预期值
     *                 if (fileMd5.equals("expected_md5_hash_here")) {
     *                     System.out.println("  MD5验证通过");
     *                 }
     *             } catch (Exception e) {
     *                 System.err.println("处理文件失败: " + file.getName() + " - " + e.getMessage());
     *             }
     *         }
     *     }
     * }
     * 
     * // 示例10: 主方法演示全部功能
     * public static void main(String[] args) {
     *     try {
     *         SecurityExample demo = new SecurityExample();
     *         
     *         System.out.println("=== SecurityUtils 使用示例演示 ===");
     *         System.out.println();
     *         
     *         System.out.println("1. 哈希算法演示:");
     *         demo.hashExample();
     *         System.out.println();
     *         
     *         System.out.println("2. 文件哈希演示:");
     *         demo.fileHashExample();
     *         System.out.println();
     *         
     *         System.out.println("3. DES加密演示:");
     *         demo.desExample();
     *         System.out.println();
     *         
     *         System.out.println("4. AES加密演示:");
     *         demo.aesExample();
     *         System.out.println();
     *         
     *         System.out.println("5. 密码处理演示:");
     *         demo.passwordHandlingExample();
     *         
     *     } catch (Exception e) {
     *         e.printStackTrace();
     *     }
     * }
     * </code>
     * </pre>
     * 
     * 注意事项:
     * 1. 对于生产环境，建议使用AES而不是DES，因为DES安全性较弱
     * 2. 文件哈希计算支持大文件，使用NIO内存映射提高性能
     * 3. 密码存储应使用加盐哈希，避免使用纯MD5
     * 4. 密钥管理应使用安全的密钥存储方案
     * 5. 对于大文件，建议使用分块哈希验证
     */
}
