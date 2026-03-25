# IMCommand 枚举迁移说明

## 概述
将 `IMCommand.java` 中的枚举常量转移到 `P2PCommand.java` 中，以 `IM_` 开头，并去掉描述字段。

## 变更内容

### 1. P2PCommand.java
在 `src/main/java/javax/net/p2p/api/P2PCommand.java` 中添加了以下枚举常量：

#### 用户相关命令 (10000-10004)
- `IM_USER_LOGIN(10000)` - IM_用户登录
- `IM_USER_LOGOUT(10001)` - IM_用户登出
- `IM_USER_LIST(10002)` - IM_获取在线用户列表
- `IM_USER_HEARTBEAT(10003)` - IM_用户心跳
- `IM_USER_STATUS_UPDATE(10004)` - IM_用户状态更新

#### 聊天相关命令 (11000-11007)
- `IM_CHAT_SEND(11000)` - IM_发送聊天消息
- `IM_CHAT_RECEIVE(11001)` - IM_接收聊天消息
- `IM_CHAT_ACK(11002)` - IM_消息确认
- `IM_CHAT_STATUS_UPDATE(11003)` - IM_消息状态更新
- `IM_CHAT_HISTORY_REQUEST(11004)` - IM_历史消息请求
- `IM_CHAT_HISTORY_RESPONSE(11005)` - IM_历史消息响应
- `IM_CHAT_RECALL(11006)` - IM_消息撤回
- `IM_CHAT_FORWARD(11007)` - IM_消息转发

#### 群组相关命令 (12000-12010)
- `IM_GROUP_CREATE(12000)` - IM_创建群组
- `IM_GROUP_DISMISS(12001)` - IM_解散群组
- `IM_GROUP_JOIN(12002)` - IM_加入群组
- `IM_GROUP_LEAVE(12003)` - IM_离开群组
- `IM_GROUP_LIST(12004)` - IM_获取群组列表
- `IM_GROUP_MEMBERS(12005)` - IM_获取群组成员
- `IM_GROUP_MESSAGE_SEND(12006)` - IM_群组消息发送
- `IM_GROUP_MESSAGE_RECEIVE(12007)` - IM_群组消息接收
- `IM_GROUP_SET_ADMIN(12008)` - IM_设置群组管理员
- `IM_GROUP_REMOVE_MEMBER(12009)` - IM_移除群组成员
- `IM_GROUP_UPDATE_INFO(12010)` - IM_更新群组信息

#### 系统状态命令 (13000-13002)
- `IM_SYSTEM_STATUS(13000)` - IM_系统状态查询
- `IM_CONNECTION_TEST(13001)` - IM_连接测试
- `IM_ERROR_RESPONSE(13002)` - IM_错误响应

### 2. 删除文件
- 删除 `src/main/java/javax/net/p2p/im/IMCommand.java`

### 3. 更新的文件

#### UserLoginProcessor.java
- 添加导入：`import javax.net.p2p.api.P2PCommand;`
- 将所有 `IMCommand.` 引用改为 `P2PCommand.IM_`
- 例如：`IMCommand.USER_LOGIN` → `P2PCommand.IM_USER_LOGIN`

#### IMServerProcessor.java
- 添加导入：`import javax.net.p2p.api.P2PCommand;`
- 将所有 `IMCommand.` 引用改为 `P2PCommand.IM_`
- 更新命令注册代码

#### IMServer.java
- 更新日志输出，移除对 `IMCommand` 方法的调用

#### ChatClient.java
- 添加导入：`import javax.net.p2p.api.P2PCommand;`
- 将所有 `IMCommand.` 引用改为 `P2PCommand.IM_`
- 更新命令设置代码，例如：
  - `IMCommand.CHAT_SEND.getCode()` → `P2PCommand.IM_CHAT_SEND.getValue()`

#### ChatMessageProcessor.java
- 添加导入：`import javax.net.p2p.api.P2PCommand;`
- 将所有 `IMCommand.` 引用改为 `P2PCommand.IM_`
- 更新命令注册和设置代码

#### IMCommandTest.java
- 完全重写测试文件以使用 `P2PCommand` 枚举
- 移除对已删除方法的测试（如 `getDescription()`, `isUserCommand()` 等）
- 添加新的测试用例验证命令码和命名规范

## 关键变更点

### 1. 枚举命名
- 所有 IM 命令都以 `IM_` 前缀开头
- 例如：`USER_LOGIN` → `IM_USER_LOGIN`

### 2. 方法调用变更
- `getCode()` → `getValue()` (P2PCommand 的方法)
- 移除了 `getDescription()`, `isUserCommand()`, `isChatCommand()` 等方法
- 移除了 `fromCode()`, `isValidCode()` 等静态方法
- 移除了 `getMinCode()`, `getMaxCode()`, `getUserCommands()` 等实用方法

### 3. 命令码范围
- 用户命令：10000-10004 (5个)
- 聊天命令：11000-11007 (8个)
- 群组命令：12000-12010 (11个)
- 系统命令：13000-13002 (3个)
- 总计：27个 IM 命令

## 优势

1. **统一管理**：所有 P2P 命令都在一个枚举中，便于维护
2. **避免继承**：Java 枚举不支持继承，避免了设计限制
3. **命名清晰**：使用 `IM_` 前缀区分 IM 命令和其他 P2P 命令
4. **简化设计**：移除了复杂的辅助方法，使代码更简洁

## 注意事项

1. 如果其他代码使用了 `IMCommand` 的辅助方法（如 `isUserCommand()`），需要自行实现这些逻辑
2. 命令码的范围验证需要手动实现
3. 原有的 `IMCommand` 工具方法已不存在，如果需要可以添加到 `P2PCommand` 中

## 兼容性

此变更是破坏性的，所有使用 `IMCommand` 的代码都需要更新。以下是需要更新的内容：

1. 导入语句
2. 枚举常量引用（添加 `IM_` 前缀）
3. 方法调用（`getCode()` → `getValue()`）
4. 辅助方法调用（移除或自行实现）
