# p2p-net-storage 工具类使用示例文档

## 概述

本文档提供项目中重要工具类的详细使用示例，帮助开发者快速理解和使用这些工具类。

## 目录

1. [SecurityUtils - 安全工具类](#securityutils)
2. [SerializationUtil - 序列化工具类](#serializationutil)
3. [FileUtil - 文件操作工具类](#fileutil)
4. [DateUtil - 日期时间工具类](#dateutil)
5. [RSAUtils - RSA加密工具类](#rsautils)
6. [MathUtil - 数学计算工具类](#mathutil)
7. [其他重要工具类](#其他重要工具类)

---

## SecurityUtils

### 功能概述
提供MD5、SHA1、SHA256、AES、DES等加密解密功能，支持文件哈希计算。

### 核心方法
- `md5(String inputText)` - MD5哈希
- `sha256(String inputText)` - SHA-256哈希
- `encryptDes(String data, String key)` - DES加密
- `decryptDes(String data, String key)` - DES解密
- `encryptStringAes(String data, String key)` - AES加密
- `decryptStringAes(String data, String key)` - AES解密
- `getFileMD5String(File file)` - 文件MD5计算
- `getFileSha256(File file)` - 文件SHA-256计算

### 使用示例

```java
// 1. 哈希计算
String text = "Hello World";
String md5Hash = SecurityUtils.md5(text);
String sha256Hash = SecurityUtils.sha256(text);

// 2. AES加密解密
String plainText = "敏感数据";
String aesKey = SecurityUtils.initAesSecretKeyToBase64();
String encrypted = SecurityUtils.encryptStringAes(plainText, aesKey);
String decrypted = SecurityUtils.decryptStringAes(encrypted, aesKey);

// 3. 文件完整性验证
File file = new File("important.pdf");
String fileHash = SecurityUtils.getFileSha256(file);
System.out.println("文件哈希: " + fileHash);

// 4. 密码处理（加盐）
String password = "user123";
String salt = "randomSalt";
String passwordWithSalt = password + salt;
String hashedPassword = SecurityUtils.sha256(
    SecurityUtils.md5(passwordWithSalt) + salt
);
```

### 注意事项
- 对于生产环境，建议使用AES而不是DES
- 密码存储应使用加盐哈希
- 文件哈希支持大文件，使用NIO提高性能

---

## SerializationUtil

### 功能概述
基于Protostuff的高性能序列化工具类，支持ByteBuf操作，专为Netty网络通信设计。

### 核心方法
- `serialize(Object obj)` - 序列化到字节数组
- `deserialize(Class<T> clazz, byte[] data)` - 从字节数组反序列化
- `serializeToByteBuf(Object obj, int magic)` - 序列化到ByteBuf（带协议标识）
- `deserialize(Class<T> clazz, ByteBuf in, int magic)` - 从ByteBuf反序列化

### 使用示例

```java
// 1. 基本对象序列化
User user = new User("张三", 30);
byte[] serializedData = SerializationUtil.serialize(user);
User deserializedUser = SerializationUtil.deserialize(User.class, serializedData);

// 2. 集合序列化
List<String> nameList = Arrays.asList("张三", "李四", "王五");
byte[] listData = SerializationUtil.serialize(nameList);
List<String> deserializedList = SerializationUtil.deserialize(ArrayList.class, listData);

// 3. Netty ByteBuf序列化（带协议验证）
FileInfo fileInfo = new FileInfo("test.pdf", 1024 * 1024);
int protocolMagic = 0x12345678;
ByteBuf serializedBuf = SerializationUtil.serializeToByteBuf(fileInfo, protocolMagic);

// 接收端反序列化
FileInfo receivedInfo = SerializationUtil.deserialize(
    FileInfo.class, serializedBuf, protocolMagic
);

// 4. 高性能序列化（直接缓冲区）
List<DataPoint> dataPoints = new ArrayList<>();
for (int i = 0; i < 10000; i++) {
    dataPoints.add(new DataPoint(i, Math.random()));
}
ByteBuf directBuffer = SerializationUtil.serializeToByteBuf(dataPoints);
```

### 注意事项
- 所有需要序列化的类必须有无参构造函数
- ByteBuf使用后必须正确释放资源
- 协议标识用于验证数据完整性和版本控制

---

## FileUtil

### 功能概述
文件操作工具类，支持文件锁定、分块读写、断点续传、安全沙箱访问等功能。

### 核心方法
- `loadFile(File file)` - 加载整个文件
- `loadFile(File file, long start, long count)` - 加载文件分块
- `storeFile(File file, long start, long count, byte[] data)` - 存储文件分块
- `getLockedFileChannel(String path)` - 获取文件锁
- `getDownInfo(File file)` - 获取下载进度信息
- `getAndCheckExistsSandboxFile(int storeId, String path)` - 安全沙箱文件访问

### 使用示例

```java
// 1. 基本文件读写
File testFile = new File("test.txt");
String content = "测试内容";
byte[] data = content.getBytes("UTF-8");
FileUtil.storeFile(testFile, 0, data.length, data);
byte[] readData = FileUtil.loadFile(testFile);

// 2. 文件锁定和并发控制
String configFile = "shared_config.json";
FileLock fileLock = FileUtil.getLockedFileChannel(configFile);
if (fileLock != null) {
    try {
        String config = "{ \"server\": \"localhost\" }";
        FileUtil.concurentAppend(fileLock, config.getBytes("UTF-8"));
    } finally {
        fileLock.release();
    }
}

// 3. 断点续传
File targetFile = new File("downloads/video.mp4");
Triple<File, File, Set<Integer>> downInfo = FileUtil.getDownInfo(targetFile);
Set<Integer> completedBlocks = downInfo.getRight();
System.out.println("已完成块数: " + completedBlocks.size());

// 4. 安全沙箱访问
int storeId = 1;
String relativePath = "user_docs/report.pdf";
File sandboxFile = FileUtil.getAndCheckExistsSandboxFile(storeId, relativePath);
byte[] fileData = FileUtil.loadFile(sandboxFile);
```

### 注意事项
- 文件锁定可以防止并发访问导致的数据不一致
- 断点续传依赖索引文件记录进度
- 沙箱文件访问确保不会越界访问系统文件

---

## DateUtil

### 功能概述
日期时间处理工具类，支持多种格式的日期解析、格式化、日期差计算、模糊日期处理等。

### 核心方法
- `parse(String text)` - 智能解析多种日期格式
- `format(Date date)` - 格式化日期
- `getBetweenDays(Date start, Date end)` - 计算天数差
- `getHumanReadingDateDiff(Date now, Date old)` - 人类可读时间差
- `getMinTimeByFuzzyDateString(String str)` - 模糊日期最小时间
- `getMaxTimeByFuzzyDateString(String str)` - 模糊日期最大时间
- `isToday(Date date)` - 判断是否为今天
- `isYesterday(Date date)` - 判断是否为昨天

### 使用示例

```java
// 1. 智能日期解析
Date date1 = DateUtil.parse("2023-12-25");                 // 标准日期
Date date2 = DateUtil.parse("2023-12-25 14:30:00");        // 日期时间
Date date3 = DateUtil.parse("12/25/2023 14:30:00");        // 美式格式
Date date4 = DateUtil.parse("20231225143000000");          // 模糊格式
Date date5 = DateUtil.parse("1703511000000");              // 时间戳

// 2. 日期差计算
SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
Date startDate = sdf.parse("2023-12-25 10:00:00");
Date endDate = sdf.parse("2023-12-27 14:30:45");
int daysBetween = DateUtil.getBetweenDays(startDate, endDate);
String humanReadable = DateUtil.getHumanReadingDateDiff(endDate, startDate);

// 3. 模糊日期查询
String fuzzyDate = "202312"; // 2023年12月
Date minTime = DateUtil.getMinTimeByFuzzyDateString(fuzzyDate);
Date maxTime = DateUtil.getMaxTimeByFuzzyDateString(fuzzyDate);
System.out.println("查询范围: " + DateUtil.format(minTime) + " ~ " + DateUtil.format(maxTime));

// 4. 日期判断
Date today = new Date();
Date yesterday = new Date(today.getTime() - 24 * 60 * 60 * 1000);
boolean isToday = DateUtil.isToday(today);
boolean isYesterday = DateUtil.isYesterday(yesterday);
```

### 注意事项
- 支持多种日期格式自动识别
- 模糊日期处理适用于按年、月、日等维度查询
- 人类可读时间格式化适用于日志、报告等场景

---

## RSAUtils

### 功能概述
RSA非对称加密工具类，支持密钥生成、加密解密、数字签名验证等功能。

### 核心方法
- `initKey()` - 生成RSA密钥对
- `encryptByPublicKey(byte[] data, String key)` - 公钥加密
- `decryptByPrivateKey(byte[] data, String key)` - 私钥解密
- `sign(byte[] data, String privateKey)` - 数字签名
- `verify(byte[] data, String publicKey, String sign)` - 验证签名

### 使用示例

```java
// 1. 密钥生成和基本加密
Map<String, Object> keyMap = RSAUtils.initKey();
String publicKey = RSAUtils.getPublicKey(keyMap);
String privateKey = RSAUtils.getPrivateKey(keyMap);

String originalText = "敏感数据";
byte[] encrypted = RSAUtils.encryptByPublicKey(
    originalText.getBytes("UTF-8"), publicKey
);
byte[] decrypted = RSAUtils.decryptByPrivateKey(encrypted, privateKey);

// 2. 数字签名和验证
String importantData = "重要合同条款";
byte[] data = importantData.getBytes("UTF-8");
String signature = RSAUtils.sign(data, privateKey);
boolean isValid = RSAUtils.verify(data, publicKey, signature);

// 3. 双向加密通信（客户端-服务器）
// 客户端使用服务器公钥加密
String clientMessage = "客户端请求";
byte[] clientEncrypted = RSAUtils.encryptByPublicKey(
    clientMessage.getBytes("UTF-8"), serverPublicKey
);

// 服务器使用服务器私钥解密
byte[] serverDecrypted = RSAUtils.decryptByPrivateKey(
    clientEncrypted, serverPrivateKey
);

// 4. 安全文件传输（签名+加密）
// 发送方：对文件数据签名，然后用接收方公钥加密
String fileContent = "机密文件内容";
byte[] fileData = fileContent.getBytes("UTF-8");
String fileSignature = RSAUtils.sign(fileData, senderPrivateKey);
byte[] combined = combineDataAndSignature(fileData, fileSignature);
byte[] encryptedFile = RSAUtils.encryptByPublicKey(combined, receiverPublicKey);

// 接收方：解密后验证签名
byte[] decrypted = RSAUtils.decryptByPrivateKey(encryptedFile, receiverPrivateKey);
byte[][] separated = separateDataAndSignature(decrypted);
boolean isSignatureValid = RSAUtils.verify(
    separated[0], senderPublicKey, new String(separated[1], "UTF-8")
);
```

### 注意事项
- RSA加密对数据长度有限制，需要分段处理大文件
- 私钥必须严格保密
- 建议使用2048位或以上长度的密钥
- 数字签名可以证明数据来源和完整性

---

## MathUtil

### 功能概述
精确的浮点数运算工具类，使用BigDecimal避免浮点数精度问题。

### 核心方法
- `add(double v1, double v2)` - 加法
- `sub(double v1, double v2)` - 减法
- `mul(double v1, double v2)` - 乘法
- `div(double v1, double v2, int scale)` - 除法（指定精度）
- `round(double v, int scale)` - 四舍五入

### 使用示例

```java
// 1. 精确的浮点数运算
double d1 = 0.1;
double d2 = 0.2;

// 错误的浮点数运算
double wrongResult = d1 + d2; // 结果是 0.30000000000000004

// 正确的精确运算
double correctResult = MathUtil.add(d1, d2); // 结果是 0.3

// 2. 财务计算
double price = 19.99;
int quantity = 3;
double discount = 0.1; // 10%折扣

// 计算总价（含折扣）
double subtotal = MathUtil.mul(price, quantity);
double discountAmount = MathUtil.mul(subtotal, discount);
double total = MathUtil.sub(subtotal, discountAmount);
double roundedTotal = MathUtil.round(total, 2); // 保留2位小数

System.out.println("单价: " + price);
System.out.println("数量: " + quantity);
System.out.println("小计: " + subtotal);
System.out.println("折扣: " + discountAmount);
System.out.println("总计: " + roundedTotal);

// 3. 百分比计算
double originalValue = 100.0;
double percentage = 15.5; // 15.5%
double percentageValue = MathUtil.div(
    MathUtil.mul(originalValue, percentage), 100, 2
);
double newValue = MathUtil.add(originalValue, percentageValue);

// 4. 平均值计算
double[] values = {10.1, 20.2, 30.3, 40.4, 50.5};
double sum = 0.0;
for (double value : values) {
    sum = MathUtil.add(sum, value);
}
double average = MathUtil.div(sum, values.length, 2);

// 5. 复杂公式计算
// 计算复利：A = P(1 + r/n)^(nt)
double principal = 10000.0;    // 本金
double rate = 0.05;           // 年利率5%
int timesPerYear = 12;        // 每月复利
int years = 10;               // 10年

double ratePerPeriod = MathUtil.div(rate, timesPerYear, 6);
double onePlusRate = MathUtil.add(1.0, ratePerPeriod);
double exponent = timesPerYear * years;

// 使用循环计算幂（BigDecimal.pow不支持double指数）
BigDecimal base = new BigDecimal(onePlusRate.toString());
BigDecimal result = base.pow((int)exponent);
double finalAmount = result.multiply(new BigDecimal(principal.toString()))
                         .doubleValue();

System.out.println("复利计算结果: " + finalAmount);
```

### 注意事项
- 财务计算必须使用精确的十进制运算
- 除法运算需要指定精度和舍入模式
- 避免直接使用double进行财务计算

---

## 其他重要工具类

### 1. ExceptionUtil
异常处理工具类，提供统一的异常记录和转换功能。

```java
try {
    // 可能抛出异常的代码
    riskyOperation();
} catch (Exception e) {
    // 记录异常并转换为运行时异常
    ExceptionUtil.logAndThrow(e);
    
    // 或者只记录不抛出
    ExceptionUtil.log(e);
}
```

### 2. XXHashUtil
高性能的XXHash哈希计算工具类，适用于快速数据校验。

```java
String data = "需要哈希的数据";
long hash64 = XXHashUtil.hash64(data.getBytes());
int hash32 = XXHashUtil.hash32(data.getBytes());

// 文件哈希
File file = new File("large_file.dat");
long fileHash = XXHashUtil.hash64(file);
```

### 3. CosUtil（腾讯云COS存储工具类）
腾讯云对象存储操作工具类，支持分片上传、下载等功能。

```java
// 初始化COS客户端
CosUtil.init("secretId", "secretKey", "region");

// 上传文件
File localFile = new File("local_file.txt");
String cosKey = "uploads/file.txt";
String etag = CosUtil.uploadFile("bucketName", cosKey, localFile);

// 下载文件
File downloadFile = new File("download.txt");
CosUtil.downloadFile("bucketName", cosKey, downloadFile);

// 分片上传大文件
CosUtil.multipartUpload("bucketName", "large_file.mp4", 
    new File("large_file.mp4"), 5 * 1024 * 1024); // 5MB分片
```

### 4. ChannelUtils（Netty Channel工具类）
Netty网络通信工具类，提供Channel管理和操作功能。

```java
// 获取Channel信息
Channel channel = ...;
String channelId = ChannelUtils.getChannelId(channel);
boolean isActive = ChannelUtils.isChannelActive(channel);

// 发送消息
P2PWrapper wrapper = new P2PWrapper();
wrapper.setCommand(P2PCommand.FILE_GET);
ChannelUtils.writeAndFlush(channel, wrapper);

// 关闭连接
ChannelUtils.closeChannel(channel);
```

### 5. P2PUtils系列（P2P文件传输工具类）
P2P网络文件传输工具类，支持TCP/UDP协议、分块传输等功能。

```java
// TCP文件传输
P2PUtils.downloadFileTcp("serverAddress", 8080, 
    "remoteFilePath", "localFilePath");

// UDP文件传输（适合小文件）
P2PUtils.downloadFileUdp("serverAddress", 8081,
    "remoteFilePath", "localFilePath");

// 分块下载（支持断点续传）
List<Integer> blocks = Arrays.asList(0, 1, 2, 3);
P2PUtils.downloadFileBlocks("serverAddress", 8080,
    "remoteFilePath", "localFilePath", blocks);
```

---

## 最佳实践

### 1. 错误处理
```java
// 使用工具类时应该进行适当的错误处理
try {
    byte[] encrypted = SecurityUtils.encryptStringAes(data, key);
    // 处理加密结果
} catch (Exception e) {
    // 记录日志并处理异常
    logger.error("加密失败", e);
    throw new BusinessException("数据加密失败", e);
}
```

### 2. 资源管理
```java
// 使用ByteBuf等资源时必须正确释放
ByteBuf buffer = null;
try {
    buffer = SerializationUtil.serializeToByteBuf(obj);
    // 使用buffer...
} finally {
    if (buffer != null && buffer.refCnt() > 0) {
        buffer.release();
    }
}
```

### 3. 性能优化
```java
// 批量操作使用线程局部变量
// DateUtil内部已经使用了线程局部缓存
for (int i = 0; i < 1000; i++) {
    Date date = DateUtil.parse(dateStrings[i]);
    // 处理日期...
}

// 大文件使用分块处理
File largeFile = new File("huge_file.dat");
int blockSize = 1024 * 1024; // 1MB
long fileSize = largeFile.length();
int blockCount = (int) Math.ceil((double) fileSize / blockSize);

for (int blockIndex = 0; blockIndex < blockCount; blockIndex++) {
    long start = blockIndex * blockSize;
    long count = Math.min(blockSize, fileSize - start);
    byte[] blockData = FileUtil.loadFile(largeFile, start, count);
    // 处理分块数据...
}
```

### 4. 安全性考虑
```java
// 密钥管理
// 不要硬编码密钥，应该从安全配置中读取
String encryptionKey = System.getenv("ENCRYPTION_KEY");
if (encryptionKey == null || encryptionKey.isEmpty()) {
    throw new SecurityException("加密密钥未配置");
}

// 输入验证
String userInput = getUserInput();
if (userInput == null || userInput.trim().isEmpty()) {
    throw new IllegalArgumentException("输入不能为空");
}

// 防止路径遍历攻击
String userPath = getUserProvidedPath();
if (userPath.contains("..") || userPath.startsWith("/")) {
    throw new SecurityException("非法路径");
}
```

---

## 总结

本项目提供了丰富的工具类库，涵盖了安全加密、序列化、文件操作、日期处理、网络通信等多个方面。正确使用这些工具类可以：

1. **提高开发效率** - 避免重复造轮子
2. **保证代码质量** - 经过验证的可靠实现
3. **增强系统安全性** - 内置安全最佳实践
4. **优化性能** - 使用高效算法和实现
5. **提高可维护性** - 统一的接口和错误处理

建议开发者在实际使用前仔细阅读每个工具类的使用示例和注意事项，确保正确理解其功能和使用场景。