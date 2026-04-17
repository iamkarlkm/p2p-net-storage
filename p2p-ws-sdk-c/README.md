# p2p-ws-sdk-c

C 语言版本（demo 级别）：实现了 WebSocket(Binary) + 最小 protobuf 编解码 + RSA-OAEP(SHA-256) 解密 + RSA-SHA256(PKCS#1 v1.5) 签名 + keyfile XOR。

支持 demo：
- Center 入网：HAND -> CENTER_HELLO -> GET_NODE
- Peer 直连：HAND -> PEER_HELLO -> FILE_PUT/FILE_GET
- Center 中继：RELAY_DATA(-11012)
- Peer 常驻：WebSocket Server + HAND/PEER_HELLO + FILE_PUT/FILE_GET

当前实现依赖 Windows CNG（bcrypt/ncrypt），仅支持在 Windows 上构建运行。

## 构建（PowerShell + MinGW64 gcc）

```powershell
cd p2p-ws-sdk-c
$c="C:\msys64\mingw64\bin\gcc.exe"
& $c -Iinclude -std=gnu11 -O2 -c src/p2p_ws.c src/p2pws_buf.c src/p2pws_pb.c src/p2pws_ws.c src/p2pws_crypto.c src/p2pws_yaml.c src/p2pws_messages.c
& $c -Iinclude -std=gnu11 -O2 -o p2pws_center_join.exe demo/center_join.c p2p_ws.o p2pws_buf.o p2pws_pb.o p2pws_ws.o p2pws_crypto.o p2pws_yaml.o p2pws_messages.o -lws2_32 -lbcrypt -lncrypt
& $c -Iinclude -std=gnu11 -O2 -o p2pws_peer_connect.exe demo/peer_connect.c p2p_ws.o p2pws_buf.o p2pws_pb.o p2pws_ws.o p2pws_crypto.o p2pws_yaml.o p2pws_messages.o -lws2_32 -lbcrypt -lncrypt
& $c -Iinclude -std=gnu11 -O2 -o p2pws_relay_echo.exe demo/relay_echo.c p2p_ws.o p2pws_buf.o p2pws_pb.o p2pws_ws.o p2pws_crypto.o p2pws_yaml.o p2pws_messages.o -lws2_32 -lbcrypt -lncrypt
& $c -Iinclude -std=gnu11 -O2 -o p2pws_peer_node.exe demo/peer_node.c p2p_ws.o p2pws_buf.o p2pws_pb.o p2pws_ws.o p2pws_crypto.o p2pws_yaml.o p2pws_messages.o p2pws_fs.o -lws2_32 -lbcrypt -lncrypt -lcrypt32
```

## 运行示例

```powershell
cd p2p-ws-sdk-c
.\p2pws_center_join.exe ..\p2p-ws-protocol\examples\peer1_c.yaml
.\p2pws_peer_connect.exe ..\p2p-ws-protocol\examples\peer2_c.yaml 1
.\p2pws_relay_echo.exe ..\p2p-ws-protocol\examples\peer2_c.yaml 1
.\p2pws_peer_node.exe ..\p2p-ws-protocol\examples\peer1_c.yaml
```
