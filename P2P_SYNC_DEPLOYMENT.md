# p2p-sync 文件同步服务部署文档

本文档描述如何部署与运维 `p2p-sync`（预定义目录的一对一同步服务），包含配置、权限（RSA 鉴权）、双向同步冲突处理、失败队列以及 Web 监控界面。

## 1. 组件与端口

### 1.1 进程职责

一个 `p2p-sync` 进程同时承担两类能力：

- **接收端（receiver）**：监听 `listenPort`，接收远端同步事件并落盘。
- **发送端（sender）**：监听本地目录变更，向 `remoteEndpoints` 推送同步事件。

当 `remoteEndpoints` 为空时，只启动接收端（不发送）。

入口主类：

- `javax.net.p2p.filesync.app.P2PSyncNodeMain`

### 1.2 端口

- `listenPort`：P2P 服务端口（TCP），默认 `6060`
- `monitorPort`：Web 监控端口，默认 `8090`
  - Web UI：`http://127.0.0.1:<monitorPort>/sync`

## 2. 构建与发布

### 2.1 构建

在仓库根目录执行：

```bash
mvn -pl p2p-sync -am -DskipTests=true package
```

建议在发布目录同时拷贝依赖到 `dependency/`（便于直接 `java -cp` 运行）：

```bash
mvn -pl p2p-sync -am -DskipTests=true dependency:copy-dependencies -DoutputDirectory=p2p-sync/target/dependency
```

产物位置：

- `p2p-sync/target/p2p-sync-1.0.0.jar`
- `p2p-sync/target/dependency/*.jar`

### 2.2 一键打包发布目录（包含依赖）

仓库已提供“包含依赖”的发布目录打包脚本（会输出一个可直接拷贝到服务器的目录）。

打包后目录结构：

- `app/p2p-sync.jar`
- `lib/*.jar`
- `conf/p2p-sync.yaml.example`
- `bin/run.ps1`（Windows）
- `bin/run.sh`（Linux/macOS）

#### Windows

在仓库根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File p2p-sync/deploy/pack.ps1
```

脚本会在控制台输出 dist 目录路径（形如 `dist/p2p-sync-<version>-<timestamp>`）。

#### Linux/macOS

在仓库根目录执行：

```bash
bash p2p-sync/deploy/pack.sh
```

> 如需跳过构建仅做打包复制，可在 shell 下设置 `SKIP_BUILD=1`（前提是本地已完成 `target/` 产物与 `dependency/` 依赖复制）。

### 2.3 启动命令

#### Windows（classpath 分隔符为 `;`）

```powershell
java -cp "p2p-sync/target/p2p-sync-1.0.0.jar;p2p-sync/target/dependency/*" `
  -Dp2p.sync.yaml="C:\path\to\p2p-sync.yaml" `
  javax.net.p2p.filesync.app.P2PSyncNodeMain
```

#### Linux/macOS（classpath 分隔符为 `:`）

```bash
java -cp "p2p-sync/target/p2p-sync-1.0.0.jar:p2p-sync/target/dependency/*" \
  -Dp2p.sync.yaml="/opt/p2p-sync/p2p-sync.yaml" \
  javax.net.p2p.filesync.app.P2PSyncNodeMain
```

#### 使用发布目录的启动脚本（推荐）

将打包产物目录复制到目标机器后：

- Windows：

```powershell
.\bin\run.ps1 "C:\path\to\p2p-sync.yaml"
```

- Linux/macOS：

```bash
./bin/run.sh "/opt/p2p-sync/p2p-sync.yaml"
```

## 3. 配置文件（p2p-sync.yaml）

示例参考：

- `p2p-sync/src/main/resources/p2p-sync.yaml.example`

关键字段说明：

- `taskId`：同步任务 ID（同一对同步端必须一致）
- `storeId`：存储空间 ID（当前用于接收端落盘空间选择）
- `listenPort`：接收端 TCP 监听端口
- `monitorPort`：Web 监控端口（0 表示禁用）
- `remoteEndpoints`：发送端推送的远端列表，格式 `"host:port"`
- `localDir`：被同步的本地根目录（预定义、唯一、一一对应）
- `dsHome`：同步状态目录（强烈建议与 `localDir` 分离、并持久化）
- `auth` / `authYaml`：鉴权配置注入（见下节）
- `userInfo` / `loginInfo`：用于登录与身份信息

建议的目录布局（Linux 举例）：

- `/data/sync-dir`：业务目录（`localDir`）
- `/var/lib/p2p-sync/task-<taskId>/`：状态目录（`dsHome`）
- `/etc/p2p-sync/`：配置与密钥目录

## 4. 鉴权与加密（RSA）

> 注意：本项目鉴权不使用证书链/CA/PKI/Keystore，只有 RSA 公私钥对。

### 4.1 开关与来源

启用方式：

- 在 `p2p-sync.yaml` 中设置：
  - `auth: { enabled: true, ... }`（内联）
  - 或 `authYaml: "./auth.yaml"`（外部文件路径）

`p2p-sync` 会将上述配置注入为系统属性供 `p2p-core` 读取（你只需配好 `p2p-sync.yaml`）。

参考默认鉴权模板：

- `p2p-core/src/main/resources/auth.yaml`

### 4.2 最小权限建议（auth.yaml）

建议为同步专门创建一个角色（示例）：

```yaml
enabled: true
keyDir: "/etc/p2p-sync/keys"

server:
  privateKey: "server-private.pem"
  clientPublicKeyTemplate: "client-public-keys/{userIdPrefix2}/{userId}.pub"
  roles:
    p2p_sync:
      # 同步至少需要 RPC（Apply/Finalize）与文件传输能力
      allowCategories:
        - RPC
        - FILE
        - DATA_TRANSFER
      # 可选：保留 ECHO 便于连通性诊断
      allowCommands:
        - ECHO
  defaultRoles:
    - p2p_sync

client:
  userId: "alice"
  privateKey: "client-private.pem"
  serverPublicKey: "server-public.pem"
```

> 如果你希望把权限进一步收紧，可以先只保留 `RPC`，再根据实际报错逐步加回 `FILE/DATA_TRANSFER`（以“最小可用”为准）。

## 5. 双向同步与冲突策略

### 5.1 双向部署方式

两端都作为 sender+receiver 运行：

- A 端 `remoteEndpoints` 指向 B 端 `listenPort`
- B 端 `remoteEndpoints` 指向 A 端 `listenPort`
- 两端 `taskId` 必须一致
- 两端 `localDir` 分别为自己的业务目录（各自不同路径）

### 5.2 冲突语义（后写失败）

当同一 `(taskId, path)` 上发生并发写入导致冲突：

- 接收端在 Apply 阶段返回 `write_conflict`
- 发送端将该事件移入 **失败队列**，不再自动重试
- 由用户在 Web UI 决策：
  - **重试(覆盖同步)**：把失败事件重新入队，再次同步
  - **放弃**：丢弃该失败事件，认为流程结束

## 6. 队列与失败队列

### 6.1 队列分类

Web UI 中可查看以下队列（均为持久化 DS 结构，重启不丢）：

- 新增队列（文件/目录）
- 修改队列（文件）
- 删除队列（文件/目录）
- 失败队列（文件/目录；新增/修改/删除）

### 6.2 失败原因

失败队列会记录失败原因（目前典型为 `write_conflict`）。

## 7. Web 监控界面

### 7.1 访问

- `http://127.0.0.1:<monitorPort>/sync`

### 7.2 API（可用于二次集成）

- `GET /sync/api/queues?limit=200`：获取各队列概览与样本条目
- `POST /sync/api/failed/retry?type=MODIFY&dir=false&fileId=123`：失败事件重试（覆盖同步）
- `POST /sync/api/failed/discard?type=MODIFY&dir=false&fileId=123`：失败事件放弃

## 8. 运维建议

### 8.1 多租户部署

推荐“一个租户/一个同步目录/一个进程”：

- 每个租户独立 `taskId / localDir / dsHome / keyDir / auth.yaml`
- 避免不同租户共享状态目录或密钥目录

### 8.2 停机与重启

- 正常停止：发送 SIGTERM/关闭进程即可（已注册 shutdown hook）
- 重启后：
  - inflight 事件会回灌到 active（保证未完成事件继续处理）
  - 失败队列保持不变，等待用户决策

### 8.3 备份

- 业务目录（`localDir`）建议由业务侧自行快照/备份
- 状态目录（`dsHome`）建议定期备份（便于恢复队列与失败记录）

## 9. 常见问题排查

- Web UI 无法访问：检查 `monitorPort` 是否被占用、是否被防火墙拦截。
- 同步不动：Web UI 查看新增/修改/删除队列是否增长；查看失败队列是否堆积。
- 大量失败 `write_conflict`：说明双端对同一文件存在并发写，需用户决策覆盖/放弃，或调整使用规范（尽量避免同文件双端同时写）。
