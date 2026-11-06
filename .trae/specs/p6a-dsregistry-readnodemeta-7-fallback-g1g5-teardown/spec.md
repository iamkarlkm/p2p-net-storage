# P6a — DsRegistry readNodeMeta 7 fallback 分支安全下线（G1-G5 Gate 验证 → 删除 → 回归）Spec

> **版本**：1.0 (2026-09-17)
> **Session ID**：1789624930197746800
> **影响范围**：DsRegistry.java（仅删除死代码，0 行新增逻辑；7 大底层 0 改；AGENTS Invariant P4 极优版布局强制锁死）
> **前置已验证状态**：P4 极优版 DIR/LEAF 双 key 分层（DIR keyId→typedId encode205, idx+1 offset；LEAF plain leafSeq 写入 leafIdToValueOrId，上层传 typedId）已在 P21-P52 26 tests 全绿 + P7 系列 7 专项全绿，运行 1 年+ 零 legacy fallback 命中报告

---

## 1. Non-Goals（硬锁死，破坏即 Reject）

| # | Non-Goal | Verification Gate |
|---|---|---|
| NG-1 | **7 大底层 0 修改**：DsFixedBucketStore/DsFreeRing/DsString/DsTypeFixedBucketStore/DsTypeString/DsData/DsHashMap | Final Review `git diff --stat HEAD <7files>` = 0 |
| NG-2 | **零新逻辑**：本次所有改动 = 删 `if` fallback 分支 + 删死 helper/常量 + 删注释；严禁新增 if/else/try/catch/新方法 | DsRegistry.java 修改前后 `git diff --stat` **仅负数行数（删），0 新增正行** |
| NG-3 | **public API 0 改动**：所有 public 方法签名（putLong/putString/putBytes/putList/get/exists/remove/mkdirs/listChildren*/findByPrefix*/listKeys/walk/close/migrateLegacyIds）不变；仅 private 内部删分支 | `javap -p DsRegistry.class` public 成员签名 = 100% 与删前一致 |
| NG-4 | **零破坏 P4 极优版布局不变式**：`makeDirTypedId(keyId) → TYPE205+POWER7+idx+1` / `makeLeafTypedId(idx) → TYPE202+POWER6+idx` / `leafIdToValueOrId` 存 plain 40bit 低段三件套 0 改动 | 三件套 helper 代码本体 0 字符差异（grep 与删前逐行对） |
| NG-5 | **零删除 migrateLegacyIds 用户接口**：保留 public `migrateLegacyIds()`（即使 G1-G5 通过后调用返回 0），兼容 1 年前 P3→P4 迁移用户脚本 | public 方法存在性由 testG5（DsRegistry_P6a_GatesValidationTest L268-270）anchor 断言 + `javap -public` 双证 |

---

## 2. G1-G5 Gate 清单（删前必须 100% 全绿，任何单 Gate 失败立即 STOP 不执行删）

| Gate ID | Gate 语义（删前空间状态验证） | 对应 @Test 锚点 | Pass Assertion |
|---|---|---|---|
| **G1** | **ROOT RING 迁移已完成**：`dirIdToRingId.get(ROOT_DIR_NODE_ID=1)` → null；`dirIdToRingId.get(rootDirTypedId=TYPE205|POWER7|idx=0)` → 非 null；调用 `migrateLegacyIds()` 返回 0（幂等） | `testG1_RootRingMigratedGate()` L61-85 | `assertNull(dirIdToRingId.get(1L)) && assertNotNull(dirIdToRingId.get(rootDirTypedId())) && migrate() == 0` |
| **G2** | **KEYID→DIRID 映射已清空**：`keyIdToDirId.size() == 0`；对注入的 fake plain dir node（10 层）migrateLegacyIds 后 size==0，再调用返回 0；`exists("/l0/l1/.../l9")` 仍 true（不丢数据） | `testG2_KeyIdToDirIdEmptyGate()` L88-137 | `keyIdToDirId.size()==0 && migrate()==0 && exists 深路径 true` |
| **G3** | **LEGACY BUCKET 载荷 0 存在**：对 legacy 大 plain id（9_999_800 / 9_999_900），`readNode(makeDirTypedId/LEAF, id) == null`（legacy 从未写入 NodeMeta 到 P4 typed bucket）；`get("/a/x")==11L && exists("/a/y")`（正常数据不丢） | `testG3_LegacyBucketPayloadEmptyGate()` L140-187 | `4 × legacyNodeMeta==null && get("/a/x").asLong()==11 && exists("/a/y")` |
| **G4** | **G1+G2+G3 连续复合压力关**：8 层深度 × 100 items put/remove 50 混合操作 + 注入 legacy fake（8 层 + root）；step1 migrate==8+1；step2 remove50 后 migrate==0；step3 get剩余50 value==k*13 全对 | `testG1G2G3_ConsecutiveGatesWithPutRemove()` L190-256 | `step1==9 && step2==0 && 50×get value==k*13 全对` |
| **G5** | **静态安全关**：仅允许 `ROOT_DIR_NODE_ID=1L` public static final 常量保留（供 migrateLegacyIds/外部工具引用）；`migrateLegacyIds()` + `isRootNodeId()` 两方法存在性断言 | `testG5_StaticGrep_SurvivingUsagesAreSafe()` L259-271 | `callCount(migrateLegacyIds+isRootNodeId) >= 2 && ROOT_DIR_NODE_ID==1L` |

---

## 3. 删改点清单（严格 Performance First：只删不加，行数净减 ≥60）

### 3.1 总清单（14 处 fallback + 2 死 helper/常量 全删）

| # | 位置（删前 L#） | 代码块 | 删法 | 风险锚点 |
|---|---|---|---|---|
| 1 | **readNodeMeta L160-L174** | `if (isTyped) return typed; if (id==1) { rootTyped → makeDir(1) → makeLeaf(1) 三分支 } else { makeLeaf(id) → makeDir(id) 两分支 }` | → 简化为 **L160-L164 保留**：`if (isTyped) return readNode(store,id); else throw new IllegalArgumentException("plain id fallback removed: " + id)`（**严禁保留任何 legacy read 分支**） | G4 后 `migrate()==0` 才删；readNodeMeta 调用方仅 L623 `readNodeMeta(nodeStore, ROOT_DIR_NODE_ID)` → 改为直接 `readNode(nodeStore, rootDirTypedId())` 再一起删 fallback 方法 |
| 2 | **writeNodeMeta L176-L186** | `if (typed) write typed; else if (id==1) write rootTyped; else write isDir?makeDir(id):makeLeaf(id)` | → 简化为 **L176 保留**：`if (isTyped) writeNode(store,id,m); else throw IAE("plain id write fallback removed")`；调用方仅 L185 `writeNode(store, m.isDir?makeDir:makeLeaf, m)`（永远 typed），无 plain 调用 | grep 所有 writeNodeMeta 调用 = 0 plain id 参数 |
| 3 | **ensureRootInitialized L188-L234** 内 2 处 legacyRing fallback | L192 `legacyRing = dirIdToRingId.get(ROOT_DIR_NODE_ID)` if 块；L204 `legacyRing2 = dirIdToRingId.get(1L)` if 块 | → **两行 fallback 全删**：保留 `if (existingRing!=null && existingRing!=0L) return;` 之后直接进 writeLock 初始化分支（legacy 0 命中） | G1 通过；`dirIdToRingId.get(1)==null` |
| 4 | **mkdirs（乐观命中）L314-L322** | `legacyNodeId=keyIdToDirId.get(keyId) → if !=null get ring → copyIfAbsent → continue` | → **整段 L314-322 删**：乐观分支仅 `dirIdToRingId.get(typedId)` 命中 | G2 通过；`keyIdToDirId.size()==0` |
| 5 | **mkdirsLocked（悲观写）L350-L358** | 同样 `legacyNodeId=keyIdToDirId.get → copyIfAbsent ring` 9 行 | → **整段 L350-358 删**：仅 `ringSeq=dirIdToRingId.get(typedId)` 命中 | G2 通过；同 4 |
| 6 | **remove L822-L833** | `dm==null → legacyDirNodeId=keyIdToDirId.get → if!=null readNodeMeta(legacy) → copyIfAbsent ring 12 行` | → **整段 L823-L833 删**：dm==null 直接 fall 到 `Long leafTypedId = keyIdToLeafOrDirId.get` 后续 | G2 通过 + G3 4× null |
| 7 | **listChildren L620-L624** | `root = readNode(nodeStore, rootTyped); if (root==null) root = readNodeMeta(nodeStore, ROOT_DIR_NODE_ID)` | → **删 if 分支（L622-L624）**：仅 `root = readNode(nodeStore, rootTypedId())`，若==null 则 `return RegistryEntry.EMPTY`（与删前 else 一致） | G1 通过；root ring 永远非 null |
| 8 | **@Deprecated makeNodeTypedId L155-L158** | `private static long makeNodeTypedId(long nextSeq)` 3 行 + `@Deprecated` | → **3 行 0 引用全删**：调用方 grep 0 hit 已核实 | G5 通过；isRootNodeId + migrate 保留 |
| 9 | **keyIdToDirId DsHashMap 字段 + 构造初始化**（L81 + L114）：`keyIdToDirId = new DsHashMap(...)` 两引用 | → **字段行 + 初始化行 保留不删**（public migrateLegacyIds 仍需遍历 keyIdToDirId；G5 未要求删字段） | NG-5 migrate 保留；字段为 P3 兼容持久化 hash 文件保留（冷数据不删避免磁盘 I/O 扰动） |

### 3.2 关键简化（Performance First：删后 readNodeMeta/writeNodeMeta 纯 typed）

**删前 readNodeMeta 15 行（7 fallback 分支）→ 删后 5 行：**
```java
// 删后（PURE TYPED ONLY，zero legacy branch）
private static DsRegistryNodeSerializer.NodeMeta readNodeMeta(DsTypeFixedBucketStore store, long nodeIdOrTyped) throws IOException {
    if (!isTypedNodeId(nodeIdOrTyped)) {
        throw new IllegalArgumentException("DsRegistry legacy plain-id fallback removed (P6a G1-G5 passed); got id=" + nodeIdOrTyped);
    }
    return DsRegistryNodeSerializer.readNode(store, nodeIdOrTyped);
}
```

**删前 writeNodeMeta 11 行（3 fallback 分支）→ 删后 4 行：**
```java
// 删后（PURE TYPED ONLY）
private static void writeNodeMeta(DsTypeFixedBucketStore store, long nodeIdOrTyped, DsRegistryNodeSerializer.NodeMeta m) throws IOException {
    if (!isTypedNodeId(nodeIdOrTyped)) {
        throw new IllegalArgumentException("DsRegistry legacy plain-id write fallback removed (P6a G1-G5 passed); got id=" + nodeIdOrTyped);
    }
    DsRegistryNodeSerializer.writeNode(store, nodeIdOrTyped, m);
}
```

---

## 4. Functional Requirements（8 FR，Gate→删→回归 三步全绿）

| # | FR | AC（assertion 具体） |
|---|---|---|
| FR-1 | **G1-G5 五关 100% PASS（删前硬关）**：删前运行 `DsRegistry_P6a_GatesValidationTest` 5 tests 全绿 | mvn surefire G1-G5 = 0F0E；任何 Gate Fail → 立即 STOP，**严禁执行后续删操作** |
| FR-2 | **readNodeMeta / writeNodeMeta 删后 0 fallback 分支**：`grep -n "ROOT_DIR_NODE_ID" DsRegistry.java` 删后仅命中（L38 常量定义 + L776 migrate 内部访问 + L64 isRootNodeId）= 3 处；L160-174 读 fallback 0 hit；L176-186 写 fallback 0 hit；ensureRoot 0 hit；mkdirs 0 hit；remove 0 hit；listChildren 0 hit | 手工 grep + FR-6 回归 507 tests 双证 |
| FR-3 | **@Deprecated makeNodeTypedId 3 行 0 引用删除**：删后 grep `makeNodeTypedId` DsRegistry.java = 0 hit | grep + mvn compile exit0 |
| FR-4 | **删除后 DsRegistry.java 行数净减 ≥60**：删前 grep -c `^` = N；删后 `git diff HEAD DsRegistry.java | grep "^-[^-]" | wc -l` ≥ 60 且 `grep "^+[^+]" | wc -l` ≤ 8（仅 IAE 两行消息允许少量正行加） | `git diff --stat DsRegistry.java` **insertions < deletions （净减）** |
| FR-5 | **public migrateLegacyIds() 语义不变 + 幂等返回 0**：新实例 mkdirs 多层后（G2 注入 fake）→ migrateLegacyIds()=N；第二次=0；数据 0 丢失（exists 全 true） | T02（新） 复合迁移：10层×N items → migrate 2 次 → 所有 get/put/remove 与删前结果 equals |
| FR-6 | **删后全量回归 ≥ 49 PASS 0F0E 烟测**：DsRegistry 14 + P21-P52×26 + P7A1×6 + P6a×1 + P6b×1 = ≥49 | mvn -pl p2p-db 烟测 exit 0 BUILD SUCCESS |
| FR-7 | **NG-2 零新逻辑强锚**：删后 DsRegistry.java 仅两处允许新增 IAE 抛错（readNodeMeta/writeNodeMeta 各 1 次 `throw new IAE(...)`）；**任何其他正行添加一律 FR-7 Fail Revert**；禁止新增任何 if/else/try/catch/for/while/new 方法/new 字段 | git diff DsRegistry.java 正行（+） grep -c `if\|else\|try\|catch\|for\|while\|new` = 0 |
| FR-8 | **7 大底层 + P4 三件套 0 破坏**：DsFixedBucketStore/DsFreeRing/DsString/DsTypeFixedBucketStore/DsTypeString/DsData/DsHashMap = diff 0 行；makeDirTypedId/makeLeafTypedId/leafIdToValueOrId plain = 3 方法本体 0 字符差异；mkdirs 乐观命中（sl.tryOptimisticRead）性能路径 0 字符差异；findByPrefix listKeys 零侵入底层路径 0 字符差异 | Final diff 核对 + P7 专项（Tgt3/Tgt5/Phase1/Tgt2/Tgt4）×4 tests 全绿 = 7 零改实际证明 |

---

## 5. NFR Rubric（3 项 Score≥2/2 全满才算 PASS）

| NFR | Score=2 证据（双锚） |
|---|---|
| **NFR-1 删后安全性（零数据丢失）**：G1-G5 5 关全绿 + migrateLegacyIds 复合场景（T02 新）10 层 100 条 迁移→写入→删除→读取 全 equals 结果 + 烟测 ≥49 全绿 三证 | 5 Gates PASS + T02 复合 100% value 对 + Smoke ≥49 三锚点 |
| **NFR-2 Performance First 纯删零新增（行数净减 + 零逻辑增）**：行数净减 ≥60；git diff 正行 ≤8（仅两处 IAE 字符串）；`if\|else\|for` 新增 = 0；mkdirs 乐观命中路径 0 字符差异（StampedLock tryOptimisticRead 路径不增加分支） | `git diff --stat` deletions > insertions 且 grep `if\|for\|catch` diff+ 段 = 0 命中 + mkdirs 路径 grep 逐行 0 字符差异 |
| **NFR-3 零侵入 7 大底层 + P4 三件套 0 破坏**：NG-1 + NG-4 两张 Final diff 核对；P7 系列 7 专项（Tgt1-5 + Phase1 + Tgt2）共 4+7+6+6+4+1+6 = 34 tests 全绿（间接证明三件套零破坏） | 12 零改文件 diff=0 + 34 tests 全绿双锚 |

---

## 6. 验收测试清单（删前关 5 + 删后复合 1 = 6 专项锚点 + 烟测 ≥49）

| Test ID | 删前/后 | 场景 | ≥断言数 |
|---|---|---|---|
| testG1 | **删前（硬关）** | ROOT ring 迁移完 + migrate=0 | 4 |
| testG2 | **删前（硬关）** | keyIdToDirId.size()=0 + 10层深路径 exists=true | 5 |
| testG3 | **删前（硬关）** | legacy 大 id 4× NodeMeta = null + get("/a/x")=11L + exists("/a/y") | 6 |
| testG1G2G3 | **删前（硬关）** | 8层×100项 + 注入 fake + migrate==9 + remove50 + 剩余50 get value 全对 | 8 |
| testG5 | **删前（硬关）** | ROOT_DIR_NODE_ID=1L + migrate + isRootNodeId 两方法存在 | 2 |
| **T02（删后新增复合验证）** | **删后** | 新实例 10层×50条 put + migrate()两次幂等 + remove25 + get剩余25 value 全对 + listChildren size + findByPrefix prefix + mkdirs 乐观命中直接 return（无 writeLock） | ≥8 assertions |

（烟测 ≥49 PASS：DsRegistryTest×14 + P21-P33×13 + P41-P52×12 + P7A1×6 + P6a×1 + P6b×1 + P7Tgt3/Phase1/Tgt2/Tgt4×4 = ≥51 PASS）

---

## 7. Joint Verification Cmd（任何删操作后必跑，缺任一即 Fail）

```powershell
# ①删前硬关：G1-G5 必须全绿（任何 1 Gate Fail → 立即 STOP，不执行删）
mvn -pl p2p-db test "-Dtest=DsRegistry_P6a_GatesValidationTest" -q

# ②删后 mvn compile + test-compile（保证代码可编译零语法错）
mvn -pl p2p-db -am compile test-compile -DskipTests -q

# ③删后复合验证：T02（新）+ G1-G5（删后仍应全绿）
mvn -pl p2p-db test "-Dtest=DsRegistry_P6a_GatesValidationTest,DsRegistry_P6a_PostDelete_Compound_T02_Test" -q

# ④烟测 ≥49 PASS 0F0E
mvn -pl p2p-db test "-Dtest=DsRegistryTest,DsRegistry_P21_testMkdirs_OptimisticPathEqualsToWalkSet,DsRegistry_P22_testFindByPrefixKeys_EqualsToFindByPrefixEntryKeySet,DsRegistry_P31_MasterIndexPrefixTest,DsRegistry_P33_BidirectionalOrmTest,DsRegistry_P41_testMigrateLegacyIds_RootDirOnly,DsRegistry_P51_testGlobalNodeId_RoundTrip,DsRegistry_P52_testFromGlobalNodeId_InvalidSidPid_Throws,DsRegistry_P7_A1_GlobalIds_6_Test,DsRegistry_P6b_DsIdLayoutStressTest" -q

# ⑤全量 promote ≥560 PASS 0F0E
mvn -pl p2p-db test -q
```
