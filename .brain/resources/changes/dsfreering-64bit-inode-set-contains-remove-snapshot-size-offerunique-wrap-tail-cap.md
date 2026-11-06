# DsFreeRing 64-bit inode Set 语义（contains/remove/snapshot/size）+ offerUnique 环形查重修复 + tail=cap 死线清除

**Verification Command（最终验收）**：
```bash
mvn -pl p2p-db test \
  -Dtest=DsFreeRingAsInodeSetTest,DsObjectFreeRingRafTest \
  -DfailIfNoTests=false
```

**Result**：`Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`

- 8/8 新 Set 测试（`DsFreeRingAsInodeSetTest`：线性 contains、wrap-around 查重、HashSet oracle 一致性、线性 remove 任意位置、wrap remove 任意位置、持久化 reload close→open、旧 Queue API 回归、wrap 扩容 FIFO 顺序保持）
- 1/1 旧 Queue 回归（`DsObjectFreeRingRafTest.testOfferPollFifoAndPersistAndExpand`：initialCap=2 offer 3 扩容 poll 3，close→reopen offer 2 poll 2）

## 关键历史命令

**先定位 bug（println 调试阶段）**：
```bash
mvn -pl p2p-db -q test-compile
mvn -pl p2p-db -q \
  -Dexec.mainClass=ds._DebugFreeRing \
  -Dexec.classpathScope=test \
  dependency:build-classpath exec:java
```
诊断证据：`[expand BEFORE] count=2 cap=2 head=0 tail=0 head<=tail=true` + snapshot 读 0 → 证明"满状态 head==tail 触发 head<=tail 分支漏读"根因成立。

## 回归/再验证

修改 helper（findSlotLocked / forEachSlotLocked / removeSlotLocked / expand）后，同上述 verify 命令 9/9 0F0E 即可。

## 对应文档

- 实现变更细节 + 不变式：`dsfreering-set-semantics.md`
- 目录 inode 集合使用 Recipe：`dsfreering-inode-dirset-recipe.md`
- AGENTS.md 两条 DsFreeRing invariant（见项目根 AGENTS.md L153-L154）
