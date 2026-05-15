---
updated: "2026-05-11T12:56:14Z"
---
# 禁止证书链守门（p2p-core）

## Intent

- 项目鉴权坚持“RSA 公私钥对”方案，明确禁止引入证书链/CA/PKI/Keystore 之类体系。
- 允许底层协议（如 QUIC/TLS）使用自签名证书或不校验配置，但不允许演进为证书链校验与管理。

## What Changed

- 新增 `NoCertificateChainUsageTest`：扫描 `p2p-core/src/main/java/javax/net/p2p` 下源码，若出现 `KeyStore/PKCS12/TrustManagerFactory/X509TrustManager/CertificateFactory/...` 等关键词则测试失败。

## Verification

- `mvn -q -pl p2p-core "-Dtest=javax.net.p2p.security.NoCertificateChainUsageTest" test`
