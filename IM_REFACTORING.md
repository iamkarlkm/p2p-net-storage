# 即时通讯模块重构说明

## 概述
根据P2P网络体系架构标准流程重构即时通讯相关业务模块，符合以下架构规则：
1. 每个操作都对应一个P2PCommand的命令
2. 每个命令都对应一个处理器handler和一个泛型数据model
3. 系统底层会自动序列化/反序列化该泛型数据
4. 通过P2PWrapper的data泛型字段传递数据
5. 使用方法 `<T> P2PWrapper.build(P2PCommand command, T data)` 创建请求和响应
6. 每个处理器都必须实现接口P2PCommandHandler
7. P2P节点启动会自动扫描实现了P2PCommandHandler接口的独立类并自动注册

## 新增文件结构

### 1. 数据模型 (model包)
创建以下数据模型类，每个命令对应独立的请求和响应模型：

#### 用户相关模型
- `UserLoginRequest.java` - 用户登录请求
  - userId, username, password, nickname, avatar, clientIp, clientPort, timestamp

- `UserLoginResponse.java` - 用户登录响应
  - success, message, userId, username, nickname, onlineUserCount, onlineUserIds, timestamp

- `UserListResponse.java` - 获取用户列表响应
  - success, message, totalCount, users, timestamp

- `UserHeartbeat.java` - 用户心跳请求/响应
  - userId, lastActiveTime, heartbeatTime, onlineUserCount

#### 聊天相关模型
- `ChatSendRequest.java` - 聊天消息发送请求
  - message (ChatMessage), timestamp

- `ChatResponse.java` - 聊天消息响应（通用）
  - success, message, messageId, timestamp

- `ChatMessageAck.java` - 消息确认请求/响应
  - messageId, ackType, ackTime, userId

- `ChatHistoryRequest.java` - 聊天历史消息请求
  - targetId, isGroup, startTime, endTime, limit, offset

- `ChatHistoryResponse.java` - 聊天历史消息响应
  - success, message, userId, targetId, isGroup, startTime, endTime, messages, hasMore, timestamp

### 2. 命令处理器 (handler包)
创建以下独立的Handler类，每个类实现P2PCommandHandler接口：

#### 用户相关处理器
- `UserLoginHandler.java` - 处理用户登录 (IM_USER_LOGIN)
  - 实现P2PCommandHandler接口
  - 处理登录请求，验证用户信息
  - 注册用户到UserManager
  - 返回UserLoginResponse

- `UserLogoutHandler.java` - 处理用户登出 (IM_USER_LOGOUT)
  - 实现P2PCommandHandler接口
  - 从UserManager移除用户
  - 返回登出结果

- `UserListHandler.java` - 处理获取用户列表 (IM_USER_LIST)
  - 实现P2PCommandHandler接口
  - 从UserManager获取在线用户
  - 返回UserListResponse

- `UserHeartbeatHandler.java` - 处理用户心跳 (IM_USER_HEARTBEAT)
  - 实现P2PCommandHandler接口
  - 更新用户活动时间
  - 返回心跳响应

#### 聊天相关处理器
- `ChatSendHandler.java` - 处理发送聊天消息 (IM_CHAT_SEND)
  - 实现P2PCommandHandler接口
  - 验证接收者是否在线
  - 记录消息到MessageAckManager
  - 转发消息给接收者
  - 返回ChatResponse

- `ChatReceiveHandler.java` - 处理接收聊天消息 (IM_CHAT_RECEIVE)
  - 实现P2PCommandHandler接口
  - 处理接收到的聊天消息
  - 更新消息状态
  - 返回接收确认

- `ChatAckHandler.java` - 处理消息确认 (IM_CHAT_ACK)
  - 实现P2PCommandHandler接口
  - 处理消息确认请求
  - 更新消息状态
  - 返回确认结果

- `ChatHistoryRequestHandler.java` - 处理历史消息请求 (IM_CHAT_HISTORY_REQUEST)
  - 实现P2PCommandHandler接口
  - 查询历史消息记录
  - 返回ChatHistoryResponse

## 关键设计变更

### 1. Handler类设计
每个Handler类都实现P2PCommandHandler接口：

```java
public interface P2PCommandHandler {
    P2PCommand getCommand();
    P2PWrapper process(P2PWrapper request);
}
```

实现示例：
```java
@Slf4j
public class UserLoginHandler implements P2PCommandHandler {
    
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.IM_USER_LOGIN;
    }
    
    @Override
    public P2PWrapper process(P2PWrapper<UserLoginRequest> request) {
        // 处理逻辑
        UserLoginResponse response = new UserLoginResponse();
        // 设置响应数据
        
        return P2PWrapper.build(P2PCommand.IM_USER_LOGIN, response);
    }
}
```

### 2. 数据模型设计
每个数据模型都实现Serializable接口，支持自动序列化：

```java
public class UserLoginRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String userId;
    private String username;
    // 其他字段
    
    // Getter和Setter方法
}
```

### 3. 创建请求和响应
使用P2PWrapper.build()方法：

```java
// 创建请求
UserLoginRequest request = new UserLoginRequest();
request.setUserId("user001");
request.setUsername("张三");
P2PWrapper<UserLoginRequest> requestWrapper = P2PWrapper.build(P2PCommand.IM_USER_LOGIN, request);

// 创建响应
UserLoginResponse response = new UserLoginResponse();
response.setSuccess(true);
response.setMessage("登录成功");
P2PWrapper<UserLoginResponse> responseWrapper = P2PWrapper.build(P2PCommand.IM_USER_LOGIN, response);
```

### 4. 自动注册机制
系统会自动扫描实现了P2PCommandHandler接口的独立类并注册处理器：
- 无需手动注册
- Handler类必须放在独立文件中
- 通过getCommand()方法指定处理的命令类型

## 优势

1. **符合架构标准**：完全遵循P2P网络体系架构标准流程
2. **类型安全**：使用泛型确保数据类型正确
3. **自动注册**：系统自动扫描注册，无需手动管理
4. **职责清晰**：每个Handler只处理一个命令，职责单一
5. **易于扩展**：新增命令只需创建新的Handler类和数据模型
6. **统一规范**：所有命令遵循相同的处理模式

## 使用示例

### 发送用户登录请求
```java
// 创建登录请求数据
UserLoginRequest loginRequest = new UserLoginRequest();
loginRequest.setUserId("user001");
loginRequest.setUsername("张三");
loginRequest.setPassword("encrypted_password");

// 创建P2PWrapper请求
P2PWrapper<UserLoginRequest> request = P2PWrapper.build(
    P2PCommand.IM_USER_LOGIN, 
    loginRequest
);

// 系统会自动：
// 1. 序列化UserLoginRequest
// 2. 发送到网络
// 3. 接收UserLoginResponse
// 4. 反序列化为UserLoginResponse对象
```

### 发送聊天消息
```java
// 创建聊天消息
ChatMessage chatMessage = new ChatMessage("user001", "user002", "你好");
chatMessage.setMessageType(MessageType.TEXT);

// 创建发送请求数据
ChatSendRequest sendRequest = new ChatSendRequest(chatMessage);

// 创建P2PWrapper请求
P2PWrapper<ChatSendRequest> request = P2PWrapper.build(
    P2PCommand.IM_CHAT_SEND, 
    sendRequest
);

// 系统会自动处理序列化和网络传输
```

## 待完善事项

1. **网络层集成**：Handler中的process方法需要集成实际的网络发送逻辑
2. **上下文传递**：需要获取ChannelHandlerContext以获取发送者地址等信息
3. **历史消息存储**：ChatHistoryRequestHandler需要从数据库或缓存获取历史消息
4. **消息转发**：ChatSendHandler需要实现实际的消息转发逻辑
5. **群组功能**：群组相关的Handler还需要补充实现

## 兼容性说明

旧的处理器类（UserLoginProcessor, ChatMessageProcessor, IMServerProcessor等）保留但不再使用，新的架构使用独立的Handler类。建议逐步迁移到新架构。

## 下一步工作

1. 完善群组相关的Handler
2. 实现系统状态相关的Handler
3. 集成实际的网络发送逻辑
4. 完善历史消息查询功能
5. 添加单元测试
