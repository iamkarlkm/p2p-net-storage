# P2P-Net-StorageSystem 前后端集成测试契约协议对比报告
**生成时间**: 2026-04-23
**项目路径**: `I:\2025\code\P2P-Net-StorageSystem\p2p-net-storage`
---
## 1. 统计概览
| 类别 | 数量 |
|------|------|
| 后端API (Java) | 2101 |
| Protobuf Message | 31 |
| Protobuf Enum | 0 |
| 前端SDK API | 587 |

## 2. 后端API接口定义
### 2.1 Java服务层
- `BouncyCastleSelfSignedCertGenerator.BouncyCastleSelfSignedCertGenerator()` → `private`
- `CleanDir.cleanAction(File file)` → `boolean`
- `CleanDir.fileCopyNIO(File src, File dest)` → `void`
- `CleanDir.fileCopyNIO(InputStream is, File dest,long count)` → `void`
- `CleanDir.fileCopyNIO(File src, OutputStream os)` → `void`
- `CleanDir.main2(String[] args)` → `void`
- `CleanDir.scanDir(File from)` → `void`
- `MergeDir.fileCopyNIO(File src, File dest)` → `void`
- `MergeDir.fileCopyNIO(InputStream is, File dest,long count)` → `void`
- `MergeDir.fileCopyNIO(File src, OutputStream os)` → `void`
- `MergeDir.main3(String[] args)` → `void`
- `MergeDir.mergeDir(File to,File from)` → `void`
- `QuicClient.ChannelInboundHandlerAdapter()` → `new`
- `QuicClient.ChannelInboundHandlerAdapter()` → `new`
- `QuicClient.channelActive(ChannelHandlerContext ctx)` → `void`
- `QuicClient.channelRead(ChannelHandlerContext ctx, Object msg)` → `void`
- `QuicClient.userEventTriggered(ChannelHandlerContext ctx, Object evt)` → `void`
- `QuicServer.ChannelInboundHandlerAdapter()` → `new`
- `QuicServer.ChannelInboundHandlerAdapter()` → `new`
- `QuicServer.GenericFutureListener()` → `new`
- `QuicServer.channelActive(ChannelHandlerContext ctx)` → `void`
- `QuicServer.channelInactive(ChannelHandlerContext ctx)` → `void`
- `QuicServer.channelRead(ChannelHandlerContext ctx, Object msg)` → `void`
- `QuicServer.initChannel(QuicStreamChannel ch)` → `void`
- `QuicServer.operationComplete(Future future)` → `void`
- `RWLock.RWLock()` → `public`
- `RWLock.RWLock(Mode mode)` → `public`
- `RWLock.destroy()` → `void`
- `RWLock.read(Runnable cb)` → `void`
- `RWLock.write(Runnable cb)` → `void`
- `ThreadLocalInsecureRandom.ThreadLocalInsecureRandom()` → `private`
- `ThreadLocalInsecureRandom.current()` → `SecureRandom`
- `ThreadLocalInsecureRandom.nextBoolean()` → `boolean`
- `ThreadLocalInsecureRandom.nextBytes(byte[] bytes)` → `void`
- `ThreadLocalInsecureRandom.nextDouble()` → `double`
- `ThreadLocalInsecureRandom.nextFloat()` → `float`
- `ThreadLocalInsecureRandom.nextGaussian()` → `double`
- `ThreadLocalInsecureRandom.nextInt()` → `int`
- `ThreadLocalInsecureRandom.nextInt(int n)` → `int`
- `ThreadLocalInsecureRandom.nextLong()` → `long`
- `ThreadLocalInsecureRandom.random()` → `Random`
- `UDPClientHandler.channelRead0(ChannelHandlerContext ctx, DatagramPacket msg)` → `void`
- `UDPClientHandler.exceptionCaught(ChannelHandlerContext ctx, Throwable cause)` → `void`
- `UDPServerHandler.channelRead0(ChannelHandlerContext ctx, DatagramPacket msg)` → `void`
- `UDPServerHandler.channelReadComplete(ChannelHandlerContext ctx)` → `void`
- `UDPServerHandler.exceptionCaught(ChannelHandlerContext ctx, Throwable cause)` → `void`
- `UDPServerHandler.nextQuote()` → `String`
- `com.flydean17.protobuf.FileServer.initChannel(SocketChannel ch)` → `void`
- `com.flydean17.protobuf.FileServerHandler.channelRead0(ChannelHandlerContext ctx, String msg)` → `void`
- `com.flydean17.protobuf.Student.Builder()` → `private`

... 还有 2051 个方法未显示

### 2.2 Protobuf协议定义
- `CenterHello` (message)
  - 字段: []...
- `CenterHelloAck` (message)
  - 字段: []...
- `CenterHelloBody` (message)
  - 字段: [["repeated", "Endpoint", "reported_endpoints", "3"]]...
- `ConnectHint` (message)
  - 字段: []...
- `ConnectHintAck` (message)
  - 字段: [["repeated", "Endpoint", "target_endpoints", "4"]]...
- `CryptUpdate` (message)
  - 字段: []...
- `Endpoint` (message)
  - 字段: []...
- `FileDataModel` (message)
  - 字段: []...
- `FileGetRequest` (message)
  - 字段: []...
- `FileGetResponse` (message)
  - 字段: []...
- `FileListEntry` (message)
  - 字段: []...
- `FileListRequest` (message)
  - 字段: []...
- `FileListResponse` (message)
  - 字段: [["repeated", "FileListEntry", "items", "4"]]...
- `FilePutRequest` (message)
  - 字段: []...
- `FilePutResponse` (message)
  - 字段: []...
- `FileRenameRequest` (message)
  - 字段: []...
- `FileSegmentsDataModel` (message)
  - 字段: []...
- `FilesCommandModel` (message)
  - 字段: [["repeated", "string", "params", "3"]]...
- `GetNode` (message)
  - 字段: []...
- `GetNodeAck` (message)
  - 字段: [["repeated", "Endpoint", "endpoints", "4"]]...
- `Hand` (message)
  - 字段: [["repeated", "bytes", "key_ids", "2"]]...
- `HandAckPlain` (message)
  - 字段: []...
- `HeaderUpdate` (message)
  - 字段: []...
- `IncomingHint` (message)
  - 字段: [["repeated", "Endpoint", "source_endpoints", "3"]]...
- `NodeCaps` (message)
  - 字段: []...
- `P2PWrapper` (message)
  - 字段: []...
- `PeerHello` (message)
  - 字段: []...
- `PeerHelloAck` (message)
  - 字段: []...
- `PeerHelloBody` (message)
  - 字段: []...
- `RelayData` (message)
  - 字段: []...
- `StringList` (message)
  - 字段: [["repeated", "string", "items", "1"]]...

## 3. 前端SDK API定义

### 3.40 DART\LIB\SRC\FRAME.DART SDK
- `frame.DecodedFrame(this.header, this.cipherPayload)` → `DecodedFrame(this.header,`
- `frame.FormatException("frame too short")` → `too`
- `frame.FormatException("length check failed")` → `check`
- `frame.WireHeader(this.length, this.magic, this.version, this.flags)` → `this.version,`
- `frame.decodeFrame(Uint8List frame)` → `frame)`
- `frame.encodeFrame(WireHeader h, Uint8List payload)` → `payload)`

### 3.41 DART\LIB\SRC\HANDSHAKE.DART SDK
- `handshake.ArgumentError("length mismatch")` → `ArgumentError("length`
- `handshake.FormatException("invalid spki")` → `FormatException("invalid`
- `handshake.FormatException("invalid spki bit string")` → `bit`
- `handshake.FormatException("invalid rsa public key")` → `public`
- `handshake.RSAPublicKey(n, e)` → `RSAPublicKey(n,`
- `handshake.StateError("message too long")` → `too`
- `handshake.StateError("invalid key size")` → `key`
- `handshake.StateError("oaep decoding error")` → `decoding`
- `handshake.StateError("oaep decoding error")` → `decoding`
- `handshake.StateError("oaep decoding error")` → `decoding`
- `handshake.StateError("oaep decoding error")` → `decoding`
- `handshake.StateError("private key missing public exponent")` → `public`
- `handshake.StateError("integer too large")` → `too`
- `handshake._i2osp(c, k)` → `_i2osp(c,`
- `handshake._i2osp(BigInt x, int size)` → `size)`
- `handshake._leftPad(Uint8List x, int size)` → `size)`
- `handshake._mgf1Sha256(Uint8List seed, int maskLen)` → `maskLen)`
- `handshake._os2ip(Uint8List x)` → `x)`
- `handshake._secureRandomBytes(int len)` → `len)`
- `handshake._xor(Uint8List a, Uint8List b)` → `b)`
- `handshake.buildHandWrapper({
  required int seq,
  required Uint8List clientPubkeySpkiDer,
  required List<Uint8List> keyIds,
  required int maxFramePayload,
  required String clientId,
})` → `})`
- `handshake.decodeHandAckPlain(plain)` → `return`
- `handshake.decryptHandAckPlain({
  required String privateKeyPem,
  required Uint8List encrypted,
})` → `})`
- `handshake.rsaOaepSha256Decrypt(RSAPrivateKey privateKey, Uint8List cipher)` → `cipher)`
- `handshake.rsaOaepSha256Decrypt(priv, cipher)` → `rsaOaepSha256Decrypt(priv,`
- `handshake.rsaOaepSha256DecryptPem(String privateKeyPem, Uint8List cipher)` → `cipher)`
- `handshake.rsaOaepSha256Encrypt(RSAPublicKey publicKey, Uint8List message)` → `message)`
- `handshake.rsaPublicKeyFromSpkiDer(Uint8List spkiDer)` → `spkiDer)`
- `handshake.spkiDerFromRsaPrivateKeyPem(String privateKeyPem)` → `privateKeyPem)`
- `handshake.spkiDerFromRsaPublicKey(pub)` → `return`

... 还有 1 个方法未显示

### 3.42 DART\LIB\SRC\KEYFILE.DART SDK
- `keyfile.RangeError("offset/length must be >= 0")` → `>=`
- `keyfile.RangeError("slice out of range")` → `of`
- `keyfile.StateError("unexpected EOF")` → `StateError("unexpected`
- `keyfile.open(String path)` → `async`
- `keyfile.readSlice(int offset, int length)` → `async`

### 3.50 DART\LIB\SRC\MESSAGES\CONTROL.DART SDK
- `control.CryptUpdate({required this.keyId, required this.offset, required this.effectiveFromSeq})` → `required`
- `control.CryptUpdate(keyId: keyId, offset: offset, effectiveFromSeq: effectiveFromSeq)` → `effectiveFromSeq:`
- `control.Hand({
    required this.clientPubkeySpkiDer,
    required this.keyIds,
    required this.maxFramePayload,
    required this.clientId,
  })` → `this.clientId,`
- `control.Hand(clientPubkeySpkiDer: clientPubkeySpkiDer,
    keyIds: keyIds,
    maxFramePayload: maxFramePayload,
    clientId: clientId,)` → `clientId,`
- `control.HandAckPlain({
    required this.sessionId,
    required this.selectedKeyId,
    required this.offset,
    required this.maxFramePayload,
    required this.headerPolicyId,
  })` → `this.headerPolicyId,`
- `control.HandAckPlain(sessionId: sessionId,
    selectedKeyId: selectedKeyId,
    offset: offset,
    maxFramePayload: maxFramePayload,
    headerPolicyId: headerPolicyId,)` → `headerPolicyId,`
- `control.decodeCryptUpdate(Uint8List bytes)` → `bytes)`
- `control.decodeHand(Uint8List bytes)` → `bytes)`
- `control.decodeHandAckPlain(Uint8List bytes)` → `bytes)`
- `control.encodeHand(Hand h)` → `h)`
- `control.encodeHandAckPlain(HandAckPlain a)` → `a)`

### 3.51 DART\LIB\SRC\MESSAGES\CONTROL_PLANE.DART SDK
- `control_plane.CenterHello({required this.body, required this.signature})` → `required`
- `control_plane.CenterHelloAck({
    required this.nodeKey,
    required this.observedEndpoint,
    required this.ttlSeconds,
    required this.serverTimeMs,
  })` → `this.serverTimeMs,`
- `control_plane.CenterHelloAck(nodeKey: nodeKey, observedEndpoint: observedEndpoint, ttlSeconds: ttlSeconds, serverTimeMs: serverTimeMs)` → `serverTimeMs:`
- `control_plane.CenterHelloBody({
    required this.nodeId64,
    required this.pubkeySpkiDer,
    required this.reportedEndpoints,
    required this.caps,
    required this.timestampMs,
    required this.cryptoMode,
  })` → `this.cryptoMode,`
- `control_plane.ConnectHint({required this.targetNodeId64, required this.targetNodeKey})` → `required`
- `control_plane.ConnectHint(targetNodeId64: targetNodeId64, targetNodeKey: targetNodeKey)` → `targetNodeKey:`
- `control_plane.ConnectHintAck({
    required this.found,
    required this.targetNodeId64,
    required this.targetNodeKey,
    required this.targetEndpoints,
    required this.token,
  })` → `this.token,`
- `control_plane.ConnectHintAck(found: found,
    targetNodeId64: targetNodeId64,
    targetNodeKey: targetNodeKey,
    targetEndpoints: targetEndpoints,
    token: token,)` → `token,`
- `control_plane.Endpoint({required this.transport, required this.addr})` → `required`
- `control_plane.Endpoint(transport: transport, addr: addr)` → `addr:`
- `control_plane.Endpoint(transport: "", addr: "")` → `addr:`
- `control_plane.GetNode({required this.nodeId64, required this.nodeKey})` → `required`
- `control_plane.GetNodeAck({
    required this.found,
    required this.nodeKey,
    required this.nodeId64,
    required this.endpoints,
    required this.caps,
    required this.expiresAtMs,
  })` → `this.expiresAtMs,`
- `control_plane.GetNodeAck(found: found, nodeKey: nodeKey, nodeId64: nodeId64, endpoints: endpoints, caps: caps, expiresAtMs: expiresAtMs)` → `expiresAtMs:`
- `control_plane.IncomingHint({
    required this.sourceNodeId64,
    required this.sourceNodeKey,
    required this.sourceEndpoints,
    required this.token,
  })` → `this.token,`
- `control_plane.IncomingHint(sourceNodeId64: sourceNodeId64, sourceNodeKey: sourceNodeKey, sourceEndpoints: sourceEndpoints, token: token)` → `token:`
- `control_plane.NodeCaps({
    required this.maxFramePayload,
    required this.magic,
    required this.version,
    required this.flagsPlain,
    required this.flagsEncrypted,
  })` → `this.flagsEncrypted,`
- `control_plane.NodeCaps(maxFramePayload: maxFramePayload,
    magic: magic,
    version: version,
    flagsPlain: flagsPlain,
    flagsEncrypted: flagsEncrypted,)` → `flagsEncrypted,`
- `control_plane.PeerHello({required this.body, required this.signature})` → `required`
- `control_plane.PeerHelloAck({required this.nodeKey, required this.serverTimeMs})` → `required`
- `control_plane.PeerHelloAck(nodeKey: nodeKey, serverTimeMs: serverTimeMs)` → `serverTimeMs:`
- `control_plane.PeerHelloBody({
    required this.nodeId64,
    required this.pubkeySpkiDer,
    required this.timestampMs,
    required this.cryptoMode,
  })` → `this.cryptoMode,`
- `control_plane.PeerHelloBody(nodeId64: nodeId64, pubkeySpkiDer: pubkeySpkiDer, timestampMs: timestampMs, cryptoMode: cryptoMode)` → `cryptoMode:`
- `control_plane.PeerHelloDecoded({required this.body, required this.bodyBytes, required this.signature})` → `required`
- `control_plane.RelayData({
    required this.targetNodeId64,
    required this.targetNodeKey,
    required this.sourceNodeId64,
    required this.sourceNodeKey,
    required this.payload,
  })` → `this.payload,`
- `control_plane.RelayData(targetNodeId64: targetNodeId64,
    targetNodeKey: targetNodeKey,
    sourceNodeId64: sourceNodeId64,
    sourceNodeKey: sourceNodeKey,
    payload: payload,)` → `payload,`
- `control_plane.decodeCenterHelloAck(Uint8List bytes)` → `bytes)`
- `control_plane.decodeConnectHint(Uint8List bytes)` → `bytes)`
- `control_plane.decodeConnectHintAck(Uint8List bytes)` → `bytes)`
- `control_plane.decodeEndpoint(Uint8List bytes)` → `bytes)`

... 还有 17 个方法未显示

### 3.52 DART\LIB\SRC\MESSAGES\DATA.DART SDK
- `data.FileDataModel({
    required this.storeId,
    required this.length,
    required this.data,
    required this.path,
    required this.md5,
    required this.blockSize,
  })` → `this.blockSize,`
- `data.FileDataModel(storeId: storeId, length: length, data: data, path: path, md5: md5, blockSize: blockSize)` → `blockSize:`
- `data.FileGetRequest({required this.fileHashSha256})` → `FileGetRequest({required`
- `data.FileGetRequest(fileHashSha256: fileHashSha256)` → `FileGetRequest(fileHashSha256:`
- `data.FileGetResponse({required this.found, required this.fileName, required this.fileSize, required this.content})` → `required`
- `data.FileGetResponse(found: found, fileName: fileName, fileSize: fileSize, content: content)` → `content:`
- `data.FileListEntry({required this.name, required this.path, required this.isDir, required this.size, required this.modifiedMs})` → `required`
- `data.FileListEntry(name: name, path: path, isDir: isDir, size: size, modifiedMs: modifiedMs)` → `modifiedMs:`
- `data.FileListRequest({required this.storeId, required this.path, required this.page, required this.pageSize})` → `required`
- `data.FileListRequest(storeId: storeId, path: path, page: page, pageSize: pageSize)` → `pageSize:`
- `data.FileListResponse({required this.page, required this.pageSize, required this.total, required this.items})` → `required`
- `data.FileListResponse(page: page, pageSize: pageSize, total: total, items: items)` → `items:`
- `data.FilePutRequest({
    required this.fileName,
    required this.fileSize,
    required this.fileHashSha256,
    required this.content,
  })` → `this.content,`
- `data.FilePutRequest(fileName: fileName, fileSize: fileSize, fileHashSha256: fileHashSha256, content: content)` → `content:`
- `data.FilePutResponse({required this.success, required this.errorMessage, required this.fileHashSha256})` → `required`
- `data.FilePutResponse(success: success, errorMessage: errorMessage, fileHashSha256: fileHashSha256)` → `fileHashSha256:`
- `data.FileRenameRequest({required this.storeId, required this.srcPath, required this.dstPath})` → `required`
- `data.FileRenameRequest(storeId: storeId, srcPath: srcPath, dstPath: dstPath)` → `dstPath:`
- `data.FileSegmentsDataModel({
    required this.storeId,
    required this.length,
    required this.start,
    required this.blockIndex,
    required this.blockSize,
    required this.blockData,
    required this.blockMd5,
    required this.path,
    required this.md5,
  })` → `this.md5,`
- `data.FileSegmentsDataModel(storeId: storeId,
    length: length,
    start: start,
    blockIndex: blockIndex,
    blockSize: blockSize,
    blockData: blockData,
    blockMd5: blockMd5,
    path: path,
    md5: md5,)` → `md5,`
- `data.FilesCommandModel({required this.storeId, required this.command, required this.params, required this.data})` → `required`
- `data.FilesCommandModel(storeId: storeId, command: command, params: params, data: data)` → `data:`
- `data.StdError(this.message)` → `const`
- `data.StdError(message)` → `return`
- `data.StringList(this.items)` → `const`
- `data.StringList(items)` → `return`
- `data.decodeFileDataModel(Uint8List bytes)` → `bytes)`
- `data.decodeFileGetRequest(Uint8List bytes)` → `bytes)`
- `data.decodeFileGetResponse(Uint8List bytes)` → `bytes)`
- `data.decodeFileListEntry(Uint8List bytes)` → `bytes)`

... 还有 22 个方法未显示

### 3.53 DART\LIB\SRC\MESSAGES\WRAPPER.DART SDK
- `wrapper.P2PWrapper({
    required this.seq,
    required this.command,
    required this.data,
    this.headers = const {},
  })` → `{},`
- `wrapper.P2PWrapper(seq: seq, command: command, data: data, headers: headers)` → `headers:`
- `wrapper.decodeWrapper(Uint8List bytes)` → `bytes)`
- `wrapper.encodeWrapper(P2PWrapper w)` → `w)`

### 3.43 DART\LIB\SRC\PEER_NODE.DART SDK
- `peer_node.Endpoint(transport: e.transport, addr: "$host:$port")` → `addr:`
- `peer_node.PeerNodeConfig({
    required this.userId,
    required this.configDir,
    required this.wsUrl,
    required this.listenPort,
    required this.wsPath,
    required this.keyfilePath,
    required this.keyIdSha256Hex,
    required this.rsaPrivateKeyPemPath,
    required this.cryptoMode,
    required this.reportedEndpoints,
    required this.storageLocations,
    required this.presenceCachePath,
    required this.cooldownCachePath,
    required this.enableConnectHint,
    required this.renewSeconds,
    required this.magic,
    required this.version,
    required this.flagsPlain,
    required this.flagsEncrypted,
    required this.maxFramePayload,
  })` → `this.maxFramePayload,`
- `peer_node.PeerNodeConfig(userId: userId,
      configDir: cfgDir.absolute.path,
      wsUrl: wsUrl,
      listenPort: listenPort,
      wsPath: wsPath,
      keyfilePath: keyfilePath,
      keyIdSha256Hex: keyIdSha256Hex,
      rsaPrivateKeyPemPath: rsaPrivateKeyPemPath,
      cryptoMode: cryptoMode,
      reportedEndpoints: reportedEndpoints,
      storageLocations: storageLocations,
      presenceCachePath: presenceCachePath,
      cooldownCachePath: cooldownCachePath,
      enableConnectHint: enableConnectHint,
      renewSeconds: renewSeconds,
      magic: magic,
      version: version,
      flagsPlain: flagsPlain,
      flagsEncrypted: flagsEncrypted,
      maxFramePayload: maxFramePayload,)` → `maxFramePayload,`
- `peer_node.StateError("user_id is required")` → `is`
- `peer_node.StateError("ws_url is required")` → `is`
- `peer_node.StateError("listen_port is required")` → `is`
- `peer_node.StateError("keyfile_path is required")` → `is`
- `peer_node.StateError("rsa_private_key_pem_path is required")` → `is`
- `peer_node.StateError("key_id_sha256_hex mismatch")` → `StateError("key_id_sha256_hex`
- `peer_node.StateError("storage_locations is required")` → `is`
- `peer_node._DialResult(this.endpoint, this.ok)` → `_DialResult(this.endpoint,`
- `peer_node._EndpointCooldown(0, 0)` → `_EndpointCooldown(0,`
- `peer_node._EndpointCooldown(this.untilMs, this.fails)` → `_EndpointCooldown(this.untilMs,`
- `peer_node._applyObservedIpToEndpoints(List<Endpoint> endpoints, String observedAddr)` → `observedAddr)`
- `peer_node._connectCenterLoop()` → `async`
- `peer_node._connectCenterOnce()` → `await`
- `peer_node._connectCenterOnce()` → `async`
- `peer_node._dialHintEndpoints(ih.sourceEndpoints)` → `await`
- `peer_node._dialHintEndpoints(ackHint.targetEndpoints)` → `await`
- `peer_node._dialHintEndpoints(List<Endpoint> endpoints)` → `async`
- `peer_node._equalsBytes(Uint8List a, Uint8List b)` → `b)`
- `peer_node._flushPendingConnectHints(center)` → `await`
- `peer_node._flushPendingConnectHints(P2PSession center)` → `async`
- `peer_node._loadCooldownCache(String path)` → `path)`
- `peer_node._md5HexFile(file)` → `await`
- `peer_node._md5HexFile(file)` → `await`
- `peer_node._md5HexFile(File file)` → `async`
- `peer_node._sameEndpoints(List<Endpoint> a, List<Endpoint> b)` → `b)`
- `peer_node._savePresenceCacheObserved(String presenceCachePath, Endpoint observed)` → `observed)`
- `peer_node._sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, errWrap, w.seq)` → `errWrap,`

... 还有 60 个方法未显示

### 3.44 DART\LIB\SRC\PROTO_LITE.DART SDK
- `proto_lite.FormatException("truncated varint")` → `FormatException("truncated`
- `proto_lite.FormatException("varint too long")` → `too`
- `proto_lite.FormatException("truncated fixed64")` → `FormatException("truncated`
- `proto_lite.FormatException("truncated bytes")` → `FormatException("truncated`
- `proto_lite.FormatException("truncated fixed64")` → `FormatException("truncated`
- `proto_lite.FormatException("truncated len")` → `FormatException("truncated`
- `proto_lite.FormatException("truncated fixed32")` → `FormatException("truncated`
- `proto_lite.FormatException("unsupported wireType=$wireType")` → `FormatException("unsupported`
- `proto_lite.readBytes()` → `readBytes()`
- `proto_lite.readFixed64()` → `readFixed64()`
- `proto_lite.readString()` → `readString()`
- `proto_lite.readVarint()` → `readVarint()`
- `proto_lite.skipField(int wireType)` → `wireType)`
- `proto_lite.writeBool(int fieldNumber, bool value)` → `value)`
- `proto_lite.writeBytesField(int fieldNumber, Uint8List value)` → `value)`
- `proto_lite.writeEmbedded(int fieldNumber, Uint8List messageBytes)` → `messageBytes)`
- `proto_lite.writeFixed64(int fieldNumber, int value)` → `value)`
- `proto_lite.writeInt32(int fieldNumber, int value)` → `value)`
- `proto_lite.writeString(int fieldNumber, String value)` → `value)`
- `proto_lite.writeTag(int fieldNumber, int wireType)` → `wireType)`
- `proto_lite.writeUint32(int fieldNumber, int value)` → `value)`
- `proto_lite.writeUint64(int fieldNumber, int value)` → `value)`
- `proto_lite.writeVarint(int value)` → `value)`
- `proto_lite.writeVarint64(int value)` → `value)`

### 3.45 DART\LIB\SRC\RSA.DART SDK
- `rsa.StateError("invalid key size")` → `key`
- `rsa.StateError("integer too large")` → `too`
- `rsa._i2osp(s, k)` → `_i2osp(s,`
- `rsa._i2osp(BigInt x, int size)` → `size)`
- `rsa._leftPad(Uint8List x, int size)` → `size)`
- `rsa._os2ip(Uint8List x)` → `x)`
- `rsa.rsaSignSha256Pkcs1v15(RSAPrivateKey privateKey, Uint8List message)` → `message)`
- `rsa.rsaVerifySha256Pkcs1v15(RSAPublicKey publicKey, Uint8List message, Uint8List signature)` → `signature)`

### 3.46 DART\LIB\SRC\SERVER.DART SDK
- `server.P2PServerConfig({
    required this.listenPort,
    required this.wsPath,
    required this.magic,
    required this.version,
    required this.flagsPlain,
    required this.flagsEncrypted,
    required this.maxFramePayload,
  })` → `this.maxFramePayload,`
- `server.StateError("not encrypted yet")` → `encrypted`
- `server._handleCheckFile(P2PWrapper w)` → `async`
- `server._handleCryptUpdate(P2PWrapper w)` → `async`
- `server._handleDeprecated(P2PWrapper w)` → `async`
- `server._handleFileExists(P2PWrapper w)` → `async`
- `server._handleFileList(P2PWrapper w)` → `async`
- `server._handleFileMkdirs(P2PWrapper w)` → `async`
- `server._handleFileRename(P2PWrapper w)` → `async`
- `server._handleGetFile(P2PWrapper w)` → `async`
- `server._handleGetFileSegments(P2PWrapper w)` → `async`
- `server._handleHand(w)` → `await`
- `server._handleHand(P2PWrapper w)` → `async`
- `server._handleHttp(HttpRequest req)` → `async`
- `server._handleInfoFile(P2PWrapper w)` → `async`
- `server._handlePeerHello(P2PWrapper w)` → `async`
- `server._handlePutFile(P2PWrapper w)` → `async`
- `server._handlePutFileSegments(P2PWrapper w)` → `async`
- `server._handlePutFileSegmentsComplete(P2PWrapper w)` → `async`
- `server._md5HexFile(file)` → `await`
- `server._md5HexFile(file)` → `await`
- `server._md5HexFile(File file)` → `async`
- `server._sendEncrypted(P2PWrapper w)` → `async`
- `server.h(w)` → `await`
- `server.run()` → `async`
- `server.start()` → `async`
- `server.stop()` → `async`

### 3.47 DART\LIB\SRC\SESSION.DART SDK
- `session.HandshakeState({
    required this.sessionId,
    required this.selectedKeyId,
    required this.offset,
    required this.maxFramePayload,
    required this.headerPolicyId,
  })` → `this.headerPolicyId,`
- `session.HandshakeState(sessionId: ack.sessionId,
        selectedKeyId: ack.selectedKeyId,
        offset: ack.offset,
        maxFramePayload: ack.maxFramePayload,
        headerPolicyId: ack.headerPolicyId,)` → `ack.headerPolicyId,`
- `session.P2PSessionConfig({
    required this.magic,
    required this.version,
    required this.flagsPlain,
    required this.flagsEncrypted,
    required this.maxFramePayload,
  })` → `this.maxFramePayload,`
- `session.StateError("unexpected response command=${w.command} expected=$expectedCommand")` → `command=${w.command}`
- `session.StateError("not encrypted yet")` → `encrypted`
- `session._listen()` → `_listen()`
- `session._sendWrapperEncrypted(P2PWrapper w)` → `async`
- `session._sendWrapperPlain(wrap)` → `await`
- `session._sendWrapperPlain(P2PWrapper w)` → `async`
- `session.close()` → `async`
- `session.connect({
    required String wsUrl,
    required P2PSessionConfig config,
    required KeyFileReader keyfile,
    Map<String, dynamic>? headers,
  })` → `async`
- `session.handshake({
    required RSAPrivateKey privateKey,
    required Uint8List clientPubkeySpkiDer,
    required String clientId,
  })` → `async`
- `session.request({
    required int command,
    required Uint8List data,
    required int expectedCommand,
    Map<String, String> headers = const {},
  })` → `async`
- `session.requestAny({
    required int command,
    required Uint8List data,
    Map<String, String> headers = const {},
  })` → `async`

### 3.48 DART\LIB\SRC\SHARED_STORAGE.DART SDK
- `shared_storage.ArgumentError("storeId must be > 0")` → `>`
- `shared_storage.ArgumentError("path is empty")` → `is`
- `shared_storage.ArgumentError("illegal path")` → `ArgumentError("illegal`
- `shared_storage.ArgumentError("illegal path")` → `ArgumentError("illegal`
- `shared_storage.ArgumentError("illegal path")` → `ArgumentError("illegal`
- `shared_storage.ArgumentError("illegal path")` → `ArgumentError("illegal`
- `shared_storage.ArgumentError("illegal path")` → `ArgumentError("illegal`
- `shared_storage.StateError("storage location not registered: $storeId")` → `registered:`
- `shared_storage.StateError("path traversal detected")` → `traversal`
- `shared_storage.StateError("path traversal detected")` → `traversal`
- `shared_storage.StateError("path traversal detected")` → `traversal`
- `shared_storage.StateError("path traversal detected")` → `traversal`
- `shared_storage.StateError("file not found")` → `not`
- `shared_storage.StateError("path traversal detected")` → `traversal`
- `shared_storage._isUnder(String baseReal, String targetReal)` → `targetReal)`
- `shared_storage._normalizeRelativePath(String path)` → `path)`
- `shared_storage._resolveDirReal(String dirAbs)` → `dirAbs)`
- `shared_storage._resolveFileReal(String fileAbs)` → `fileAbs)`
- `shared_storage.getAndCheckExistsSandboxFile(int storeId, String path)` → `path)`
- `shared_storage.getSandboxFile(int storeId, String path)` → `path)`
- `shared_storage.getSandboxFileForWrite(int storeId, String path)` → `path)`
- `shared_storage.getStorageLocation(int storeId)` → `storeId)`
- `shared_storage.registerStorageLocation(int storeId, Directory dir)` → `dir)`

### 3.49 DART\LIB\SRC\XOR.DART SDK
- `xor.RangeError("keyOffset must be >= 0")` → `>=`
- `xor.RangeError("key slice out of range")` → `of`
- `xor.xorNoWrap(Uint8List data, Uint8List key, int keyOffset)` → `keyOffset)`

### 3.1 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\FILEKEYFILEPROVIDER.JAVA SDK
- `p2pws.sdk.FileKeyFileProvider.Entry(FileChannel ch, long len)` → `record`
- `p2pws.sdk.FileKeyFileProvider.Key(byte[] id)` → `record`
- `p2pws.sdk.FileKeyFileProvider.Key(byte[] id)` → `private`
- `p2pws.sdk.FileKeyFileProvider.length(byte[] keyId32)` → `long`
- `p2pws.sdk.FileKeyFileProvider.put(Path path)` → `void`

### 3.2 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\FRAMECODEC.JAVA SDK
- `p2pws.sdk.FrameCodec.FrameCodec()` → `private`
- `p2pws.sdk.FrameCodec.decode(byte[] wsBinaryPayload)` → `WireFrame`

### 3.3 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\HANDSHAKE.JAVA SDK
- `p2pws.sdk.Handshake.Handshake()` → `private`
- `p2pws.sdk.Handshake.decryptHandAck(PrivateKey clientPrivateKey, byte[] encryptedHandAckData)` → `HandAckPlain`

### 3.4 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\INMEMORYKEYFILEPROVIDER.JAVA SDK
- `p2pws.sdk.InMemoryKeyFileProvider.Key(byte[] id)` → `record`
- `p2pws.sdk.InMemoryKeyFileProvider.Key(byte[] id)` → `private`
- `p2pws.sdk.InMemoryKeyFileProvider.length(byte[] keyId32)` → `long`
- `p2pws.sdk.InMemoryKeyFileProvider.put(byte[] keyId32, byte[] keyfileBytes)` → `void`

### 3.5 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\KEYID.JAVA SDK
- `p2pws.sdk.KeyId.KeyId()` → `private`

### 3.6 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\P2PWRAPPERCODEC.JAVA SDK
- `p2pws.sdk.P2PWrapperCodec.P2PWrapperCodec()` → `private`
- `p2pws.sdk.P2PWrapperCodec.decode(byte[] data)` → `P2PWrapper`

### 3.7 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\RSAOAEP.JAVA SDK
- `p2pws.sdk.RsaOaep.RsaOaep()` → `private`

### 3.8 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\WIREFRAME.JAVA SDK
- `p2pws.sdk.WireFrame.WireFrame(WireHeader header, byte[] cipherPayload)` → `record`

### 3.9 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\WIREHEADER.JAVA SDK
- `p2pws.sdk.WireHeader.WireHeader(long length, int magic, int version, int flags)` → `record`
- `p2pws.sdk.WireHeader.ofCipherPayload(int magic, int version, int flags, byte[] cipherPayload)` → `WireHeader`

### 3.10 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\WSBINARYCODEC.JAVA SDK
- `p2pws.sdk.WsBinaryCodec.WsBinaryCodec()` → `private`
- `p2pws.sdk.WsBinaryCodec.decode(byte[] wsBinaryPayload, KeyFileProvider provider, byte[] keyId32, long offset)` → `P2PWrapper`

### 3.11 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\XORCIPHER.JAVA SDK
- `p2pws.sdk.XorCipher.XorCipher()` → `private`

### 3.12 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\CENTER\CENTERCONFIG.JAVA SDK
- `p2pws.sdk.center.CenterConfig.CenterConfig(int listenPort,
    String wsPath,
    String keyfilePath,
    String registeredUsersPath,
    int ttlSeconds,
    int magic,
    int version,
    int flagsPlain,
    int flagsEncrypted,
    int maxFramePayload)` → `record`

### 3.13 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\CENTER\CENTERSERVERHANDLER.JAVA SDK
- `p2pws.sdk.center.CenterServerHandler.CenterServerHandler(KeyFileProvider provider, byte[] keyId32, long keyLen, int magic, int version, int flagsPlain, int flagsEncrypted, int maxFramePayload, int ttlSeconds, RegisteredUsers users, PresenceStore presence)` → `public`
- `p2pws.sdk.center.CenterServerHandler.channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame frame)` → `void`
- `p2pws.sdk.center.CenterServerHandler.handleCenterHello(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper)` → `void`
- `p2pws.sdk.center.CenterServerHandler.handleConnectHint(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper)` → `void`
- `p2pws.sdk.center.CenterServerHandler.handleGetNode(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper)` → `void`
- `p2pws.sdk.center.CenterServerHandler.handleHand(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper)` → `void`
- `p2pws.sdk.center.CenterServerHandler.handleRelayData(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper)` → `void`
- `p2pws.sdk.center.CenterServerHandler.mergeEndpoints(P2PControl.Endpoint observed, List<P2PControl.Endpoint> reported)` → `List<P2PControl.Endpoint>`
- `p2pws.sdk.center.CenterServerHandler.observedEndpoint(ChannelHandlerContext ctx)` → `Endpoint`
- `p2pws.sdk.center.CenterServerHandler.writeEncrypted(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper)` → `void`
- `p2pws.sdk.center.CenterServerHandler.writePlain(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper)` → `void`

### 3.14 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\CENTER\CENTERSERVERMAIN.JAVA SDK
- `p2pws.sdk.center.CenterServerMain.initChannel(SocketChannel ch)` → `void`

### 3.15 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\CENTER\CENTERYAML.JAVA SDK
- `p2pws.sdk.center.CenterYaml.CenterYaml()` → `private`
- `p2pws.sdk.center.CenterYaml.boolVal(Map<String, Object> m, String k, boolean def)` → `boolean`
- `p2pws.sdk.center.CenterYaml.intVal(Map<String, Object> m, String k, int def)` → `int`
- `p2pws.sdk.center.CenterYaml.loadCenter(Path cfgPath)` → `CenterConfig`
- `p2pws.sdk.center.CenterYaml.loadMap(Path path)` → `Map<String, Object>`
- `p2pws.sdk.center.CenterYaml.loadRegisteredUsers(Path regPath)` → `RegisteredUsers`
- `p2pws.sdk.center.CenterYaml.longVal(Map<String, Object> m, String k, long def)` → `long`
- `p2pws.sdk.center.CenterYaml.resolvePath(Path cfgPath, String p)` → `String`
- `p2pws.sdk.center.CenterYaml.strList(Map<String, Object> m, String k)` → `List<String>`
- `p2pws.sdk.center.CenterYaml.strVal(Map<String, Object> m, String k, String def)` → `String`

### 3.16 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\CENTER\PRESENCESTORE.JAVA SDK
- `p2pws.sdk.center.PresenceStore.Key(byte[] id)` → `record`
- `p2pws.sdk.center.PresenceStore.Key(byte[] id)` → `private`
- `p2pws.sdk.center.PresenceStore.Presence(byte[] nodeKey32, long nodeId64, List<P2PControl.Endpoint> endpoints, P2PControl.NodeCaps caps, long expiresAtMs, ChannelHandlerContext ctx)` → `record`
- `p2pws.sdk.center.PresenceStore.Presence(byte[] nodeKey32, long nodeId64, List<P2PControl.Endpoint> endpoints, P2PControl.NodeCaps caps, long expiresAtMs, ChannelHandlerContext ctx)` → `public`
- `p2pws.sdk.center.PresenceStore.getByNodeId(long nodeId64)` → `Presence`
- `p2pws.sdk.center.PresenceStore.purgeExpired(long nowMs)` → `int`
- `p2pws.sdk.center.PresenceStore.put(byte[] nodeKey32, long nodeId64, List<P2PControl.Endpoint> endpoints, P2PControl.NodeCaps caps, long expiresAtMs, ChannelHandlerContext ctx)` → `void`

### 3.17 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\CENTER\REGISTEREDUSERS.JAVA SDK
- `p2pws.sdk.center.RegisteredUsers.Entry(long nodeId64, byte[] nodeKey32, byte[] pubkeySpkiDer, boolean enabled, List<String> allowedCryptoModes)` → `record`
- `p2pws.sdk.center.RegisteredUsers.Entry(long nodeId64, byte[] nodeKey32, byte[] pubkeySpkiDer, boolean enabled, List<String> allowedCryptoModes)` → `public`
- `p2pws.sdk.center.RegisteredUsers.Key(byte[] id)` → `record`
- `p2pws.sdk.center.RegisteredUsers.Key(byte[] id)` → `private`
- `p2pws.sdk.center.RegisteredUsers.getByNodeId(long nodeId64)` → `Entry`
- `p2pws.sdk.center.RegisteredUsers.getByNodeKey(byte[] nodeKey32)` → `Entry`
- `p2pws.sdk.center.RegisteredUsers.put(long nodeId64, byte[] nodeKey32, byte[] pubkeySpkiDer, boolean enabled, List<String> allowedCryptoModes)` → `void`

### 3.18 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\CENTER\RSASIG.JAVA SDK
- `p2pws.sdk.center.RsaSig.RsaSig()` → `private`
- `p2pws.sdk.center.RsaSig.verifySha256WithRsa(byte[] pubkeySpkiDer, byte[] data, byte[] signature)` → `boolean`

### 3.19 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\DEMO\DEMOKEYFILE.JAVA SDK
- `p2pws.sdk.demo.DemoKeyFile.DemoKeyFile()` → `private`

### 3.20 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\DEMO\DEMOSERVERHANDLER.JAVA SDK
- `p2pws.sdk.demo.DemoServerHandler.DemoServerHandler(KeyFileProvider provider, byte[] keyId32, long keyLen, int magic, int version, int flagsPlain, int flagsEncrypted, int maxFramePayload)` → `public`
- `p2pws.sdk.demo.DemoServerHandler.channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame frame)` → `void`
- `p2pws.sdk.demo.DemoServerHandler.handleHand(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper)` → `void`
- `p2pws.sdk.demo.DemoServerHandler.sendCryptUpdate(ChannelHandlerContext ctx, int seq)` → `void`
- `p2pws.sdk.demo.DemoServerHandler.writeEncrypted(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper)` → `void`
- `p2pws.sdk.demo.DemoServerHandler.writePlain(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper)` → `void`

### 3.21 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\DEMO\HEX.JAVA SDK
- `p2pws.sdk.demo.Hex.Hex()` → `private`
- `p2pws.sdk.demo.Hex.hex(byte[] b)` → `String`

### 3.22 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\DEMO\RSAPUBLICKEYDECODER.JAVA SDK
- `p2pws.sdk.demo.RsaPublicKeyDecoder.RsaPublicKeyDecoder()` → `private`
- `p2pws.sdk.demo.RsaPublicKeyDecoder.fromSpkiDer(byte[] der)` → `PublicKey`

### 3.23 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\DEMO\SERVERCONFIG.JAVA SDK
- `p2pws.sdk.demo.ServerConfig.ServerConfig(int listenPort,
    String wsPath,
    String keyfilePath,
    int magic,
    int version,
    int flagsPlain,
    int flagsEncrypted,
    int maxFramePayload)` → `record`

### 3.24 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\DEMO\WSSERVERMAIN.JAVA SDK
- `p2pws.sdk.demo.WsServerMain.initChannel(SocketChannel ch)` → `void`

### 3.25 JAVA\SRC\MAIN\JAVA\P2PWS\SDK\DEMO\YAMLCONFIG.JAVA SDK
- `p2pws.sdk.demo.YamlConfig.YamlConfig()` → `private`
- `p2pws.sdk.demo.YamlConfig.intVal(Map<String, Object> m, String k, int def)` → `int`
- `p2pws.sdk.demo.YamlConfig.loadMap(Path path)` → `Map<String, Object>`
- `p2pws.sdk.demo.YamlConfig.loadServer(Path path)` → `ServerConfig`
- `p2pws.sdk.demo.YamlConfig.strVal(Map<String, Object> m, String k, String def)` → `String`

### 3.26 JAVA\SRC\TEST\JAVA\P2PWS\SDK\FRAMEVECTORTEST.JAVA SDK
- `p2pws.sdk.FrameVectorTest.frame_vector_001()` → `void`

### 3.27 JAVA\SRC\TEST\JAVA\P2PWS\SDK\HANDSHAKETEST.JAVA SDK
- `p2pws.sdk.HandshakeTest.decrypt_hand_ack_plain()` → `void`

### 3.28 JAVA\SRC\TEST\JAVA\P2PWS\SDK\WSBINARYCODECTEST.JAVA SDK
- `p2pws.sdk.WsBinaryCodecTest.encode_decode_roundtrip()` → `void`

### 3.29 JAVA\SRC\TEST\JAVA\P2PWS\SDK\XORVECTORTEST.JAVA SDK
- `p2pws.sdk.XorVectorTest.xor_vector_001()` → `void`

### 3.35 TS\SRC\PEERNODE.TS SDK
- `PeerNode.Promise((r)` → `void`
- `PeerNode.String(observed.addr)` → `""
    const transport = observed?.transport ? String(observed.transport) : "ws"
    if (!addr) return
    this.writeJsonFileMerge(this.presenceCachePathAbs,`
- `PeerNode.applyObservedIpToEndpoints(endpoints: Array<{ transport: string; addr: string }>, observedAddr: string)` → `void`
- `PeerNode.async(ep: any)` → `void`
- `PeerNode.connectCenter()` → `void`
- `PeerNode.dialHintEndpoints(endpoints: any[])` → `void`
- `PeerNode.endpointHost(addr: string)` → `string`
- `PeerNode.endpointKey(e: any)` → `void`
- `PeerNode.endpointScore(e: any)` → `number`
- `PeerNode.filter((e)` → `void`
- `PeerNode.isCoolDownEndpoint(e: any)` → `void`
- `PeerNode.isPrivateIpv4(host: string)` → `boolean`
- `PeerNode.loadCooldownCache()` → `void`
- `PeerNode.loadJsonFile(p: string | undefined)` → `any | null`
- `PeerNode.loadPresenceCacheObservedAddr()` → `string | null`
- `PeerNode.map((e)` → `void`
- `PeerNode.map((x)` → `void`
- `PeerNode.markEndpointFail(e: any)` → `void`
- `PeerNode.markEndpointSuccess(e: any)` → `void`
- `PeerNode.on("connection", (ws)` → `void`
- `PeerNode.on("message", (data: Buffer)` → `void`
- `PeerNode.on("open", ()` → `void`
- `PeerNode.on("message", (ev)` → `void`
- `PeerNode.on("open", ()` → `void`
- `PeerNode.on("message", (ev)` → `void`
- `PeerNode.on("error", ()` → `void`
- `PeerNode.on("close", ()` → `void`
- `PeerNode.parseInt(cfg.magic, 16)` → `(cfg.magic ?? 0x1234)
    this.version = cfg.version ?? 1
    this.flagsPlain = cfg.flags_plain ?? 4
    this.flagsEncrypted = cfg.flags_encrypted ?? 5
    this.maxFramePayload = cfg.max_frame_payload ?? 4 * 1024 * 1024
    this.listenPort = parseInt(String(cfg.listen_port ?? "0"), 10)

    const keyfileAbs = path.resolve(this.cfgDir, cfg.keyfile_path)
    this.fd = fs.openSync(keyfileAbs, "r")
    const keyHex = crypto.createHash("sha256").update(fs.readFileSync(keyfileAbs)).digest("hex")
    this.keyId = Buffer.from(keyHex, "hex")

    const privAbs = path.resolve(this.cfgDir, cfg.rsa_private_key_pem_path!)
    this.privateKeyPem = fs.readFileSync(privAbs, "utf-8")
    const privObj = crypto.createPrivateKey(this.privateKeyPem)
    const pubObj = crypto.createPublicKey(privObj)
    this.clientPubDer = pubObj.export(`
- `PeerNode.race(active.map((p, i)` → `void`
- `PeerNode.saveCooldownCache()` → `void`

... 还有 17 个方法未显示

### 3.30 TS\SRC\CLI.TS SDK
- `cli.Promise((r)` → `void`
- `cli.async(ep: any)` → `void`
- `cli.connectToPeerHandshake(endpoint: any, timeoutMs: number)` → `Promise<PeerSession | null>`
- `cli.dialHappyEyeballsHandshake(endpoints: any[], timeoutMs: number)` → `Promise<PeerSession | null>`
- `cli.endpointHostPort(addr: string)` → ``
- `cli.endpointKey(e: any)` → `void`
- `cli.endpointKey(e)` → `void`
- `cli.endpointScore(e: any)` → `number`
- `cli.filter((a)` → `void`
- `cli.filter((e)` → `void`
- `cli.finally(()` → `void`
- `cli.findIndex((e)` → `void`
- `cli.isEndpointCoolDown(e: any)` → `void`
- `cli.isPrivateIpv4(host: string)` → `boolean`
- `cli.loadCooldownCache()` → `void`
- `cli.loadJsonFile(p: string)` → `any | null`
- `cli.log(`[Hint] IncomingHint received: ${eps.map((e:any)` → `void`
- `cli.log("[Auto] No usable endpoints (cooldown)` → `void`
- `cli.log("[Auto] No usable endpoints (cooldown)` → `void`
- `cli.map((x)` → `void`
- `cli.map((e:any)` → `void`
- `cli.markEndpointFail(e: any)` → `void`
- `cli.markEndpointSuccess(e: any)` → `void`
- `cli.mergeEndpoints(a: any[], b: any[])` → `void`
- `cli.on("open", ()` → `void`
- `cli.on("message", (ev)` → `void`
- `cli.on("open", ()` → `void`
- `cli.on("message", (ev)` → `void`
- `cli.on("close", ()` → `void`
- `cli.on("error", ()` → `void`

... 还有 17 个方法未显示

### 3.31 TS\SRC\CONFIG.TS SDK
- `config.loadClientConfig(p: string)` → `ClientConfig`
- `config.parseIntMaybeHex(v: number | string | undefined, def: number)` → `number`

### 3.32 TS\SRC\FRAME.TS SDK
- `frame.decodeFrame(wsBinaryPayload: Uint8Array)` → `WireFrame`
- `frame.encodeFrame(header: WireHeader, cipherPayload: Uint8Array)` → `Uint8Array`

### 3.33 TS\SRC\HANDSHAKE.TS SDK
- `handshake.decodeHandAckPlain(root: protobuf.Root, plain: Uint8Array)` → ``
- `handshake.rsaOaepSha256Decrypt(privateKeyPem: string | Buffer, cipher: Uint8Array)` → `Uint8Array`
- `handshake.rsaOaepSha256Encrypt(publicKeyPem: string | Buffer, plain: Uint8Array)` → `Uint8Array`

### 3.34 TS\SRC\KEYID.TS SDK
- `keyid.sha256Bytes(data: Uint8Array)` → `Uint8Array`
- `keyid.sha256Hex(data: Uint8Array)` → `string`

### 3.36 TS\SRC\PROTO.TS SDK
- `proto.loadProtoRoot()` → `Promise<protobuf.Root>`

### 3.37 TS\SRC\SWARM_CLI.TS SDK
- `swarm_cli.Promise((r)` → `void`
- `swarm_cli.Promise((resolve, reject)` → `void`
- `swarm_cli.Promise((r)` → `void`
- `swarm_cli.Promise((r)` → `void`
- `swarm_cli.async()` → `void`
- `swarm_cli.await(async ()` → `void`
- `swarm_cli.buildManifest(filePathAbs: string, chunkSize: number)` → ``
- `swarm_cli.centerLookupEndpoints(params: {
  cfgPathAbs: string
  targetNodeId64: string
})` → `Promise<`
- `swarm_cli.connectPeerHandshakeOnce(params: {
  endpoint: Endpoint
  root: any
  keyId: Buffer
  clientPubDer: Buffer
  privateKeyPem: string
  nodeId64Str: string
  cryptoMode: string
  fd: number
  magic: number
  version: number
  flagsPlain: number
  flagsEncrypted: number
  handshakeTimeoutMs: number
})` → `Promise<PeerConn | null>`
- `swarm_cli.connectPeerHappyEyeballs(params: {
  endpoints: Endpoint[]
  root: any
  keyId: Buffer
  clientPubDer: Buffer
  privateKeyPem: string
  nodeId64Str: string
  cryptoMode: string
  fd: number
  magic: number
  version: number
  flagsPlain: number
  flagsEncrypted: number
  maxParallel: number
  staggerMs: number
  handshakeTimeoutMs: number
})` → `Promise<PeerConn | null>`
- `swarm_cli.endpointHostPort(addr: string)` → ``
- `swarm_cli.endpointKey(e: any)` → `void`
- `swarm_cli.endpointScore(e: any)` → `number`
- `swarm_cli.filter((s)` → `void`
- `swarm_cli.find((h)` → `void`
- `swarm_cli.findIndex((a)` → `void`
- `swarm_cli.findIndex((a)` → `void`
- `swarm_cli.findIndex((a)` → `void`
- `swarm_cli.isPrivateIpv4(host: string)` → `boolean`
- `swarm_cli.main()` → `void`
- `swarm_cli.map((s)` → `void`
- `swarm_cli.map((x)` → `void`
- `swarm_cli.map((c)` → `void`
- `swarm_cli.mergeEndpoints(a: any[], b: any[])` → `void`
- `swarm_cli.on("open", ()` → `void`
- `swarm_cli.on("message", (ev)` → `void`
- `swarm_cli.on("message", (ev2)` → `void`
- `swarm_cli.on("close", ()` → `void`
- `swarm_cli.on("error", ()` → `void`
- `swarm_cli.on("open", ()` → `void`

... 还有 21 个方法未显示

### 3.38 TS\SRC\WRAPPER.TS SDK
- `wrapper.decodeWrapper(root: protobuf.Root, data: Uint8Array)` → `P2PWrapper`
- `wrapper.encodeWrapper(root: protobuf.Root, w: P2PWrapper)` → `Uint8Array`

### 3.39 TS\SRC\XOR.TS SDK
- `xor.xorNoWrap(plain: Uint8Array, keyfile: Uint8Array, offset: number)` → `Uint8Array`

## 4. 契约对比分析
### 4.1 后端有但前端缺失的方法
- `__printonline`
- `abstractoptimizedp2pclient`
- `abstractp2pclient`
- `abstractp2pmessageserviceadapter`
- `abstractp2pserver`
- `abstractquicmessageprocessor`
- `abstractsendmesageexecutor`
- `abstracttcpmessageprocessor`
- `abstractudpmessageprocessor`
- `abstractudpmessageprocessorbytebuf`
- `abstractudpmessageprocessoroptimized`
- `acquire`
- `acquireconnection`
- `add`
- `addbuffer`
- `addmessage`
- `addpacketlistener`
- `addpendingmessage`
- `addqueueifnotfull`
- `addrecieved`
- `addrepeatedfield`
- `aesexample`
- `alert`
- `append`
- `applyadvancedtcpoptions`
- `applykeydir`
- `asyncexcute`
- `asyncprocess`
- `asyncstreamrequest`
- `attachsession`

... 还有 1007 个

### 4.2 前端有但后端缺失的方法
- `_applyobservediptoendpoints`
- `_connectcenterloop`
- `_connectcenteronce`
- `_dialhintendpoints`
- `_dialresult`
- `_endpointcooldown`
- `_equalsbytes`
- `_flushpendingconnecthints`
- `_handlecheckfile`
- `_handlecryptupdate`
- `_handledeprecated`
- `_handlefileexists`
- `_handlefilelist`
- `_handlefilemkdirs`
- `_handlefilerename`
- `_handlegetfile`
- `_handlegetfilesegments`
- `_handlehand`
- `_handlehttp`
- `_handleinfofile`
- `_handlepeerhello`
- `_handleputfile`
- `_handleputfilesegments`
- `_handleputfilesegmentscomplete`
- `_i2osp`
- `_isunder`
- `_leftpad`
- `_listen`
- `_loadcooldowncache`
- `_md5hexfile`

... 还有 253 个

### 4.3 Protobuf Message使用映射
- `CenterHello`: ✅ 已映射
- `CenterHelloAck`: ✅ 已映射
- `CenterHelloBody`: ✅ 已映射
- `ConnectHint`: ✅ 已映射
- `ConnectHintAck`: ✅ 已映射
- `CryptUpdate`: ✅ 已映射
- `Endpoint`: ✅ 已映射
- `FileDataModel`: ✅ 已映射
- `FileGetRequest`: ✅ 已映射
- `FileGetResponse`: ✅ 已映射
- `FileListEntry`: ✅ 已映射
- `FileListRequest`: ✅ 已映射
- `FileListResponse`: ✅ 已映射
- `FilePutRequest`: ✅ 已映射
- `FilePutResponse`: ✅ 已映射
- `FileRenameRequest`: ✅ 已映射
- `FileSegmentsDataModel`: ✅ 已映射
- `FilesCommandModel`: ✅ 已映射
- `GetNode`: ✅ 已映射
- `GetNodeAck`: ✅ 已映射

## 5. 集成测试建议
### 5.1 高风险不匹配项
1. 需要人工确认前后端消息处理逻辑是否一致
2. WebSocket 连接生命周期管理（握手、重连、心跳）需要端到端测试
3. 文件传输分片协议需要验证前后端实现一致性
4. 加密/混淆层（XOR）需要跨SDK验证向量一致性

### 5.2 测试优先级
| 优先级 | 测试项 | 说明 |
|--------|--------|------|
| P0 | 握手协议 | Hand/HandAck 状态机 |
| P0 | 消息发送 | 单聊/群聊消息收发 |
| P1 | 文件传输 | 分片/重组/校验 |
| P1 | 在线状态 | 心跳/ presence |
| P2 | 错误处理 | 超时/重连/降级 |

---
*报告由自动化扫描生成，需人工复核*
