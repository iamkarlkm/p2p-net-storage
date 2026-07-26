---
updated: "2026-05-11T10:54:29Z"
---
# Auth RBAC：按角色与服务类别授权

## What Changed

- 鉴权从“按 userId 逐个列 allowCommands”扩展为 RBAC：在 `auth.yaml` 中声明 `roles`（角色策略）与 `roleBindings/defaultRoles/userRoles`（角色分配），并支持按 `P2PCommand.category`（`P2PServiceCategory`）进行授权。
- `AuthEnforcer` 在服务端统一入口处优先走 role-based 决策：聚合角色策略中的 `allowCategories/allowCommands` 判断是否放行；在未配置 roles 时保持 legacy `allowCommands` 行为不变。
- 更新 `p2p-core/src/main/resources/auth.yaml` 示例为“基于角色与类别”的配置结构，降低百万用户场景下的维护成本（通过默认角色与规则绑定替代逐用户命令清单）。
- 客户端公钥来源支持从“YAML 内 per-user clientPublicKeys map”迁移到 resolver：新增 `clientPublicKeyTemplate/clientPublicKeyDir/clientPublicKeyBindings`，服务端在 HAND/LOGIN 验签与解密阶段按 userId 动态解析公钥路径/值，避免百万用户把公钥塞进 YAML。

## Verification

- `mvn -q -pl p2p-core "-Dtest=javax.net.p2p.auth.AuthEnforcerRoleCategoryTest" test`
- `mvn -q -pl p2p-core test`
