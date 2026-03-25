package javax.net.p2p.security;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

/**
 * RSA密钥对管理器
 *
 * 功能说明：
 * 1. 生成RSA密钥对
 * 2. 保存和加载密钥
 * 3. 密钥验证
 *
 * @author IM System
 * @version 1.0
 * @since 2026
 */
@Slf4j
@Data
public class RSAKeyPair {

    /** 节点ID */
    private String nodeId;

    /** 公钥 */
    private String publicKey;

    /** 私钥 */
    private String privateKey;

    /** 密钥对 */
    private KeyPair keyPair;

    public RSAKeyPair() {
        this.nodeId = UUID.randomUUID().toString();
        this.generateKeyPair();
    }

    public RSAKeyPair(String nodeId) {
        this.nodeId = nodeId;
        this.generateKeyPair();
    }

    public RSAKeyPair(String nodeId, String publicKey, String privateKey) throws Exception {
        this.nodeId = nodeId;
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.loadKeyPair();
    }

    /**
     * 生成RSA密钥对
     */
    private void generateKeyPair() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            this.keyPair = keyPairGenerator.generateKeyPair();

            this.publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
            this.privateKey = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());

            log.info("生成RSA密钥对成功: nodeId={}", nodeId);

        } catch (NoSuchAlgorithmException e) {
            log.error("生成RSA密钥对失败", e);
            throw new RuntimeException("生成RSA密钥对失败", e);
        }
    }

    /**
     * 从字符串加载密钥对
     */
    private void loadKeyPair() throws Exception {
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");

        // 加载公钥
        byte[] publicKeyBytes = Base64.getDecoder().decode(publicKey);
        X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(publicKeyBytes);
        PublicKey pubKey = keyFactory.generatePublic(publicKeySpec);

        // 加载私钥
        byte[] privateKeyBytes = Base64.getDecoder().decode(privateKey);
        PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
        PrivateKey privKey = keyFactory.generatePrivate(privateKeySpec);

        this.keyPair = new KeyPair(pubKey, privKey);
        log.info("加载RSA密钥对成功: nodeId={}", nodeId);
    }

    /**
     * 获取公钥对象
     */
    public PublicKey getPublicKeyObject() {
        return keyPair != null ? keyPair.getPublic() : null;
    }

    /**
     * 获取私钥对象
     */
    public PrivateKey getPrivateKeyObject() {
        return keyPair != null ? keyPair.getPrivate() : null;
    }

    /**
     * 验证密钥对是否有效
     */
    public boolean isValid() {
        return keyPair != null && publicKey != null && privateKey != null;
    }
}
