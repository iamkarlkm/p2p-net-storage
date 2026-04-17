package javax.net.p2p.utils;

import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import org.apache.commons.codec.binary.Base64;

/**
 * RSA安全编码组件
 *
 * @author karl
 * @version 1.0
 * @since 1.0
 */
public abstract class RSAUtils {

    public static final String KEY_ALGORITHM = "RSA";
    public static final int KEY_SIZE = 2048;
    public static final String SIGNATURE_ALGORITHM = "MD5withRSA";

    private static final String PUBLIC_KEY = "RSAPublicKey";
    private static final String PRIVATE_KEY = "RSAPrivateKey";
//	static{
//		Security.addProvider(new BouncyCastleProvider());
//	}

    /**
     * 用私钥对信息生成数字签名
     *
     * @param data 加密数据
     * @param privateKey 私钥
     *
     * @return
     * @throws Exception
     */
    public static String sign(byte[] data, String privateKey) throws Exception {
        // 解密由base64编码的私钥
        byte[] keyBytes = Base64.decodeBase64(privateKey);

        // 构造PKCS8EncodedKeySpec对象
        PKCS8EncodedKeySpec pkcs8KeySpec = new PKCS8EncodedKeySpec(keyBytes);

        // KEY_ALGORITHM 指定的加密算法
        KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);

        // 取私钥匙对象
        PrivateKey priKey = keyFactory.generatePrivate(pkcs8KeySpec);

        // 用私钥对信息生成数字签名
        Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initSign(priKey);
        signature.update(data);

        return Base64.encodeBase64String(signature.sign());
    }

    /**
     * 用公钥校验数字签名
     *
     * @param data 加密数据
     * @param publicKey 公钥
     * @param sign 数字签名
     *
     * @return 校验成功返回true 失败返回false
     * @throws Exception
     *
     */
    public static boolean verify(byte[] data, String publicKey, String sign)
            throws Exception {

        // 解密由base64编码的公钥
        byte[] keyBytes = Base64.decodeBase64(publicKey);

        // 构造X509EncodedKeySpec对象
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);

        // KEY_ALGORITHM 指定的加密算法
        KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);

        // 取公钥匙对象
        PublicKey pubKey = keyFactory.generatePublic(keySpec);

        Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initVerify(pubKey);
        signature.update(data);

        // 验证签名是否正常
        return signature.verify(Base64.decodeBase64(sign));
    }

    /**
     * 用私钥解密
     *
     * @param data
     * @param key
     * @return
     * @throws Exception
     */
    public static byte[] decryptByPrivateKey(byte[] data, String key)
            throws Exception {
        // 对密钥解码
        byte[] keyBytes = Base64.decodeBase64(key);

        return decryptByPrivateKey(data, keyBytes);
    }

    /**
     * 用私钥解密
     *
     * @param data
     * @param keyBytes
     * @return
     * @throws Exception
     */
    public static byte[] decryptByPrivateKey(byte[] data, byte[] keyBytes)
            throws Exception {

        // 取得私钥
        PKCS8EncodedKeySpec pkcs8KeySpec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
        Key privateKey = keyFactory.generatePrivate(pkcs8KeySpec);

        // 对数据解密
        Cipher cipher = Cipher.getInstance(keyFactory.getAlgorithm());
        //Cipher cipher = Cipher.getInstance(keyFactory.getAlgorithm(), "BC");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);

        return cipher.doFinal(data);
    }

    /**
     * 用公钥解密
     *
     * @param data
     * @param key
     * @return
     * @throws Exception
     */
    public static byte[] decryptByPublicKey(byte[] data, String key)
            throws Exception {
        // 对密钥解码
        byte[] keyBytes = Base64.decodeBase64(key);

        return decryptByPublicKey(data, keyBytes);
    }

    /**
     * 用公钥解密
     *
     * @param data
     * @param keyBytes
     * @return
     * @throws Exception
     */
    public static byte[] decryptByPublicKey(byte[] data, byte[] keyBytes)
            throws Exception {

        // 取得公钥
        X509EncodedKeySpec x509KeySpec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
        Key publicKey = keyFactory.generatePublic(x509KeySpec);

        // 对数据解密
        Cipher cipher = Cipher.getInstance(keyFactory.getAlgorithm());
        //Cipher cipher = Cipher.getInstance(keyFactory.getAlgorithm(), "BC");
        cipher.init(Cipher.DECRYPT_MODE, publicKey);

        return cipher.doFinal(data);
    }

    /**
     * 用公钥加密
     *
     * @param data
     * @param key
     * @return
     * @throws Exception
     */
    public static byte[] encryptByPublicKey(byte[] data, String key)
            throws Exception {
        // 对公钥解码
        byte[] keyBytes = Base64.decodeBase64(key);
        return encryptByPublicKey(data, keyBytes);
    }

    /**
     * 用公钥加密
     *
     * @param data
     * @param keyBytes
     * @return
     * @throws Exception
     */
    public static byte[] encryptByPublicKey(byte[] data, byte[] keyBytes)
            throws Exception {

        // 取得公钥
        X509EncodedKeySpec x509KeySpec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
        Key publicKey = keyFactory.generatePublic(x509KeySpec);

        // 对数据加密
        Cipher cipher = Cipher.getInstance(keyFactory.getAlgorithm());
        //Cipher cipher = Cipher.getInstance(keyFactory.getAlgorithm(), "BC");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);

        return cipher.doFinal(data);
    }

    /**
     * 用私钥加密
     *
     * @param data
     * @param key
     * @return
     * @throws Exception
     */
    public static byte[] encryptByPrivateKey(byte[] data, String key)
            throws Exception {
        // 对密钥解码
        byte[] keyBytes = Base64.decodeBase64(key);
        return encryptByPrivateKey(data, keyBytes);
    }


    /**
     * 用私钥加密
     *
     * @param data
     * @param keyBytes
     * @return
     * @throws Exception
     */
    public static byte[] encryptByPrivateKey(byte[] data, byte[] keyBytes)
            throws Exception {

        // 取得私钥
        PKCS8EncodedKeySpec pkcs8KeySpec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
        Key privateKey = keyFactory.generatePrivate(pkcs8KeySpec);

        // 对数据加密
        Cipher cipher = Cipher.getInstance(keyFactory.getAlgorithm());
        //Cipher cipher = Cipher.getInstance(keyFactory.getAlgorithm(), "BC");
        cipher.init(Cipher.ENCRYPT_MODE, privateKey);

        return cipher.doFinal(data);
    }

    /**
     * 取得私钥
     *
     * @param keyMap
     * @return
     * @throws Exception
     */
    public static String getPrivateKey(Map<String, Object> keyMap)
            throws Exception {
        Key key = (Key) keyMap.get(PRIVATE_KEY);
        return Base64.encodeBase64String(key.getEncoded());
    }

    /**
     * 取得公钥
     *
     * @param keyMap
     * @return
     * @throws Exception
     */
    public static String getPublicKey(Map<String, Object> keyMap)
            throws Exception {
        Key key = (Key) keyMap.get(PUBLIC_KEY);
        return Base64.encodeBase64String(key.getEncoded());
    }

    /**
     * 取得公钥
     *
     * @param keyMap
     * @return
     * @throws Exception
     */
    public static byte[] getPublicKeyBytes(Map<String, Object> keyMap)
            throws Exception {
        Key key = (Key) keyMap.get(PUBLIC_KEY);
        return key.getEncoded();
    }

    /**
     * 取得私钥
     *
     * @param keyMap
     * @return
     * @throws Exception
     */
    public static byte[] getPrivateKeyBytes(Map<String, Object> keyMap)
            throws Exception {
        Key key = (Key) keyMap.get(PRIVATE_KEY);
        return key.getEncoded();
    }

    /**
     * 初始化密钥
     *
     * @return
     * @throws Exception
     */
    public static Map<String, Object> initKey() throws Exception {
        KeyPairGenerator keyPairGen = KeyPairGenerator
                .getInstance(KEY_ALGORITHM);
        keyPairGen.initialize(KEY_SIZE);

        KeyPair keyPair = keyPairGen.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();

        Map<String, Object> keyMap = new HashMap<String, Object>(2);
        keyMap.put(PUBLIC_KEY, publicKey);
        keyMap.put(PRIVATE_KEY, privateKey);

        return keyMap;
    }

    public static void main(String[] args) throws Exception {
        Map<String, Object> keyMap = RSAUtils.initKey();

        String publicKey = RSAUtils.getPublicKey(keyMap);
        String privateKey = RSAUtils.getPrivateKey(keyMap);
        System.out.println("公钥: \n\r" + publicKey);
        System.out.println("私钥： \n\r" + privateKey);

        System.out.println("公钥加密——私钥解密");
        String inputStr = "abcabcabcabcabcabcabcabcabcabcabcabc";
        byte[] data = inputStr.getBytes();

        byte[] encodedData = RSAUtils.encryptByPublicKey(data, publicKey);

        byte[] decodedData = RSAUtils.decryptByPrivateKey(encodedData,
                privateKey);

        String outputStr = new String(decodedData);
        System.out.println("加密前: " + inputStr + "\n\r" + "解密后: " + outputStr);

        System.err.println("私钥加密——公钥解密");
        inputStr = "sign";
        data = inputStr.getBytes();

        encodedData = RSAUtils.encryptByPrivateKey(data, privateKey);

        decodedData = RSAUtils
                .decryptByPublicKey(encodedData, publicKey);

        outputStr = new String(decodedData);
        System.out.println("加密前: " + inputStr + "\n\r" + "解密后: " + outputStr);

        System.out.println("私钥签名——公钥验证签名");
        // 产生签名
        String sign = RSAUtils.sign(encodedData, privateKey);
        System.out.println("签名:\r" + sign);

        // 验证签名
        boolean status = RSAUtils.verify(encodedData, publicKey, sign);
        System.out.println("状态:\r" + status);
    }

    /**
     * ============================================================================
     * 使用示例 - 完整演示 RSAUtils 类的各种功能
     * ============================================================================
     * 
     * <pre>
     * <code>
     * // 示例1: 基本RSA加密解密
     * public void basicRSAExample() throws Exception {
     *     // 生成RSA密钥对
     *     Map<String, Object> keyMap = RSAUtils.initKey();
     *     String publicKey = RSAUtils.getPublicKey(keyMap);
     *     String privateKey = RSAUtils.getPrivateKey(keyMap);
     *     
     *     System.out.println("RSA密钥生成:");
     *     System.out.println("  公钥(Base64): " + publicKey.substring(0, 50) + "...");
     *     System.out.println("  私钥(Base64): " + privateKey.substring(0, 50) + "...");
     *     
     *     // 要加密的数据
     *     String originalText = "敏感业务数据: 用户ID=12345, 金额=1000.00";
     *     byte[] data = originalText.getBytes("UTF-8");
     *     
     *     // 公钥加密
     *     byte[] encryptedData = RSAUtils.encryptByPublicKey(data, publicKey);
     *     System.out.println("公钥加密完成，密文长度: " + encryptedData.length + " bytes");
     *     
     *     // 私钥解密
     *     byte[] decryptedData = RSAUtils.decryptByPrivateKey(encryptedData, privateKey);
     *     String decryptedText = new String(decryptedData, "UTF-8");
     *     System.out.println("私钥解密完成，原文: " + decryptedText);
     *     
     *     // 验证数据完整性
     *     if (originalText.equals(decryptedText)) {
     *         System.out.println("数据完整性验证: 通过");
     *     } else {
     *         System.out.println("数据完整性验证: 失败");
     *     }
     * }
     * 
     * // 示例2: 数字签名和验证

     * public void digitalSignatureExample() throws Exception {
     *     // 生成密钥对
     *     Map<String, Object> keyMap = RSAUtils.initKey();
     *     String publicKey = RSAUtils.getPublicKey(keyMap);
     *     String privateKey = RSAUtils.getPrivateKey(keyMap);
     *     
     *     // 需要签名的数据
     *     String importantData = "重要合同条款: 项目名称=XXX软件开发, 金额=50000.00";
     *     byte[] data = importantData.getBytes("UTF-8");
     *     
     *     System.out.println("数字签名示例:");
     *     System.out.println("  原始数据: " + importantData);
     *     
     *     // 使用私钥生成数字签名
     *     String signature = RSAUtils.sign(data, privateKey);
     *     System.out.println("  数字签名(Base64): " + signature.substring(0, 50) + "...");
     *     
     *     // 使用公钥验证签名
     *     boolean isValid = RSAUtils.verify(data, publicKey, signature);
     *     System.out.println("  签名验证结果: " + (isValid ? "有效" : "无效"));
     *     
     *     // 模拟数据被篡改的情况
     *     String tamperedData = "重要合同条款: 项目名称=XXX软件开发, 金额=100000.00";
     *     byte[] tamperedBytes = tamperedData.getBytes("UTF-8");
     *     boolean isTamperedValid = RSAUtils.verify(tamperedBytes, publicKey, signature);
     *     System.out.println("  篡改数据验证结果: " + (isTamperedValid ? "异常" : "正常"));
     * }
     * 
     * // 示例3: 双向加密解密
     * public void bidirectionalEncryptionExample() throws Exception {
     *     // 生成两对密钥（模拟客户端和服务器）
     *     Map<String, Object> clientKeyMap = RSAUtils.initKey();
     *     String clientPublicKey = RSAUtils.getPublicKey(clientKeyMap);
     *     String clientPrivateKey = RSAUtils.getPrivateKey(clientKeyMap);
     *     
     *     Map<String, Object> serverKeyMap = RSAUtils.initKey();
     *     String serverPublicKey = RSAUtils.getPublicKey(serverKeyMap);
     *     String serverPrivateKey = RSAUtils.getPrivateKey(serverKeyMap);
     *     
     *     System.out.println("双向加密通信示例:");
     *     System.out.println("  客户端公钥长度: " + clientPublicKey.length());
     *     System.out.println("  服务器公钥长度: " + serverPublicKey.length());
     *     
     *     // 客户端加密数据（使用服务器公钥）
     *     String clientMessage = "客户端请求: GET /api/data";
     *     byte[] clientEncrypted = RSAUtils.encryptByPublicKey(
     *         clientMessage.getBytes("UTF-8"), serverPublicKey);
     *     
     *     // 服务器解密数据（使用服务器私钥）
     *     byte[] serverDecrypted = RSAUtils.decryptByPrivateKey(clientEncrypted, serverPrivateKey);
     *     String serverReceived = new String(serverDecrypted, "UTF-8");
     *     System.out.println("  客户端发送: " + clientMessage);
     *     System.out.println("  服务器接收: " + serverReceived);
     *     
     *     // 服务器加密响应（使用客户端公钥）
     *     String serverResponse = "服务器响应: {\"status\":\"success\",\"data\":{...}}";
     *     byte[] serverEncrypted = RSAUtils.encryptByPublicKey(
     *         serverResponse.getBytes("UTF-8"), clientPublicKey);
     *     
     *     // 客户端解密响应（使用客户端私钥）
     *     byte[] clientDecrypted = RSAUtils.decryptByPrivateKey(serverEncrypted, clientPrivateKey);
     *     String clientReceived = new String(clientDecrypted, "UTF-8");
     *     System.out.println("  服务器响应: " + serverResponse);
     *     System.out.println("  客户端接收: " + clientReceived);
     * }
     * 
     * // 示例4: 密钥管理最佳实践
     * public void keyManagementExample() throws Exception {
     *     // 生成密钥对
     *     Map<String, Object> keyMap = RSAUtils.initKey();
     *     
     *     // 获取密钥字节数组
     *     byte[] publicKeyBytes = RSAUtils.getPublicKeyBytes(keyMap);
     *     byte[] privateKeyBytes = RSAUtils.getPrivateKeyBytes(keyMap);
     *     
     *     System.out.println("密钥管理示例:");
     *     System.out.println("  公钥字节长度: " + publicKeyBytes.length);
     *     System.out.println("  私钥字节长度: " + privateKeyBytes.length);
     *     
     *     // 保存密钥到文件（模拟）
     *     saveKeyToFile("public_key.pem", publicKeyBytes);
     *     saveKeyToFile("private_key.pem", privateKeyBytes);
     *     
     *     // 从文件加载密钥（模拟）
     *     byte[] loadedPublicKey = loadKeyFromFile("public_key.pem");
     *     byte[] loadedPrivateKey = loadKeyFromFile("private_key.pem");
     *     
     *     // 使用加载的密钥进行加密解密
     *     String testData = "测试数据";
     *     
     *     // 加密

     *     byte[] encrypted = RSAUtils.encryptByPublicKey(
     *         testData.getBytes("UTF-8"), loadedPublicKey);
     *     
     *     // 解密
     *     byte[] decrypted = RSAUtils.decryptByPrivateKey(encrypted, loadedPrivateKey);
     *     String result = new String(decrypted, "UTF-8");
     *     
     *     System.out.println("  原始数据: " + testData);
     *     System.out.println("  解密结果: " + result);
     *     System.out.println("  验证: " + (testData.equals(result) ? "通过" : "失败"));
     * }
     * 
     * // 示例5: 数据完整性验证
     * public void dataIntegrityExample() throws Exception {
     *     // 生成密钥对
     *     Map<String, Object> keyMap = RSAUtils.initKey();
     *     String publicKey = RSAUtils.getPublicKey(keyMap);
     *     String privateKey = RSAUtils.getPrivateKey(keyMap);
     *     
     *     // 准备数据
     *     String transactionData = "交易数据: {from: \"user123\", to: \"user456\", amount: 1000}";
     *     byte[] data = transactionData.getBytes("UTF-8");
     *     
     *     System.out.println("数据完整性验证示例:");
     *     
     *     // 方案1: 先加密后签名
     *     byte[] encrypted = RSAUtils.encryptByPublicKey(data, publicKey);
     *     String signature1 = RSAUtils.sign(encrypted, privateKey);
     *     
     *     System.out.println("  方案1 - 先加密后签名:");
     *     System.out.println("    密文长度: " + encrypted.length + " bytes");
     *     System.out.println("    签名长度: " + signature1.length() + " chars");
     *     
     *     // 验证

     *     boolean verify1 = RSAUtils.verify(encrypted, publicKey, signature1);
     *     System.out.println("    签名验证: " + (verify1 ? "有效" : "无效"));
     *     
     *     // 方案2: 先签名后加密
     *     String signature2 = RSAUtils.sign(data, privateKey);
     *     byte[] combinedData = combineDataAndSignature(data, signature2);
     *     byte[] encryptedCombined = RSAUtils.encryptByPublicKey(combinedData, publicKey);
     *     
     *     System.out.println("  方案2 - 先签名后加密:");
     *     System.out.println("    密文长度: " + encryptedCombined.length + " bytes");
     *     
     *     // 解密并验证
     *     byte[] decryptedCombined = RSAUtils.decryptByPrivateKey(encryptedCombined, privateKey);
     *     byte[][] separated = separateDataAndSignature(decryptedCombined);
     *     boolean verify2 = RSAUtils.verify(separated[0], publicKey, new String(separated[1], "UTF-8"));
     *     System.out.println("    签名验证: " + (verify2 ? "有效" : "无效"));
     * }
     * 
     * // 示例6: 实际应用场景 - 安全文件传输
     * public void secureFileTransferExample() throws Exception {
     *     System.out.println("安全文件传输示例:");
     *     
     *     // 模拟发送方
     *     Map<String, Object> senderKeyMap = RSAUtils.initKey();
     *     String senderPublicKey = RSAUtils.getPublicKey(senderKeyMap);
     *     String senderPrivateKey = RSAUtils.getPrivateKey(senderKeyMap);
     *     
     *     // 模拟接收方
     *     Map<String, Object> receiverKeyMap = RSAUtils.initKey();
     *     String receiverPublicKey = RSAUtils.getPublicKey(receiverKeyMap);
     *     String receiverPrivateKey = RSAUtils.getPrivateKey(receiverKeyMap);
     *     
     *     // 文件数据
     *     String fileContent = "敏感文件内容: 财务报表，机密客户信息...";
     *     byte[] fileData = fileContent.getBytes("UTF-8");
     *     
     *     System.out.println("  发送方操作:");
     *     System.out.println("    原始文件大小: " + fileData.length + " bytes");
     *     
     *     // 1. 对文件数据进行数字签名
     *     String fileSignature = RSAUtils.sign(fileData, senderPrivateKey);
     *     
     *     // 2. 使用接收方公钥加密（文件数据 + 签名）
     *     byte[] combined = combineDataAndSignature(fileData, fileSignature);
     *     byte[] encryptedFile = RSAUtils.encryptByPublicKey(combined, receiverPublicKey);
     *     
     *     System.out.println("    加密后文件大小: " + encryptedFile.length + " bytes");
     *     
     *     // 模拟传输...
     *     
     *     System.out.println("  接收方操作:");
 *     
     *     // 3. 接收方使用私钥解密
     *     byte[] decrypted = RSAUtils.decryptByPrivateKey(encryptedFile, receiverPrivateKey);
     *     
     *     // 4. 分离数据和签名

     *     byte[][] separated = separateDataAndSignature(decrypted);
     *     byte[] receivedData = separated[0];
     *     String receivedSignature = new String(separated[1], "UTF-8");
 *     
     *     // 5. 使用发送方公钥验证签名
     *     boolean isSignatureValid = RSAUtils.verify(receivedData, senderPublicKey, receivedSignature);
 *     
     *     System.out.println("    签名验证: " + (isSignatureValid ? "通过" : "失败"));
 *     
     *     if (isSignatureValid) {
 *         String receivedContent = new String(receivedData, "UTF-8");
 *         System.out.println("    接收的文件内容: " + receivedContent);
 *         System.out.println("    数据完整性: " + (fileContent.equals(receivedContent) ? "完整" : "损坏"));
 *     } else {
 *         System.out.println("    警告: 文件可能被篡改或来源不可信");
 *     }
 * }
 * 
 * // 辅助方法
 * private static byte[] combineDataAndSignature(byte[] data, String signature) {
 *     byte[] signatureBytes = signature.getBytes();
 *     byte[] combined = new byte[data.length + signatureBytes.length + 4];
 *     
 *     // 存储数据长度（4字节）
 *     combined[0] = (byte)((data.length >> 24) & 0xFF);
 *     combined[1] = (byte)((data.length >> 16) & 0xFF);
 *     combined[2] = (byte)((data.length >> 8) & 0xFF);
 *     combined[3] = (byte)(data.length & 0xFF);
 *     
 *     // 存储数据
 *     System.arraycopy(data, 0, combined, 4, data.length);
 *     
 *     // 存储签名
 *     System.arraycopy(signatureBytes, 0, combined, 4 + data.length, signatureBytes.length);
 *     
 *     return combined;
 * }
 * 
 * private static byte[][] separateDataAndSignature(byte[] combined) {
 *     // 解析数据长度
 *     int dataLength = ((combined[0] & 0xFF) << 24) |
 *                      ((combined[1] & 0xFF) << 16) |
 *                      ((combined[2] & 0xFF) << 8) |
 *                      (combined[3] & 0xFF);
 *     
 *     // 提取数据
 *     byte[] data = new byte[dataLength];
 *     System.arraycopy(combined, 4, data, 0, dataLength);
 *     
 *     // 提取签名

 *     int signatureLength = combined.length - dataLength - 4;
 *     byte[] signatureBytes = new byte[signatureLength];
 *     System.arraycopy(combined, 4 + dataLength, signatureBytes, 0, signatureLength);
 *     
 *     return new byte[][]{data, signatureBytes};
 * }
 * 
 * private static void saveKeyToFile(String filename, byte[] keyData) {
 *     // 模拟保存密钥到文件
 *     System.out.println("    [模拟] 保存密钥到文件: " + filename + " (" + keyData.length + " bytes)");
 * }
 * 
 * private static byte[] loadKeyFromFile(String filename) {
 *     // 模拟从文件加载密钥
 *     System.out.println("    [模拟] 从文件加载密钥: " + filename);
 *     return "模拟密钥数据".getBytes();
 * }
 * 
 * // 示例7: 主方法演示
 * public static void main(String[] args) {
 *     try {
 *         RSAExample demo = new RSAExample();
 *         
 *         System.out.println("=== RSAUtils 使用示例演示 ===");
 *         System.out.println();
 *         
 *         System.out.println("1. 基本RSA操作演示:");
 *         demo.basicRSAExample();
 *         System.out.println();
 *         
 *         System.out.println("2. 数字签名演示:");
 *         demo.digitalSignatureExample();
 *         System.out.println();
 *         
 *         System.out.println("3. 安全文件传输演示:");
 *         demo.secureFileTransferExample();
 *         
 *     } catch (Exception e) {
 *         e.printStackTrace();
 *     }
 * }
 * </code>
 * </pre>
 * 
 * 注意事项:
 * 1. RSA加密解密对数据长度有限制，通常需要分段加密大文件
 * 2. 私钥必须严格保密，绝不能泄露
 * 3. 公钥可以公开分发，用于加密和验证签名
 * 4. 数字签名可以证明数据来源和完整性
 * 5. 建议使用2048位或以上长度的密钥以保证安全性
 * 6. 密钥应该定期更换，特别是用于重要数据的密钥
 * 7. 生产环境建议使用硬件安全模块（HSM）存储密钥
 * </code>
 * </pre>
 */}
