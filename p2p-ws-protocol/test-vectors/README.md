# Test Vectors

本目录用于跨语言一致性测试（不依赖具体语言实现）。

建议每个 SDK 至少实现：
- XOR 向量：给定 keyfile bytes、offset、plain，输出 cipher 必须一致
- 帧向量：给定 header 字段与 cipherPayload，解析结果必须一致

