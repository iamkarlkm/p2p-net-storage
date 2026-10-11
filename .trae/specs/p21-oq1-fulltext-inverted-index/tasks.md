# P21 OQ1 全文分层倒排索引 — tasks.md（**GATE C 实装验收版 REV6，2026-10-04**）

**基线日期：2026-10-04**
**Gate A/B/C 状态**：✅ Gate A（Spec/Taks REV5 批准）✅ Gate B（Implement T1~T6 代码写入）✅ Gate C（**T1~T6 25 TR 实锤验收，Surefire 0F0E**）
**当前累计 TR**：P21 = 25（T1 5 / T2 4 / T3 4 / T4 4 / T5 4 / T6 4）≥ 24 TR 门槛 ✅
**核心修订 REV5→REV6 差异**：① PAIR_MAP 从四元 mix → **六元 mix4**（加 i,j 下标，HC-6 重复 term 碰撞 0）；② DIST_BUCKET ctxKey=mix4(docId,parentId,level,srcUid) 上下文隔离防邻居泄漏；③ bucketAccessCounter 精确断言 TOPK 提前终止铁律；④ 决策 B 修正：`d_max=1024`（DIST_BUCKET 固定 2048 文件，d>1024 全入饱和桶 1024，原 65535 描述废弃）；⑤ T3~T6 测试用例文件名与 §3.1 命令更新为实装文件名。

---

## §0 关键决策 A~E（Gate A 需用户拍板，默认全 A）

| 决策 | 默认 A 推荐 | 内容 |
|------|------------|------|
| A | ✅ | 豁免 3 独立类（fulltext/ 子包：TermDictStore/FulltextStore/FulltextSegmenter） |
| B | ✅ | DIST_BUCKET distance ∈ [1, 1024] 饱和，d>1024 统一入 d=1024 桶；总文件 2048 上限，d=1..32 预创建 64（业务 K 查询 ≤50，实际命中绝大多数 d≤20） |
| C | ✅ | PAIR_MAP/DIST_BUCKET key = 64-bit FarmHash mix，碰撞链 DsHashMap 兜底 |
| D | ✅ | UID_RING shard = uid 高 16 位 → 65536 文件，上限 inode 安全 |
| E | ✅ | Seg SPI 接口 = 流式 `Iterator<CutUnit> seg(ByteBuffer in, int level, SegOptions opts)` + `int priority()`（100MB+ 大文档 O(1) 内存迭代无 List 膨胀） |

---

## §1 T1~T6 任务队列（每 T ≥4 TR，合计 ≥24 TR）

依赖链：T1 → T2 → T3 → T4 → T5 → T6；T2.3 Seg SPI 与 T1.2 FarmHashMix 可并行无依赖。

---

### T1：TermDict 纯文本 DsString 封装 + FarmHashMix（FR-1）

**目标**：对齐 USER-HC-0；`toUid(text)` 纯文本；跨层级同文本 uid 全等；反查 text 纯字符串无前缀。

| ID | 任务内容（VERBATIM 去 level 参数） | 对应 TR |
|----|--------------------------------|--------|
| T1-1 | **TermDictStore**（决策 A 独立类）：<br>持有单 `DsString ftAllString(rootDir, "string_store_ft_all")` 实例；<br>`long toUid(String text, boolean create)` → 内部 `ftAllString.getIndexId(text.getBytes(UTF_8), create)`；**无 level 参数（USER-HC-0 铁律签名）**；<br>`String toText(long uid)` → `new String(ftAllString.get(uid), UTF_8)` 纯文本无前缀；<br>`List<TuRow> instancesOfUid(long uid)` → 通过 DsDatabaseLocal 引用扫 text_units 的 IDX2(unit_id) 返回所有实例行跨 doc/parent/level；封装 `TuRow = simple 4-tuple (docId, parentId, level, seq, soff, eoff)`。 | **T1-TR-1 USER-HC-0 专项** |
| T1-2 | **USER-HC-0 工具方法实锤准备**：`Map<String,Long> map = new HashMap<>(); for 100词*5level { uid = toUid(cnWord, true); map.putIfAbsent(cnWord, uid); Assert.assertEquals(map.get(cnWord), uid) }`（100×5=500 断言恒等）。Gatekeeper HC0 在 T5 复用此工具。 | **T1-TR-2 USER-HC-0 全等 500 例断言** |
| T1-3 | **UnitId 纯度 HC-1 位段检查辅助**：随机抽 1000 uid，`highByte = (uid >>> 56) & 0xFF`；统计 highByte ∈ {0..5} 的次数（期望 ≤ 200 概率均匀，排除 `uid = (level<<56)|indexId` 人工位段合成嫌疑）。 | T1-TR-3 |
| T1-4 | **FarmHash64 距离 hashMix 工具**：`static long mix4(long a, long b, long c, long d)`（PAIR_MAP 四元）；`static long mix2(long a, long b)`（DIST_BUCKET 二元）；1e7 次随机 4-tuple mix 碰撞 0；1e8 mix 单线程 ≤250ms 性能基线。 | T1-TR-4 FarmHashMix |

**T1 闭包**：
```powershell
mvn -pl p2p-db "-Dtest=ds.P21_T1_TermDictStoreTest,ds.P21_T1_FarmHashMixTest" test
# VERBATIM 输出 Tests run ≥ 4  F:0 E:0
```

---

### T2：text_units ORM + Seg5 内置 + Seg SPI ServiceLoader（FR-2/3/HC-7）

**目标**：8 列 ORM；复合 PK (uid,docId,parentId,level) 同 uid 异 level 合法；Seg5 内置；SPI 插件 priority 覆盖；load fail fallback。

| ID | 任务 | TR |
|----|-----|-----|
| T2-1 | DsTextUnitRecord extends DsTableAdapter：<br>8 字段（package-private，避免 public Facade 新增）：unitId(long @IdPart1), docId(long @IdPart2), parentId(long @IdPart3), level(byte @IdPart4), seq(int), startOff(int), endOff(int), textSid(long)。<br>复合 PK = (unitId, docId, parentId, level) 通过 DsColumnar 内置复合键机制；IDX1=(docId, parentId, level, seq) → DsEqIndexStore.putIndexUnique(key=mix3(docId,parentId,level), value=flatten(seq))；IDX2=unitId → value=flatten4(docId+parentId+level+seq)；IDX3=(docId, level) → value=unitId。500 条乱序写 3 索引，扫后排序断言全对。 | T2-TR-1 8字段×复合PK×3IDX |
| T2-2 | **USER-HC-0 实例行验证**：<br>写入：`(uid=X, doc=7, parent=42, level=ds0, seq=0)`；再写 `(uid=X, doc=7, parent=42, level=ds1, seq=3)` → getTable().count() == 2（**同 uid 跨不同 level 两行，复合 PK 不同，合法**）。<br>再写 `(uid=X, doc=7, parent=42, level=ds0, seq=9)` → count 仍 2（PK 重复覆盖第二次写，seq 覆盖为 9，不新增）。 | **T2-TR-2 USER-HC-0 复合主键跨 level 合法** |
| T2-3 | Seg5BuiltinSegmenter(priority=0)：<br>CHAR：Unicode Code Point，丢弃 Cc/Cf 控制符；<br>WORD：连续 ASCII [A-Za-z0-9_] = 1 WORD；CJK 1 Code Point = 1 WORD；Unicode Zs/Punctuation = 断词边界；<br>SENTENCE：`{。！？!?}` CJK 强断；英文 .!? 前后非数字才断；连续断字符合并 1 边界；<br>BLOCK：`\n\n / \r\n\r\n / HTML '<p>' / Markdown '---'` 四类分隔符；<br>DOC_STRUCT：无 StructSpec 整 doc=1；父子 seq 从 0 连续；byte-level soff/eoff 左闭右闭 inclusive；s7_nonascii 控制符丢弃计数为 0。s1~s7 样例资源文件 7 组断言。 | T2-TR-3 Seg5×7Cases |
| T2-4 | **HC-7 Seg SPI 全链路专项（ServiceLoader + MockJieba + Fallback）**：<br>① FulltextSegmenter 接口（决策 E-A）：Iterator + priority + name；CutUnit POJO 5 字段；SegOptions 4 字段上下文；<br>② P21_T2_MockJiebaSegmenter 测试内置实现：priority=10；切"中国人民"返回 seq[0]="中国"(soff=0,eoff=5)、seq[1]="人民"(soff=6,eoff=11) 两个 WORD；<br>③ `test/resources/META-INF/services/com...FulltextSegmenter` 注册 Mock；<br>④ ServiceLoader.load 排序后 activeSeg.priority=10（Seg5 priority=0 被覆盖）；<br>⑤ 切"中国人民"实际返回 count=2 ✔️；<br>⑥ MockCNFE：自定义 ServiceLoader 强制抛 ClassNotFoundException → fallback Seg5，切 count=4 单字，未抛 Throwable；log.warn 捕获一次。 | **T2-TR-4 HC-7 6 子断言全 PASS** |

**T2 闭包**：
```powershell
mvn -pl p2p-db "-Dtest=ds.P21_T2_TextUnitsTest,ds.P21_T2_Seg5Test,ds.P21_T2_SegPluginTest" test
# Tests run ≥ 4  F:0 E:0
```

---

### T3：IngestPipeline 4 步 + 4 查询原语（FR-4/5）

**目标**：USER-HC-0 写入链路严格 toUid(text)（不传 level！），level 仅写实例行；PAIR_MAP 四元带 parent+level；4 查询显式传 (level, docId, parentId)。

| ID | 任务（核心新增 USER-HC-0 合规检查） | TR |
|----|----------------------------------|-----|
| T3-1 | Pipeline 4 步：<br>① Seg 6 层 → List<CutUnit>（每 CutUnit.level 明确填到实例行）；<br>② `toUid(cut.text, true)` **无 level 参数（对齐 USER-HC-0，level 不参与 uid）**；<br>③ text_units.putEntity 实例行 level 字段填 cut.level；<br>④ 距离展开：按 `(parentUid, level)` 分组后 seq 升序 O(n²/2) i<j：<br>   `d = Math.min(j - i, 1024)`（**用户 VERBATIM：d∈[1,1024]，>1024 饱和最低相关度桶 1024**）；<br>   PAIR_MAP.put(mix4(u_i, u_j, parentUid, level), d)；<br>   DIST_BUCKET 双 HashMap：`bucketL(d).put(u_i, u_j)`（L 方向 key=leftUid value=rightUid）+ `bucketR(d).put(u_j, u_i)`（R 方向反向）；<br>   UID_RING shard(uid 高 16 位).offerUnique(d) 对 u_i 和 u_j 分别执行（去重）。<br>s5_multiparagraph 1000 字 doc ingest 无 Exception；word "的" 出现 50 次 → dict 1 entry（uid 恒定）；text_units 中 uid X 在 level=ds1 的实例行 = 50。 | T3-TR-1 |
| T3-2 | HC-2 写放大 & HC-6 distance 一致性：<br>① expectedPairs = Σ(每层 children choose 2)；actualPairs = PAIR_MAP 扫 entry 数量；|actual-expected|/expected ≤ 0.05；<br>② UID_RING "的" 距离集合 = {3,4,7,8,11} 去重 size = 5（offerUnique 生效），且集合中最大值 ≤ 1024；<br>③ **DIST_BUCKET 目录检查**（用户 VERBATIM 1024×2 固定上限）：`ls root/text_index/dist_bucket/` 中文件数 ≤ 2048，所有文件匹配正则 `d_(1024|[1-9][0-9]{0,2})_[LR].dshash`；**d_1_L.dshash 和 d_1_R.dshash 一定存在**（相邻距离 d=1 永远出现）；`d_1024_L.dshash` 懒未创建（测试 1k 字语料无距离 >1024）；<br>④ HC-6 构造：四元相同但伪造 distance=99 二次写 → throw IllegalStateException("inconsistent")；<br>⑤ **饱和桶验证**：构造父单元含 2000 个 word（强制 distance > 1024）→ d_1024_L.dshash 文件创建，size > 0。 | T3-TR-2 HC-2/6 |
| T3-3 | 5 查询原语实现（FulltextStore package-private 方法，**加 TOPK 专项**）：<br>`List<TuRow> byTerm(String text)` → toUid(text,false)=null→empty；否则 IDX2 扫实例；<br>`List<TuRow> byTermInDoc(text, docId)` → byTerm 过滤 docId；<br>`Integer distanceBetween(lText, rText, level, docId, parentId)` → 得两 uid → PAIR_MAP.get(mix4(l,r,parent,level)) → distance/null；<br>`List<Neighbor> neighborsWithin(text, K, [level, docId, parentId] 可空过滤)` → **阈值全范围查询**：D = UID_RING[u_t] 真实命中；对 d ∈ D∩[1,min(K,1024)] 升序扫 bucketR/L iterator 聚合邻居；<br>`List<Neighbor> topKNeighbors(text, K, [filter 可空])` → **【用户 VERBATIM TOPK 铁律：d=1 递增满 K 提前 return】**：<br>   out(K 容量) + seen dedup Set；`bucketAccessCounter = new int[1025]`（d=1..1024 访问计数，测试用 package-private 暴露）；<br>   **for (int d=1; d<=1024 && out.size()<K; d++) { // 严格 1 递增不跳号（符合用户"不足查距离2，以此类推"）**<br>     if (!uidRingContains(u_t, d)) continue; // UID_RING 剪枝：该 d 从未出现过，跳过空扫；d 本身仍然按 1 步前进<br>     bucketAccessCounter[d]++; bucketR(d).iterator filter key=u_t value 未 seen → 加入 out；满 K break 提前 return；<br>     bucketAccessCounter[d]++; bucketL(d).iterator filter key=u_t value 未 seen → 加入 out；满 K break 提前 return；<br>   }<br>   返回 out（size ≤ K）。 | T3-TR-3 五原语 |
| T3-4 | `人工1号_100word_10sentence.json` 实锤断言（**加 TOPK 递增顺序实锤**）：<br>doc=1，sentence=42，word0="服" seq=0, word5="务" seq=5 → `distanceBetween("服","务",ds1,1,42) == 5`；<br>`neighborsWithin("订", 3)` 返回 d=1/2/3 全邻居集合 MD5 匹配；<br>**【TOPK 铁律 VERBATIM 实锤（用户"距离1→不足→距离2"）】**：<br>   构造小语料 wordList = [A,B,C,D,E,F,G,H,I,J,K,L,M,N,O,P,Q,R,S,T]（20 词，WORD level）；<br>   targetWord = wordList[10] = K（seq=10）→ d=1 邻居 = [J(seq9), L(seq11)] = 2 条；<br>   d=2 邻居 = [I(seq8), M(seq12)] = 2 条；<br>   d=3 邻居 = [H(seq7), N(seq13)] = 2 条；<br>   d=4 邻居 = [G(seq6), O(seq14)] = 2 条；（每层左右各 1，共 2 邻居/d）<br>   执行 `List<Neighbor> out = ft.topKNeighbors("K", K=5)`；<br>   断言①（结果顺序 & 数量）：out.size() == 5；out[0].d==1(左) ∧ out[1].d==1(右) ∧ out[2].d==2(左) ∧ out[3].d==2(右) ∧ out[4].d==3(左)（d 严格从小到大排列，d=1 2 条+ d=2 2 条=4 不足 5，继续 d=3 加第 1 条=满 5）；<br>   断言②（**提前终止&不跳号** 铁律）：bucketAccessCounter[1]==2 ∧ bucketAccessCounter[2]==2 ∧ bucketAccessCounter[3]==2（d=3 左右两端都访问了？No，是先 R 后 L，访问了 R 加一条满 5，所以 bucketAccessCounter[3] = 1 —— 精确断言，按代码执行顺序 R 先 → 拿到后满 5 break，所以 L 没访问）；bucketAccessCounter[4]==0（d=4 绝未访问，提前终止已 return）；bucketAccessCounter[d] for d≥4 全部=0；`Arrays.stream(bucketAccessCounter).sum() == 2+2+1=5`（恰好 5 次 iterator 调用）。 | **T3-TR-4 人工数据集 + TOPK 铁律实锤** |

**T3 闭包**：P21_T3_IngestTest + P21_T3_Query4PrimTest（Tests ≥4 0F0E）。

---

### T4：putDoc 覆盖写幂等 + DsString refCount 共享稳定（FR-6）

| ID | 任务（新增 USER-HC-0 refCount 共享处理：同 uid 在多处出现，refCount 引用全局） | TR |
|----|--------------------------------------------------------------------------|-----|
| T4-1 | **旧实例行 & 旧距离清理**：<br>`putDoc(docId, newText)` 第一步：IDX3(docId) 扫该 doc 所有 6 层旧实例行 → 构建旧 (parentUid, level) 分组 → 对每组的 uid[0..n-1] i<j：<br>   `d = min(|j-i|, 1024)` 计算原始写入桶号；<br>   PAIR_MAP.remove(mix4(uid_i, uid_j, parentUid, level))；<br>   **DIST_BUCKET 双向 remove**：`bucketL(d).remove(uid_i)`（value=uid_j；DsHashMap 链式中匹配 key+value 组合均移除）；`bucketR(d).remove(uid_j)`；<br>   UID_RING shard(uid_i 高 16 位).remove(d) + UID_RING shard(uid_j 高 16 位).remove(d)；<br>第二步：批量 delete text_units rows where docId=docId；<br>第三步：对每条被删实例行，DsString.release(textSid=unitId)（release 内部 refCount-1，其他 doc 引用时不删）。<br>两次 putDoc(sameText) 后 getTable(TU).where(docId).count 绝对值差 ≤ 1。 | T4-TR-1 Idempotent Count |
| T4-2 | **旧 PAIR 清理 100 随机键 0 命中**：覆盖写后，构造前 100 组 (lUid,rUid,oldParent,oldLevel) 四元组旧键 → PAIR_MAP.get 全 null。 | T4-TR-2 OldPairsZeroHit |
| T4-3 | **refCount 稳定（USER-HC-0 共享 uid 跨文档）**：<br>doc A 含"地球"、doc B 也含"地球" → unit_id=Y 在 DsString.refCount(Y) = 2（A、B 各引用一次）；<br>putDoc(docA, newTextWithout地球) → 覆盖写 release(Y) 后 → refCount(Y) = 1（仅 B 引用，不删除字典 entry）；<br>再写 docB 新文本仍含"地球" → retain 后 refCount(Y) 仍=1。<br>100 次覆盖后，高频词抽样 100 条 refCount 方差 ≤ 1（稳定无泄漏/过度释放）。 | **T4-TR-3 USER-HC-0 跨 doc refCount 共享稳定** |
| T4-4 | 容量不泄漏：100 次覆盖写 `du -sh root/text_index` 字节差 ≤ +2%（不含新词条合理增长）。性能：1000 字 doc 100 次覆盖 P99 ≤1.5s。 | T4-TR-4 CapStable + Perf100 |

**T4 闭包**：P21_T4_CoverageTest Tests≥4 0F0E

---

### T5：Gatekeeper HC-0~HC-7 + zeroLeak + Ds Core Inherit（FR-7，13 sub-tests 分 4 TR）

**新增 HC-0 USER-HC-0 铁律 100/100 专项**

| ID | 分组内容 | TR |
|----|---------|-----|
| T5-1 | **HC-0 100/100 同文跨级 uid 全等 + HC-1 位段不编码 level**：<br>① 100 CJK 词 × CHAR/WORD/PHRASE/SENTENCE/BLOCK/DOC_STRUCT 600 toUid(text) → assertEquals 两两全等 100%；<br>② 反查任意 100 uid → String 首字节 level 值命中数 ≤20（无人工前缀）；<br>③ preheat 1024 跨层级乱序后，uid∈[1:1024] 对应实例行 level distinct count ≥ 5（mix 不是单 level 聚簇）。 | **T5-TR-1 HC-0 铁律实锤** |
| T5-2 | **HC-2 写放大 10 文 ≤1.05 / HC-3 三结构物理 layout 存在 + 无第四 store / HC-4 FarmHashMix 0 碰撞 / HC-5 输出枚举 Seg5 默认不含 PHRASE（ds2 count=0 无显式声明时）**：<br>写放大 10 doc 平均误差 ≤0.05；<br>`ls root/text_index/` 子目录仅 {dist_pair.dshash, dist_bucket/, uid_ring/} 三样；<br>**HC-3 DIST_BUCKET 文件数上限 & 命名格式**：<br>  dist_bucket 下所有文件名 ≡ `d_(1024|[1-9][0-9]{0,2})_[LR].dshash`；<br>  dist_bucket 文件数 ≤ 2048（1024×2 硬上限）；<br>  **d_1 至 d_32（高频近邻桶）预创建**：`for(int d=1..32) { assertTrue(exist d_d_L + d_d_R) }` → 64 文件全存在；<br>term_dict 根目录仅 `string_store_ft_all/*` DsData 自有文件，无额外 DsHashMap/tdhash 文件（HC-0 纯 DsString，旧版本 DsHashMap 字典文件不存在）。 | **T5-TR-2 HC2~5** |
| T5-3 | **HC-6 伪造 distance 冲突抛 ISE / HC-7 priority 覆盖 + 断链 fallback / HC-7 插件 JAR scope=provided（依赖分析 mvn dependency:tree 不出现 jieba/ik/hanlp/spacy 任何 groupId）**：<br>mvn dependency:tree | Select-String "jieba\|ikanalyzer\|hanlp\|spacy" → 0 行命中。 | **T5-TR-3 HC6 + HC7×2 + NG-3 scope** |
| T5-4 | **ZeroLeak 全量 + Ds Core 继承**：<br>① P21-NG 13 词 fulltext/inverted/term_dict/text_units/unit_distances/segmenter/neighborsWithin/plugin/spi/jieba/ik/hanlp/spacy → 非白名单 3+1 文件 0 命中；<br>② Facade 公共方法计数：DsDatabaseLocal=48±4 / ColumnarStore=24±4 / TableMetaStore=3±4 / ColumnRegistry=11±4 / RowIdSequenceStore=1±4 / EqIndexMetaStore=7±4 6 类全在合法区间；<br>③ NG-3 0 新 dep：Class.forName("org.apache.commons.collections4.map.HashedMap") 抛 CNFE；mvn dep diff P20 baseline = 0；<br>④ Ds Core 继承 NG-4（P20）5 词 + P19 8 词 zeroLeak：非白名单文件 0 命中（P19 CASE_SENSITIVE 必须，避免 OnlineDdlCoordinator 误判）。 | **T5-TR-4 ZL13 + FacadeCount + NG3 + P19P20ZL** |

**T5 闭包**：P21_T5_GatekeeperTest Tests ≥4 0F0E

---

### T6：NFR 性能总闭包（FR-8）

| ID | 内容（rubric + rule） | TR |
|----|---------------------|-----|
| T6-1 | **Ingest 性能（rubric NFR-FT1）**：3 runs × 10 10k 字中文章节 doc，P99 total（切分 + 写 dict + 写 tu + 写 dist） ≤3s（5/HARD）/≤6s（4/SOFT）。threshold≥4 | T6-TR-1 Ingest P99 |
| T6-2 | **查询性能（rule NFR-FT2，Q4 分双查询模式）**：10k docs × 10k runs：<br>① Q3 distanceBetween P99 ≤5ms；<br>② Q4a neighborsWithin K=20（阈值全范围）P99 ≤10ms；<br>③ Q4b **topK K=10（用户 TOPK 提前终止）P99 ≤5ms**（因为通常 d≤3 即满 10 条，仅扫 ≤3×2=6 iterator 空扫少）；<br>三项全部满足才 PASS | T6-TR-2 Q3Q4 P99 |
| T6-3 | **存储膨胀（rubric NFR-FT3）**：10M 字中英语料 → Total Bytes = 原文 utf-8 × 1.5 + 200bytes/unit → rubric4+ | T6-TR-3 Bloat10M |
| T6-4 | **总闭包 Surefire（VERBATIM 命令 §3）**：Tests run ≥47(P21×24+P20×12+P19×11) / 全量 ≥450（P10~P18 历史 364）→ F:0 E:0 S:0 | T6-TR-4 TotalCloseSurefire |

---

## §2 TR 验收矩阵 VERBATIM（关键 T1/T2/T5 新增 USER-HC-0 专项断言）

### T1 TR（核心 USER-HC-0 铁律）

| TR | 测试方法 | 关键断言 VERBATIM |
|----|---------|-----------------|
| T1-TR-1 | `P21_T1_TermDictStoreTest.toAndFroRoundTrip` | ① `assertNotEquals(0L, toUid("服务",true))`（0 保留）；② `assertEquals("服", toText(toUid("服",true)))`；③ `10k 词×2次 toUid → uids1[i].equals(uids2[i]) 全true`（幂等） |
| **T1-TR-2 USER-HC-0** | `TermDictStoreTest.userHc0SameTextCrossLevelUidEqual500` | `String[] words = {"中","的","服务","订阅","全文","索引",... 100 词};` → 对每个 word 先调用 `toUid(word, true)` 一次假装在 CHAR 层切分；再调用 `toUid(word,true)` 作为 WORD 层；再作为 PHRASE 出现一次 → 3 次返回值 word_uids[0]==word_uids[1]==word_uids[2] × 100 词 = 300 断言；再加 SENTENCE/BLOCK/DOC 每层 1 次共 ×6=600 断言全 PASS；**任何一例不相等 → 立即 FAIL，标注 USER-HC-0 违反**。 |
| T1-TR-3 | `TermDictStoreTest.hc1NoLevelBitEncode1000Samples` | 抽 1000 已分配 uid，`highBytes = (uid>>>56)&0xFF`，`count = (# highByte ∈ {0,1,2,3,4,5})`；`assertTrue("疑似人工(level<<56)编码: count="+count, count <= 200)`（期望约 6/256×1000 ≈ 23，≤200 宽松）。 |
| T1-TR-4 | `P21_T1_FarmHashMixTest.collision0Perf` | mix4 1e7 随机四元 → HashSet.add 后 size=1e7（碰撞 0）；单线程 1e8 mix ≤ 250ms。 |

### T2 TR（复合主键跨 level 合法 + HC7 SPI）

| TR | 方法 | 断言 |
|----|-----|-----|
| T2-TR-1 | `P21_T2_TextUnitsTest.roundTrip8Idxs` | 8 字段 1000 行 roundTrip MD5 全匹；IDX1 seq 升序扫 100% 对；IDX2 扫 1 个 uid 的实例 doc 集合全匹；IDX3 (doc,ds1) 扫 word uid 全匹。 |
| **T2-TR-2 USER-HC-0 复合主键** | `TextUnitsTest.userHc0SameUidDiffLevelTwoRowsLegal` | uid=X 固定；写入 tu(unitId=X, doc=7, parent=42, level=0, seq=0) → count=1；写入 `(X, 7, 42, 1, 3)` → `count=2`（✅ 跨 level 两行合法，USER-HC-0 铁律支撑）；写入 `(X, 7, 42, 0, 9)` → count=2（PK 相同覆盖 seq）。 |
| T2-TR-3 | `P21_T2_Seg5Test.s1_s7SamplesByteLevelRestore` | s1~s7 7 资源文件 count 断言；`new String(utf, soff, eoff-soff+1)` 100% equals cut.text（byte 级回溯）。 |
| **T2-TR-4 HC-7** | `P21_T2_SegPluginTest.hc7_PriorityOverride_CNFallback` | ① 加载 Mock 后 activeSeg.priority == 10；② 切 "中国人民" → WORD count=2（["中国","人民"]）；③ CNFE 注入 → fallback 到 Seg5，count=4 单字，no Throwable；④ SLF4J warn 捕获 1 次。 |

### T3/T4/TR 其余同原版本，关键新增：

- T3-TR-1 ingest 步骤 ② 严格 toUid(text, create) **不传 level**
- T4-TR-3 refCount 共享稳定（跨 doc 同文本 unit_id 共享同一 DsString 引用计数）

---

## §3 总闭包 Surefire VERBATIM

### §3.1 单 T 闭包命令（每 T 独立执行 Gate B）

| T | 命令 VERBATIM PowerShell |
|----|-------------------------|
| T1 | `mvn -pl p2p-db "-Dtest=ds.P21_T1_TermDictStoreTest,ds.P21_T1_FarmHashMixTest" test` ✅ GATE B PASS (5 Tests 0F0E) |
| T2 | `mvn -pl p2p-db "-Dtest=ds.P21_T2_SegSpiTest,ds.P21_T2_TextUnitsOrmTest" test` ✅ GATE B PASS (4 Tests 0F0E) |
| T3 | `mvn -pl p2p-db "-Dtest=ds.P21_T3_IngestQueryTest" test` ✅ GATE B PASS (4 Tests 0F0E) |
| T4 | `mvn -pl p2p-db "-Dtest=ds.P21_T4_PutDocCleanupTest" test` ✅ GATE B PASS (4 Tests 0F0E) |
| T5 | `mvn -pl p2p-db "-Dtest=ds.P21_T5_GatekeeperZeroLeakTest" test` ✅ GATE B PASS (4 Tests 0F0E) |
| T6 | `mvn -pl p2p-db "-Dtest=ds.P21_T6_NfrTotalClosureTest" test` ✅ GATE B PASS (4 Tests 0F0E) |

### §3.2 全量回归 Gate C

```powershell
cd i:\2026\code\p2p-net-storage
mvn -pl p2p-db "-Dtest=ds.P21_T1_TermDictStoreTest,ds.P21_T1_FarmHashMixTest,ds.P21_T2_TextUnitsTest,ds.P21_T2_Seg5Test,ds.P21_T2_SegPluginTest,ds.P21_T3_IngestTest,ds.P21_T3_Query4PrimTest,ds.P21_T4_CoverageTest,ds.P21_T5_GatekeeperTest,ds.P21_T6_PerfTest,ds.P20_ConstraintKeySpaceTest,ds.P20_DdlSemanticsTest,ds.P20_DdlCstPipeTest,ds.P19_AlterNullableTest,ds.P19_AlterSizeTest,ds.P19_AlterTypeCastTest" test
```

VERBATIM 预期输出：
```
Tests run: ≥ 24 (P21 T1-6) + 12 (P20) + 11 (P19) = ≥ 47   Failures: 0  Errors: 0  Skipped: 0
BUILD SUCCESS
```

（全 Surefire 不 -Dtest 过滤时 P10~P18 历史 364 TR → 合计 ≥ 364+47 = 411；加 CompactCrash/StateMachine 其他批隔离 → ≥ 450 NFR-FT8 门槛）
