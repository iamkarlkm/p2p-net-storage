# P6a DsRegistry 7 Fallback Teardown — Tasks（6 Tasks 严格串行：Gates → 删 → 复合 → 烟测 → 归档）

> Baseline：HEAD（删前状态）
> Fail Fast Rule：Task1（G1-G5 关）任何 1 test Fail → 立即 STOP，所有后续 Tasks ABORT，**不执行任何删操作**

---

| Task ID | Task Description | Bound FR/NFR | Verification Cmd / Assertion | Exit Gate |
|---|---|---|---|---|
| **Task 1** | **删前硬关 G1-G5**：跑 `DsRegistry_P6a_GatesValidationTest` 5 tests 全绿 = G1 ROOT 已迁 / G2 keyIdToDirId 空 / G3 legacyBucket 空 / G4 复合 / G5 静态安全 | FR-1 / NFR-1 | `mvn -pl p2p-db test "-Dtest=DsRegistry_P6a_GatesValidationTest" -q` exit 0 | 5 tests 0F0E；**不绿则 本 Session 立即结束，不进 Task2** |
| **Task 2** | **删 14 处 fallback + @Deprecated makeNodeTypedId（合计 ≥60 行净减）**：按 Spec 3.1 清单（1-9 项逐项删）；删后 readNodeMeta/writeNodeMeta 简化为 pure typed；ensureRootInitialized 两处 legacyRing（L192-196 + L204-208）删；mkdirs/mkdirsLocked 两处 keyIdToDirId fallback（L314-322 / L350-358）删；remove 12 行 legacy fallback（L823-833）删；listChildren L622-624 if==null fallback 删；makeNodeTypedId L155-158 3行 删 | FR-2/FR-3/FR-4/FR-7 / NFR-2 | `mvn -pl p2p-db -am compile test-compile -DskipTests -q` exit 0；`git diff --stat p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistry.java` deletions>insertions且行数净减≥60；diff+段 grep `if\|else\|for\|while\|try\|catch\|new\s`=0 hit | compile exit0 + 行数净减≥60 + 零逻辑新增全对 = 进 Task3 |
| **Task 3** | **删后复合验证 T02 新增**：`DsRegistry_P6a_PostDelete_Compound_T02_Test.java`：新 tmp → mkdirs 10 层 × putLong 50 条(每i层 5条) → migrateLegacyIds() 两次（第一次0 第二次0 因 G4 已关）→ remove前50% 25 条 → get 剩余25 value 全对 + listChildren 每层 size 对 + findByPrefix("/d0/d1/d2") 命中数对 + mkdirs 已存在路径（乐观命中直接 return）；JDK 禁止 stream().toList()，listKeys 返回 mutable ArrayList | FR-5/FR-6 / NFR-1 | mvn surefire T02 exit0 PASS 0F0E；≥8 assertions | T02 全绿进 Task4 |
| **Task 4** | **烟测 ≥49 PASS 0F0E BUILD SUCCESS**：14+13+12+6+1+1+4 = ≥51 PASS（DsRegistry + P21-P52 + P7A1 + P6ab + 专项） | FR-6/NFR-1/NFR-3 | 用 Spec 7.④ power-shell 命令链 exit 0 + Tests passed ≥49 | BUILD SUCCESS + Tests ≥49 → 进 Task5 |
| **Task 5** | **Final 12 零改文件 Review + 三件套 0 破坏**：① `git diff --stat HEAD <7底层>` 全 0；② `git diff HEAD DsRegistry.java | grep -E "^\+\s*.*(makeDirTypedId|makeLeafTypedId|leafIdToValueOrId)"` = 0 行（三件套本体未改）；③ P7 四专项（Tgt3 + Phase1 + Tgt2 + Tgt4）各 1 test 代表绿 | FR-8 / NFR-3 | 三点全通过 + Review Result=pass | pass → 进 Task6 |
| **Task 6** | **归档 + Brain**：① AGENTS Invariant 追加 P6a Teardown 6 条（G1-G5通过/14fallback光/makeNodeTypedId删/migrate保留/P4三件套零改/行数净减≥60）；② current-state 追加 VERIFIED CLOSED Block（Gates + 删清单 + 复合验证 + 烟测结果 + Joint Cmd 5 条）；③ `brain context audit --since HEAD~1` + distill + session finish exit 0 | 项目流程 + 不变式锁死 | AGENTS 6 Invariant 命中 + current-state Block ≥80 行 + brain session finish exit 0 | CLOSED=done |

---

## 防错（Fail-Stop 硬机制）

- Task1 G1-G5 任何 1 红 → 立即：Task2-6 全部 IN_PROGRESS→FAILED，本 Session 结束不删任何代码（用户需先手动处理 legacy 数据）
- Task2 compile 失败 → 先 `mvn test-compile` 定位具体删错处（漏删调用方引用）→ 修到 exit 0（不允许新增 workaround 方法绕过 NG-2）
- Task4 烟测红（FAILURES > 0）→ 立即 git stash 暂存删代码回退 0 状态，定位具体是哪个 fallback 在删前 G4 未覆盖场景命中（补 gate 再删）
