# scripts

协议相关辅助脚本。

## 1) 校验 test-vectors

```powershell
python .\p2p-ws-protocol\scripts\verify_vectors.py
```

## 2) 生成 protobuf（可选）

需要本机安装 `protoc`。

```powershell
.\p2p-ws-protocol\scripts\gen_java.ps1
.\p2p-ws-protocol\scripts\gen_python.ps1
```

