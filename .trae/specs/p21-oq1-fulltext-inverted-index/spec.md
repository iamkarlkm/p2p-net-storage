# P21 OQ1 全文分层倒排索引（6 层语义 + 层级化两两正距离）—— spec.md（**REV8 GATE A 待批版，2026-11-10**）

**绝对日期：2026-11-10**
**REV5→REV6 关键差异**：① PAIR_MAP 四元→六元（加 i,j 下标 HC-6）；② DIST_BUCKET ctxKey 上下文隔离；③ §7 KT-1~KT-5 实装 Tradeoffs 写死（distanceBetween 查 DIST_BUCKET L 桶，不查 PAIR_MAP）。
**REV6→REV7 关键差异（用户 2026-11-10 澄清偏差修正）**：① **CHAR 层（DS0_CHAR）不再建 DIST 倒排**（成对 PAIR_MAP/DIST_BUCKET/UID_RING 全部跳过），仅保留 text_units 实例行作为「兜底词/词组/句子解析源」；DIST 最低层下沉为 WORD(level 1)，消除 char 层 O(C²) 存储浪费（见 §2.9 HC-8 与 §7 KT-6）；② HanLP 中文分词**改为硬依赖 hanlp jar**（`com.hankcs:hanlp:portable-1.8.6`，compile scope）以 Provider 接入（见 §7 KT-7）。**REV7 修订（用户 2026-11-10 推翻"反射式零硬依赖"方案）**：NG-3 红线原禁止 p2p-db 实依赖 hanlp，现**将 hanlp 移出红线**（剩余 jieba/ik/spacy/lucene/HNSW/向量库仍禁）；HanLpProvider 直连 `HanLP.segment(...)`，`available()` 恒 true、`priority()=100`；P21~P29_T5 Gatekeeper 已同步移除对 hanlp 的 `Class.forName` CNF 断言（计数 5→4 等）。
**REV7→REV8 关键差异（用户 2026-11-10 检索语义质询，Gate A 待批）**：① **实体包含树 + 向上聚合**：检索模型由"同文扁平 6 路"升级为**父包含子实体树**——`sentence(3) ⊂ block(4) ⊂ 章节/篇 ⊂ 卷 ⊂ 全本`，章节及以上统一以 **DS5_DOC_STRUCT 的 parent_id 链**嵌套（HC-5 永不增 level 6）；入参**含句子**时检索优先走 **sentence 倒排 → 定位 block/segment → 沿 parent_id 向上聚合**（章节/篇→卷→全本，每级只存本级索引，**不越层**）。见 §2.10 HC-9。② **block/segment 限长**（`maxBlockChars`，防 O(B²) 并作 scope 过滤粒度）见 §2.11 HC-10。③ **scope 倒排**（数据类别/权限过滤，复用既有物理原语、不引入第 4 种 store）见 §2.12 HC-11。④ **char 层挂「字符→词/词组」检索倒排**（澄清 HC-8 只禁 char **两两 DIST**，**不禁** char 层的词/词组后置表）见 §2.9 豁免边界增补 + §2.13 HC-12。⑤ **词/词组同义·近义关系**像 scope 一样作**一等倒排属性**（`unitId → synonymSet`）见 §2.13 HC-12。⑥ **召回语义=精确优先降权**：搜「人民」**召回**「人民群众」但**精确整词「人民」排序更前**（"人民"2/2 vs "人民群众"2/4，char 层最多匹配者择优）；**打分公式归 P22**（P21 Non-Goals：权重/TF-IDF 不在 P21），P21 只提供 char 层择优**召回原语**。

**核心设计对齐用户 VERBATIM 原方案（严格实施）**：

---

## 1. 问题陈述（同前略）

7 类功能断层 F-1~F-7 不重述。受益人新增「NLP 分词器插件开发者」。

---

## 1.3 交付目标（**用户 VERBATIM 要求严格执行，核心修正：term_dict 纯文本去重**）

### 【用户核心设计原则，永久红线写入 HC-0（最高优先级）】

> **USER-HC-0：unit_id 全局唯一标识 = 同一个原文（相同 UTF-8 字节序列）= 同一个 unit_id。level（CHAR/WORD/PHRASE/SENTENCE/BLOCK/DOC_STRUCT）**不是**字典表去重 key，只是 text_units 实例表中的实例属性。绝对禁止 level 任何形式编码进 unit_id（位段/前缀字节/分隔符都不行）。同一文本作为 CHAR、WORD、BLOCK 出现时，在 term_dict 中是唯一 entry，返回同一 unit_id；层级差异由 text_units 表中 (doc_id, parent_id, level) 不同行表达。**

---

1. **6 层 unit 抽象（同前）**：CHAR(0) → WORD(1) → PHRASE(2) → SENTENCE(3) → BLOCK(4) → DOC_STRUCT(5)；**每层只存「父包含子、子 seq、同父同层两两正距离」3 类最小信息；跨层 0 距离重复写。**【REV7 修订】CHAR(0) 层**不建 DIST 倒排**（见 HC-8）：char 层仅作为「兜底获取词/词组/句子」的解析源并保留 text_units 实例行，其两两成对距离由 WORD(1)/PHRASE(2) 层承担；DIST 倒排的实际最低层为 **WORD(level 1)**。【REV8 修订】6 层升级为**父包含子实体树**（`sentence ⊂ block(最重要检索实体) ⊂ 章节/篇/卷/全本`，级别≥4 用 DS5 `parent_id` 链）；**检索流程**：入参**含句子** → 优先 sentence 倒排 → block/segment → 沿 `parent_id` 向上聚合（**不越层存储**）；入参**非句子** → 统一分解 char → char 层倒排（**char 层可倒排词/词组**）→ **最多匹配者择优**（精确优先降权，解决「人民」vs「人民群众」）。block/segment **限长**并倒排 **scope**（数据类别/权限过滤）；词/词组带 **同义·近义** 一等属性。见 §2.9~§2.13（HC-8 增补 / HC-9~HC-12）。

2. **【USER-HC-0 严格对齐】unit_id = 纯文本 DsString.indexId 全局唯一，level 零关联**：
   - `term_dict = DsString(root, "string_store_ft_all")` 单实例；字典 key = **纯文本 UTF-8 字节**（无前缀、无分隔符、无 level 任何字节）
   - `unit_id = ftAllString.getIndexId(utf8Bytes, createIfAbsent=true)`
   - 【用户设计 VERBATIM】：同一个"订阅"（WORD level 出现的文本）和如果"订阅"又作为 PHRASE 整体出现 → 文本字节相同 → unit_id 同值 ✔️；"中"字 CHAR(0) 和 "中" 单 CJK 词 WORD(1) → 文本字节完全相同 → 同一个 unit_id ✔️
   - 反查：`DsString.get(unit_id)` → 纯文本 UTF-8 字节 decode String；**DsString 不存 level**，查 level 必须走 text_units（同一个 uid 在多层级有多个实例行，返回 List<LeveledInstance>）
   - unit_id 不携带 doc_id/parent_id/seq/level 任何位段或编码，仅由 DsString DsData 写入顺序决定（indexId 单调递增）

3. **三表/三 store 物理布局（USER-HC-0 修正 term_dict，其余严格对齐）**：
   - **term_dict（DsString，纯文本）**：key=纯 UTF-8 text；unit_id = DsString 返回 indexId。`unit_id == 0` 永久保留（不分配）。
   - **text_units 实例表（ORM 8 列，4 PK + 3 IDX）**：
     - 8 列：unit_id BIGINT、doc_id BIGINT、parent_id BIGINT、level TINYINT、seq INT UNSIGNED、start_off INT、end_off INT、text_sid BIGINT (= unit_id 冗余，方便 refCount 追踪)
     - **复合 PRIMARY KEY = (unit_id, doc_id, parent_id, level)**：同一 unit_id 在不同 doc / 不同 parent / 不同 level 都是不同实例行 ✔️；同一 uid 跨 level（CHAR 与 WORD 两条行）合法且必须 ✔️
     - IDX1 = (doc_id, parent_id, level, seq) → 同父同层按 seq 排序扫兄弟
     - IDX2 = unit_id → 扫某文本所有出现的实例（跨 doc / parent / level 全能查）
     - IDX3 = (doc_id, level) → 扫某文档某 level 所有实例
   - **unit_distances（**严格对齐用户 VERBATIM 新指令**：PAIR_MAP + DIST_BUCKET d[1-1024] × 2 HashMap/L+R + UID_RING 剪枝）**：
     - 【用户 VERBATIM 语义】：unit_distances 一行 = **「在 doc_id 的 parent_id 下 level 层，left_id 到 right_id 的距离 = |seq_left - seq_right|」**；**distance ∈ [1, 1024]，相关度随 d 增大递减，d>1024 饱和存入 d=1024 桶（最低相关度）**。
     - ① PAIR_MAP（用户指令第一句：left_id, right_id -> DsHashMap）：`root/text_index/dist_pair.dshash` 单 DsHashMap。**key = FarmHash.mix4( mix4(leftUid, rightUid, parentUid, level), i, j, 0xC0FFEE )（六元 mix4，其中 i/j 是 left/right 在同父同层 seq 数组中的下标）**（六元上下文隔离，同 term 重复出现跨句/跨段碰撞 0；同 (l,r,p,lv,i,j) 六元重复写不同 d → IllegalStateException "dist inconsistent" 执行 HC-6 红线）→ value = distance（存 d ∈ [1, 1024]，d>1024 = 1024）。
     - ② DIST_BUCKET（用户指令第二句：每一个distance[1-1024]对应2个DsHashMap：left_id，right_id）：
       - 目录：`root/text_index/dist_bucket/`；**固定 1024 个距离值 × 2 方向 = 2048 个 DsHashMap 文件**（文件数上限恒定 2048，绝不超，inode 安全）。
       - 对每个 d ∈ [1, 1024]：
         - `d_<d>_L.dshash`：**key = leftUid** → value = **rightUid**（64-bit 直接存另一 uid；同 leftUid 出现多个 rightUid 同距离 d 时，DsHashMap 内部链式冲突兜底，多次 put 形成链表；查询用 iterator 过滤 key）。
         - `d_<d>_R.dshash`：**key = rightUid** → value = **leftUid**（反向索引；方向对称）。
       - d > 1024 的 pair 统一写入 **d_1024_L.dshash / d_1024_R.dshash**（1024=最低相关度饱和桶，永不溢出）。
       - 文件创建策略：`d=1..d=32` 启动时预创建（高频近邻 K≤20 必命中）；`d=33..d=1024` 懒创建（首次写入该 d 时创建）。
     - ③ UID_RING（对齐用户原设计：每一个unit_id对应distance集合->DsFreeRing）：
       - 物理：分桶 shard = uid >>> 48 → 65536 文件上限；`root/text_index/uid_ring/shard_<high16>.dsfree`
       - 语义：存入该 unit_id 所有出现过的 distinct distance 值（DsFreeRing.offerUnique(d) 去重）。
       - 作用：Q4 neighborsWithin K 剪枝。先扫 UID_RING 得真实 D = {d1,d2,d3,...}，只查 D∩[1,K] 中存在的 d × 2 DsHashMap，**避免盲扫 2048 桶**。

4. **写入流程（对齐 USER-HC-0）**：新 doc → 6 层切分。每切 1 个 CutUnit：
   ① `unit_id = ftAllString.getIndexId(textUtf8, true)`（同文本永远返回同 uid）
   ② `INSERT INTO text_units(uid, docId, parentId, level, seq, soff, eoff, text_sid=uid)`（复合主键保证同 doc 同 parent 同 level 同 uid 不重复）
   ③ 同父同层 seq 排序后，`i<j d=j-i` 写三 distances store（PAIR_MAP / DIST_BUCKET[d] / UID_RING[u_i].offerUnique(d) + UID_RING[u_j].offerUnique(d)）

5. **Seg SPI 可插拔 NLP 分词（对齐用户要求）**：FulltextSegmenter SPI ServiceLoader 接口（决策 E-A Iterator 流式）；内置 Seg5 priority=0 白名单；**HanLP 为 compile 硬依赖**（`HanLpProvider` priority=100，见 §7 KT-7），IK/Jieba/SpaCy 仍以插件方式 classpath 放入 META-INF/services 即插即用，scope=provided；load 失败 fallback Seg5 不抛异常。MockJiebaPlugin priority=10 测试覆盖优先级。

6. **5 条核心查询原语（严格对齐 unit_id 纯文本唯一）**：
   ① `byTerm(text) → List<Instance>`：→ uid = DsString.getIndexId(text, false) → null 空集；非空扫 text_units IDX2 → 所有 (doc_id, parent_id, level, seq) 实例
   ② `byTermInDoc(text, doc_id) → List<Instance>` → uid + IDX2 + filter docId
   ③ `distanceBetween(lText, rText, level, doc_id, parent_id) → Integer or -1`：左右文本得 u_l / u_r → **扫 d=1..1024 下 `d_<d>_L.dshash` 单向桶（仅查 rightUid 作为 lUid 右侧邻居命中的距离，return 第一个 d）**；查不到 return -1；反向 distance(rText→lText) 请显式 swap 参数再调用（L/R 两桶单向）。【REV6 说明：PAIR_MAP 是六元 mix 含 i,j 写入时 seq 下标，查询端无 i,j，PAIR_MAP 仅用于 HC-6 探针 putPairForTest，不再作为 distanceBetween 主查询路径】
   ④ `neighborsWithin(text, K) → List<Neighbor>`（**阈值范围查询**，UID_RING→DIST_BUCKET L+R 直查，K≤1024）：
     - Step 1：`u_t = toUid(text,false)`；null 返回空
     - Step 2：扫 UID_RING[u_t].pollAll 得真实命中过距离集合 D（DsFreeRing 迭代去重）
     - Step 3：对每个 d ∈ (D ∩ [1, min(K, 1024)])，按 d 升序：
       - 扫 d_<d>_R.dshash iterator：filter key == u_t → value = leftNeighborUid 集合 → 加入邻居（distance=d 方向=L）
       - 扫 d_<d>_L.dshash iterator：filter key == u_t → value = rightNeighborUid 集合 → 加入邻居（distance=d 方向=R）
     - Step 4：同一邻居在 L/R 两端都命中则取较小 d，DsString.get(uid) 拼邻居文本
     - Step 5（可选 level/doc/parent 过滤）：若调用方提供，则取邻居 uid 通过 text_units IDX2 过滤 (level, doc, parent) 再返回
     - 注：④ 为 **全距离阈值查询**，返回所有 d≤K 的邻居集合，用于全范围召回
   ⑤ `topKNeighbors(text, K) → List<Neighbor>`（**【用户 VERBATIM：TOPK 查询 d 升序满 K 提前终止】**，相关度 d 越小越靠前）：
     - Step 1：`u_t = toUid(text,false)`；null 返回空；`List<Neighbor> out = new ArrayList<>(K)`；`Set<Long> seen = new HashSet<>()`
     - Step 2：D = UID_RING[u_t] 真实命中 distance 集合（offerUnique 已经去重）；或 D = ∅ 时用 d=1..1024 线性扫
     - Step 3：**对 d 从 1 递增到 1024（相关度降序）**：
       - 快速剪枝：`if D 非空 且 D.contains(d) == false → 该 d 从未命中过该 uid，跳过不扫`（省 90% 空扫）
       - `bucketR(d).iterator()` → filter key = u_t → value = leftNeighborUid；若 uid ∉ seen → seen.add + out.add(Neighbor(uid,toText(uid),d,LEFT))；若 `out.size() ≥ K → Step 5 提前 return（短接终止，不再扫 d+1）`
       - `bucketL(d).iterator()` → filter key = u_t → value = rightNeighborUid；若 uid ∉ seen → seen.add + out.add(Neighbor(uid,toText(uid),d,RIGHT))；若 `out.size() ≥ K → Step 5 提前 return`
     - Step 4：若 d=1024 扫完仍不满 K → 返回已收集（不足 K 条就实际量返回，不填充 null）
     - Step 5（可选过滤）：out 收集完 K 或 d 扫完后，对 level/doc/parent 参数做按需过滤，最终 size=min(K, 过滤后剩余)
     - **【用户 VERBATIM 语义铁律】**："如果距离1获取了10条就提前返回，不足再查询距离2，以此类推" → 严格 d=1→1024 顺序，满 K 立即 return；绝不先扫全量再截断
   注：④ vs ⑤ 对比：④ 阈值查询 = 召回 **所有** d≤K 邻居（可能几十上百条，排序取 top 业务侧用）；⑤ TOPK 查询 = 只取相关度最高 **前 K 条（默认 top10）**，扫到够就停（通常 d≤3 就能拿到 10 条邻居，平均 ≤15ms → ≤5ms 优化后）。

7. **NFR 硬线（同前）**：1 万字 doc P99 ≤3s（HARD）/6s（SOFT）；Q3 P99 ≤5ms / Q4 ≤10ms；写放大 1.0±0.05（HC-2）；膨胀率 ≤ 原文 1.5× + 200b/unit（rubric≥4）。

---

## 1.4 Non-Goals（同前修订）

✅ Seg SPI 纳入 Goals；**HanLP 为 compile 硬依赖**（`HanLpProvider` priority=100）；IK/Jieba/SpaCy 插件纳入 Goals（scope=provided）。
❌ 权重/TF-IDF/BM25/embedding 仍非 Goals（P22）。**【REV8】精确优先降权的"打分公式"（精确匹配 bonus / synonym 加权融合）归 P22**；P21 只交付 char 层**择优召回原语**（HC-12）。
❌ 分布式分片/增量 patch/自动 n-gram/跨库字典共享 仍非 Goals（P21b 后续）。

---

## 2. 硬约束（HC 红线，USER-HC-0 为最高优先级永久红线）

### §2.0 USER-HC-0：unit_id = 纯原文唯一 ID，level 不参与唯一性（VERBATIM 执行）

- **严格 VERBATIM**：
  1. term_dict 唯一 key = **纯文本 UTF-8 byte[]**；`DsString.getIndexId(key, true)` 返回 unit_id。
  2. **禁止**：前缀 byte、内部分隔符、level 入位段、`(level<<56)|indexId` 合成 uid。任何将 level 编码进 key 或 uid 的代码 → 评审直接 Reject。
  3. 同原文跨层级出现 → unit_id **严格相等**（`assertSameUid("中", CHAR) == assertSameUid("中", WORD)` 必须 TRUE）。
  4. unit_id 仅由 DsString DsData indexId 单调顺序分配，不得手工干预或构造。
- **Gatekeeper HC-0 专项**：100 例混合层级文本断言 uid 全相等；抽查 100 个 uid 反查 DsString 文本无任何前缀/分隔字符。

### §2.1 P21-HC-1：unit_id 纯洁性（USER-HC-0 推论，位段零上下文）

- **VERBATIM**：unit_id 64 位段中绝不编码 docId/parentId/seq/level。不要求位段随机分布（DsString 按写入顺序分配导致的聚类允许），但禁止位运算人工合成。
- **验证**：抽取任意 100 个 uid，尝试 `((uid>>>56)&0xFF)` 解码出的值 ∈ {0..5} 的命中数 ≤ 20（纯概率分布 level 位不应该恰好匹配 level 值的枚举）。

### §2.2 P21-HC-2：跨层 0 距离重复写（写放大 ≤ 1.05）

同父同层 pair→distance 只写一次，绝不冒泡上层 3 份。

### §2.3 P21-HC-3：三结构物理布局（0 第四种 store，term_dict=纯 DsString）

| 逻辑 | 物理（REVISED USER-HC-0 严格） | 路径 | 主键/键 |
|-----|-------------------------------|------|--------|
| term_dict | **纯 DsString 单实例（无前缀，无 level）** | `root/string_store_ft_all/` | text UTF-8 唯一 → unit_id 唯一 |
| text_units | DsColumnar ORM 8 列 4 复合 PK + 3 IDX | `root/orm/column/` | PK=(unit_id, doc_id, parent_id, level) |
| unit_distances | **DIST_BUCKET 1024×2 固定 2048 DsHashMap (L+R) + PAIR_MAP 单 mix4 + UID_RING 65536 shard DsFreeRing** | `root/text_index/{dist_pair.dshash, dist_bucket/d_<[1:1024]>_{L|R}.dshash, uid_ring/shard_<[0:65535]>.dsfree}` | byPair(mix4) / byDist 每 d 左/右两桶 / byUid(shard16) 剪枝 |

### §2.4 P21-HC-4：FarmHash64（仅距离 hashMix 用，dict 不用）

`static long mix4(long a, long b, long c, long d)`；`static long mix2(long a, long b)`。手写 0 依赖。碰撞率 ≤1e-12，DsHashMap chain 兜底。

### §2.5 P21-HC-5：6 层 level 枚举永久冻结

level ∈ {0=CHAR, 1=WORD, 2=PHRASE, 3=SENTENCE, 4=BLOCK, 5=DOC_STRUCT}。嵌套 chapter/section 用 DOC_STRUCT level=5 的 parent_id 链嵌套表达，永远不增 level=6。

### §2.6 P21-HC-6：PAIR_MAP distance 一致性（**六元 mix 同 → distance 必同，否则 ISE**）

对同一 **六元 (lUid, rUid, parentUid, level, i, j)**（i,j = 左/右 seq 下标），多次写入 distance 必须恒等于 `|lseq - rseq|`（= min(|i-j|, 1024)）；不一致 → `IllegalStateException("dist inconsistent")` 抛（数据损坏级，禁止覆盖）。同六元同 d = 幂等（skip put，不修改任何文件）。

### §2.7 P21-HC-7：Seg SPI ServiceLoader 插件化

接口决策 E-A Iterator 流式；priority 大者覆盖；内置 Seg5=0；插件 scope=provided；load 失败 fallback Seg5 不抛；MockJiebaPlugin priority=10 测试覆盖。

### §2.8 P21-NG zeroLeak 13 词（同前）+ 缩写表

fulltext/ft, inverted/inv, term_dict/td, text_units/tu, unit_distances/ud, segmenter/seg, neighborsWithin/ngb, plugin/plg, spi/SPI, jieba/jb, ik/IK, hanlp/hl, spacy/sp, DOC_STRUCT/ds5, BLOCK/ds4, SENTENCE/ds3, PHRASE/ds2, WORD/ds1, CHAR/ds0。

白名单：3 主类 FulltextStore/TermDictStore/FulltextSegmenter + 1 测试 + TermDict 内嵌类。

### §2.9 P21-HC-8：CHAR 层不建 DIST 倒排（REV7 新增，用户 2026-11-10 澄清）

- **语义**：`ingestDoc` Step4 的成对 DIST 枚举（PAIR_MAP 六元 + DIST_BUCKET[d] L/R + UID_RING.offerUnique）**仅对 level ≥ 1 执行**；level == DS0_CHAR 跳过整段 DIST，只保留 Step1/2/3 的切分与 text_units 实例行。
- **依据**：char 层逐 code point（N=字符数），同父两两对 = N(N-1)/2 → 长文本 O(C²)。CJK 场景下 char 逐字与 word 一字一词 uid 近乎同构，char 层 DIST 纯属冗余（约 32B/pair：PAIR_MAP key/value + distL 8B + distR 8B）。
- **豁免边界**：`updateWeightForLevel` / `writePositionsForLevel` 对 level 0 **仍执行**（char 层 tf/positions 保留，供 charLevelSearch 兜底查询）；`clearDoc` 对 char 层幂等安全（无 bucket → `removeNeighbor` 早返回）。
- **【REV8 增补】豁免边界扩展（用户 2026-11-10 澄清）**：HC-8 仅禁 char 层的**成对 DIST 距离对**（PAIR_MAP / DIST_BUCKET / UID_RING）；**不禁止** char 层挂「**字符 → 词/词组**」的**检索后置表**（用于 `charLevelSearch` 消歧与 §2.13 的"精确优先降权"）。即 char 层**可以倒排词和词组**（词/词组因不能严格分层，豁免跨层限制），但**仍不建**字符两两距离。二者互不冲突：查候选走词/词组后置表，距离走 level≥1。
- **Gatekeeper**：`P21_T3_IngestQueryTest.checkPairs` 断言 `pairsTotal == Σ_{lv≥1} n(n-1)/2`（排除 level 0）；HC-6 一致性红线在 level ≥ 1 内继续生效。

### §2.10 P21-HC-9：实体包含树 + 向上聚合（REV8 新增，用户 2026-11-10 澄清）

- **语义**：6 层**不是**"同一整篇文本各切一遍的扁平 6 路"，而是**父包含子**的实体树：
  - `CHAR(0) ⊂ WORD(1) ⊂ PHRASE(2) ⊂ SENTENCE(3) ⊂ BLOCK(4) ⊂ 章节/篇 ⊂ 卷 ⊂ 全本`。
  - **`block(4)` 是最重要的检索实体**：`sentence(3)` 的 `parent_id` = 其所属 **block**；`block(4)` 的 `parent_id` = 其所属**章节节点**；章节/篇/卷/全本统一用 **DS5_DOC_STRUCT 的 parent_id 链**嵌套表达（HC-5：**永不增 level=6**）。
- **检索流程（用户 VERBATIM）**：
  - 入参**含句子** → 优先走 **sentence 倒排** → 定位含该句的 **block/segment** → 沿 `parent_id` 链**向上聚合**到 章节/篇 → 卷 → 全本。
  - 入参**非句子** → 统一分解为 **char** → 走 char 层倒排（见 §2.9 豁免边界 + §2.13）。
- **不越层（用户核心原则）**：block 只倒排其 sentence 级索引；章节/篇**不重复存** sentence；卷/全本只倒排其下章节/篇。每级只存本级索引，**严禁越级冗余存储**以省空间。
- **Gatekeeper**：断言 `parent_id` 链可自底向上回溯（sentence.parentId 命中的 block 之 parentId 命中其章节 DS5 节点）≥ 3 级。

### §2.11 P21-HC-10：block/segment 长度上限（REV8 新增，用户 2026-11-10）

- **语义**：`block(4)` 单元**必须限长**（`maxBlockChars`，默认 **1024** code point）；超长段落按**句边界**二次切分为多个 block（不切碎句子）。
- **目的**：① 控制单 block 的 DIST pair 数（防 O(B²)）；② block 作为**向上聚合**与 **scope 过滤**的粒度单位。
- **Gatekeeper**：`cutBlock` 输出任一 block 长度 ≤ `maxBlockChars`；超长段落切出的多 block 的 `parent_id` 指向**同一章节节点**。

### §2.12 P21-HC-11：scope 倒排（REV8 新增，用户 2026-11-10）

- **语义**：block/segment 除文本倒排外，**倒排若干 scope**（数据类别/权限/标签等）。scope = `(scopeKey, scopeValue)` 键值对（如 `dept:HR`、`cls:SECRET`）。
- **物理**：复用既有物理原语（DsHashMap / DsColumnar），**不引入第 4 种 store 类型**（HC-3 语义下视作 unit_distances 的倒排变体）；双向：`scopeKey → {unitId}`（过滤用）与 `unitId → scopeSet`（展示/反向用）。
- **用途**：检索时按 scope 过滤（如 `scope=dept:HR` 只召回 HR 数据），实现**数据类别/权限**维度的裁剪。
- **ZeroLeak**：实现类必须放 fulltext 包**外**或作 `static` 内嵌类（fulltext 包顶级类 ≤ 3，见 §7 KT-5）。

### §2.13 P21-HC-12：词/词组同义·近义关系 + 精确优先降权（REV8 新增，用户 2026-11-10）

- **语义**：词/词组是重要语法元素，其**同义/近义词**属性**像 scope 一样**作**一等倒排属性**处理：`unitId → synonymSet`（同义 + 近义）。
- **召回语义（用户决策：精确优先降权）**：搜「人民」时，含「人民」的**更长单元**（如「人民群众」）**仍召回**，但**精确整词「人民」排序更前**；由 char 层"**最多匹配者择优**"实现（「人民」2/2 ≻「人民群众」2/4）。
- **实现位点**：char 层挂「字符→词/词组」检索后置表（§2.9 豁免边界）→ query 分解为 char → 命中候选单元 → 按**匹配度**择优（精确整词 > 长词组前缀）。
- **依赖边界**：本轮 spec 只规定 char 层**召回原语与择优规则**；最终**打分公式（精确匹配 bonus / synonym 加权融合）归 P22**（P21 Non-Goals：权重/TF-IDF/BM25 不在 P21）。

---

## 3. 功能需求 FR（严格对齐 USER-HC-0）

| FR | 内容（关键修订） | AC |
|----|-----------------|-----|
| FR-1 | **TermDict = 纯 DsString（T1）**：TermDictStore 包装 ftAllString；`long toUid(String text, boolean create)`（无 level 参数！用户原设计）；`String toText(long uid)`（DsString.get 反查纯文本，无前缀）；`List<TuRow> instancesOfUid(long uid)`（扫 text_units IDX2，返回该文本跨层级跨文档所有实例行）。toUid 幂等 + USER-HC-0 同文本跨 level 返回同 uid 专项实锤；term_dict 0 额外 DsHashMap。 | AC-FR1-1~6 |
| FR-2 | **text_units ORM（T2）**：8 字段 DsTextUnitRecord；复合 PK (uid,docId,parentId,level) 的 DsColumnar 实现；同 uid 跨 level 两条行合法；IDX1/2/3 DsEqIndexStore 实现；HC-5 putIndexUnique 冲突抛 IAE。 | AC-FR2-1~6 |
| FR-3 | **Seg5 内置 + Seg SPI（T2）**：Seg5 白名单 5 切；FulltextSegmenter 接口 Iterator<CutUnit>（决策 E-A）；ServiceLoader priority；MockJieba 覆盖；load 失败 fallback。 | AC-FR3-1~11 |
| FR-4 | **IngestPipeline 4 步（T3）**：Seg6层→TermDict.toUid(text)（不传 level！因为 USER-HC-0）→text_units insert 行（传 level 到实例）→同父同层展开 pair→distance。严格 USER-HC-0。 | AC-FR4-1~6 |
| FR-5 | **5 查询原语（T3）**：distanceBetween/neighbors 都显式传 (level, doc_id, parent_id) 因为同 uid 不同上下文 distance 不同；**NEW topKNeighbors(text,K) d=1 递增满 K 提前 return（用户 TOPK 铁律）**，seen dedup 去重左/右同一 uid，UID_RING 跳过未命中 d（剪枝空扫 90%）。 | AC-FR5-1~6 |
| FR-6 | **覆盖写幂等（T4）**：删除旧 text_units 行后旧 distances 清理；DsString release 旧 text_sid（因为 unit_id 就是 text_sid 全局引用，同一 uid 在多处出现 refCount 共享正确处理——覆盖写减少某文档引用时 release，新增时 retain，refCount 稳定）。 | AC-FR6-1~5 |
| FR-7 | **Gatekeeper HC-0~HC-7（T5）**：新增 HC-0 同文跨级 uid 相等 100 例专项；其余同前扩展 HC-7 SPI 专项。 | AC-FR7-1~13 |
| FR-8 | **NFR 性能 + 总闭包（T6）**：同前。 | ≥4 TR, 总闭包 Tests≥450 |
| FR-9 | **实体包含树 + 向上聚合（REV8，HC-9）**：ingestDoc 由"同文扁平 6 路"升级为**父包含子实体树**（sentence⊂block⊂章节/篇/卷/全本，DS5 `parent_id` 链，永不增 level 6）；入参**含句子**时检索优先 sentence 倒排 → block/segment → 沿 `parent_id` 向上聚合；**不越层存储**。 | AC-FR9-1~4 |
| FR-10 | **block 限长 + scope 倒排（REV8，HC-10/11）**：`cutBlock` 受 `maxBlockChars`（默认 1024）约束、超长按句边界切；block/segment 倒排 scope `(scopeKey,scopeValue)` 供数据类别/权限过滤（复用既有物理原语，0 第 4 store）。 | AC-FR10-1~4 |
| FR-11 | **char 层词/词组检索倒排（REV8，HC-8 豁免边界）**：char 层挂「字符→词/词组」后置表用于消歧/择优；**仍不建** char 两两 DIST（pairsTotal 排除 level 0）。 | AC-FR11-1~4 |
| FR-12 | **同义/近义关系 + 精确优先降权（REV8，HC-12）**：`unitId → synonymSet` 一等倒排；召回=**精确优先降权**（长词组仍召回但精确整词排序更前）；打分公式归 P22。 | AC-FR12-1~4 |

---

## 4. 验收标准 AC（核心新增 HC-0 专项断言）

### §4.1 功能 AC（关键新增/修订项）

- **AC-FR1-1【USER-HC-0 铁律实锤】**：同文本跨层级 uid 恒等：
  ```java
  long uidChar = term.toUid("中", false); // CHAR 层先插入
  long uidWord = term.toUid("中", false); // WORD 层再插入
  Assert.assertEquals("USER-HC-0 严重违反：同文本跨层级 uid 不等", uidChar, uidWord); // 必须 PASS
  // 扩展 100 例中文常用词 × 5 层级 = 500 次 assertEquals 全通过
  ```
- **AC-FR1-2**：DsString.get(uid) 反查无前缀：任意 200 例 uid 反查字符串首字节 ∈ UTF-8 合法范围，没有出现 0x00~0x05（level 值 0..5）的概率性模式（抽查 200 样本中首字节是 level 值的数量 ≤ 10，排除编码前缀嫌疑）。
- **AC-FR1-3**：反查 level 必须走 text_units：`instancesOfUid(uid)` 返回实例列表，每实例行 getLevel() ∈ {0..5}；同一 uid 返回列表的 level 去重数 ≥ 2（至少出现过 2 个层级）的样本 ≥ 50%。
- **AC-FR2-1（复合主键验证）**：同 doc=7, parent=42, level=ds1 插入 (uid=X) 两次 → 第二次覆盖第一次，count=1；再插入 (uid=X, doc=7, parent=42, level=ds0) → count=2（不同 level 不同行）。
- **AC-FR3-8（HC-7 SPI）**：MockJiebaPlugin 加载后 activeSeg.priority == 10；切"中国人民"得 ["中国","人民"] 2 WORD；断链 fallback 得 4 单字不抛。
- **AC-FR5-3（distance 上下文隔离）**：同文本对 (l="的", r="服务") 在 sentence=S1 中 distance=3；在 sentence=S2 中 distance=7；PAIR_MAP 分别 mix(S1) 和 mix(S2) 返回 3 和 7（两 key 不同，结果独立）。
- **AC-FR6-3（refCount 稳定）**：doc A 含 word="地球"（引用 1 次），覆盖写删除 → DsString.refCount(uidOf("地球")) 减 1（到 0 则 DsString 删除由 DsData 内部管理；但其他文档引用时不删）—— 100 次覆盖后高频 100 词 refCount 方差 ≤1。
- **AC-FR7-1（HC-0 Gate）**：HC-0 100 例跨层 uid 相等 100/100 通过。
- **AC-FR9-1（实体树回溯，HC-9）**：sentence 实例的 `parentId` 命中同一 doc 的 block 实例；block 的 `parentId` 命中章节 DS5 节点；可自底向上回溯 **≥3 级**（sentence→block→章节）。
- **AC-FR9-2（含句检索聚合）**：入参含完整句子 → 命中该句的 **block/segment 优先返回**，并向上聚合命中其章节/篇（不越层：章节索引中不含 sentence 级冗余）。
- **AC-FR9-3（不越层断言）**：block 的倒排在中不含章节/篇级 term 冗余；章节及以上节点只倒排其直接下级的索引。
- **AC-FR10-1（block 限长，HC-10）**：构造 > `maxBlockChars` 的超长段落 → 切出 **≥2 个 block**，每个长度 ≤ `maxBlockChars`，且**不切碎句子**，多 block 的 `parentId` 相同。
- **AC-FR10-2（scope 过滤，HC-11）**：写入 block 带 `scope=dept:HR` 与 `scope=dept:FIN`；查询限定 `dept:HR` 只召回 HR block，FIN block 被过滤掉。
- **AC-FR10-3（scope 0 第 4 store）**：`root/text_index/` 下**不新增**第 4 种 store 目录；scope 复用 DsHashMap/DsColumnar 原语。
- **AC-FR11-1（char 层词/词组倒排，HC-8 豁免）**：`charLevelSearch=true` 时 char 层**可命中词/词组单元**；同时 `pairsTotal` 中 char 层 DIST 对 = **0**（HC-8 仍生效）。
- **AC-FR11-2（精确优先降权）**：语料同时含「人民」「人民群众」；搜「人民」→ **两者均召回**，且「人民」**排序严格先于**「人民群众」（降权不丢召回）。
- **AC-FR12-1（同义关联，HC-12）**：配置 `synonym(人民 ↔ 群众)` 后，搜「人民」可经 `synonymSet` **召回**含「群众」的单元（作为**相关**，弱于精确）。
- **AC-FR12-2（无同义词典不误召）**：未配置 synonym 时，搜「人民」**不因** synonym 机制召回「群众」。

### §4.2 NFR（同前略，NFR-FT8 门槛 ≥ 450 tests 0F0E）

---

## 5. 假设 / 依赖 / 开放问题

### 5.1 假设（关键新假设 USER-HC-0）

- A4（新增 USER-HC-0 推论）：同文本跨层级出现是高频场景（如 CJK 单字既作 CHAR 又为 WORD），unit_id 共享后 term_dict 体积节省 ~ 60%（中文单字 CHAR/WAR 去重）。
- A5：跨层级 distance 不同的场景正确处理（因为 PAIR_MAP 带 parent + level）。

### 5.2 依赖（同前）

DsString / DsHashMap / DsFreeRing / DsColumnar + DsTableAdapter / DsEqIndexStore / ServiceLoader JDK 内置。FarmHash64 手写 0 依赖。

### 5.3 开放问题 OQ-1~OQ-5（决策 A~E）

- OQ-1 决策 A：豁免 3 独立类（fulltext/ 子包）vs 0 文件内嵌类？**决策 A（推荐）独立类**
- OQ-2 决策 B：DIST_BUCKET 饱和 1024（用户 VERBATIM 指令，相关度递减 d=1 最高 / d=1024 最低，>1024 塞 1024 桶），固定 2048 文件（L+R） **决策 B（强制按用户指令，不可修改=1024）**
- OQ-3 决策 C：PAIR_MAP 64-bit mix vs 128-bit 复合？**决策 C（推荐）64-bit mix**
- OQ-4 决策 D：UID_RING shard=高 16 位 65536 文件 vs 单 DsHashMap bitmap？**决策 D（推荐）shard 16-bit**
- OQ-5 决策 E：Seg SPI 接口 Iterator(E-A) vs List(E-B) vs 双模式？**决策 E-A（推荐）流式 Iterator<CutUnit> 大文档省内存**。
- OQ-6 决策 F（REV8）：包含树上界——章节/篇/卷/全本用**单 DS5 + parent_id 链可变深度** vs 固定 4 级？**决策 F-A（推荐）单 DS5 + parent_id 链**（HC-5 兼容，节点数按实际文档结构定）。
- OQ-7 决策 G（REV8）：scope 物理落点——block 表加 scope 列（DsColumnar）vs 独立 `scope→unitId` DsHashMap 倒排？**决策 G-A（推荐）倒排 DsHashMap**（过滤走倒排，避免全表扫）。
- OQ-8 决策 H（REV8）：同义/近义词典来源——内建静态词表 vs 外部可插拔（像 Seg SPI）？**决策 H-A（推荐）外部可插拔 + 内建兜底**（对齐 Seg SPI 插件化风格）。
- OQ-9 决策 I（REV8）：精确优先降权打分归属——P21 只出**召回原语**（char 层择优），打分公式（精确 bonus / synonym 加权）归 **P22**？**决策 I-A（推荐）P21 原语 + P22 打分**（P21 Non-Goals 不含权重）。

---

## 6. Ds Core Spec 继承（100% v1.0，略）

- ④ DIST_BUCKET 邻居上下文隔离（防跨文/跨句泄漏）：`ctxKey = FarmHash.mix4(docId, parentId, (long)level, srcUid)` 作为 `d_<d>_L/R.dshash` 的 hash key，返回该上下文下的邻居集合；同 term 出现在不同句段下邻居列表严格独立（**必选项，防止测试泄漏**）。
- ⑤ `public final int[] bucketAccessCounter = new int[1025]` 测试探针：TOPK 每访问 1 次 L 桶或 R 桶 `counter[d] += 1`；T3-TR-2/TR-4 必精确断言该值，避免任何实现把全量扫后截断伪装成"提前终止"。

USER-HC-0 与 Ds Core Spec 的 **位运算 ID 编码偏好**不存在冲突：Ds Core Spec 偏好"位运算编码组合键（rowId/partition/serverId）"用于跨层级聚合 ID 场景；而 USER-HC-0 明确 **unit_id 的设计语义是「文本唯一标识」而非「层级链聚合 ID」**，因此 unit_id 本身不做位编码，**text_units (unit_id, doc_id, parent_id, level) 四元组合 ID 才是层级实例的唯一标识**，两者解耦，完全兼容 Ds Core 的位运算架构偏好。

---

## 7. Known Verified Tradeoffs（实装写死 4 条，避免新人踩 REV5/REV6 差异坑）

**KT-1 distanceBetween 查询真实语义（§1.3 ③ 原 PAIR_MAP 六元查描述废弃，当前用 DIST_BUCKET L 单向扫）**：
- 真实实现：[FulltextStore.distanceBetween L661-L669](file:///I:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/fulltext/FulltextStore.java#L661-L669) 对 `leftText→rightText`，**仅扫 d=1..1024 下 `ensureDistL(d)` 单向 L 桶（rightUid 作为右侧邻居命中才 return）**；不扫 R 桶，不扫 PAIR_MAP。
- 根因：PAIR_MAP 是六元 mix4(base(mix4(l,r,p,lv)), i, j, 0xC0FFEE)，其中 i,j = 写入时 seq 下标；查询端无 i,j，**PAIR_MAP 仅能作为 HC-6 一致性校验探针（`putPairForTest`），不可作为 distanceBetween 主查询路径**（新人禁用 PAIR_MAP 做查询）。
- 反向查询：要查 `right→left` 距离，必须 **显式 swap 参数** 再调一次 `distanceBetween(rightText, leftText, doc, parent, lv)`（因为 L/R 两桶单向）。

**KT-2 DIST_BUCKET 饱和桶边界（d=1024 最低相关度）**：
- d∈[1,1023] 精确 distance=|j-i|；`d≥1024 一律写入 d=1024`（DIST_MAX 常量饱和）。
- 查询语义：d=1024 桶内所有邻居 **无法进一步区分真实距离**（只保证相关度最低档）。
- 文件数硬上限：1024×2=2048 个 `d_<d>_{L|R}.dshash`（inode 安全，d=1..32 启动预创建 64 文件，33+ 懒创建）。

**KT-3 TOPK 邻居顺序非严格左→右 / 严格 d 升序**：
- topK / neighborsWithin 对**同一 d 下**返回顺序：先扫 L 桶 → 输出所有 R 方向邻居（右侧 d 距离），再扫 R 桶 → 输出所有 L 方向邻居（左侧 d 距离）；**同一 d 内不保证 seq 全局排序**（业务层需要排序请自行 Neighbor.d / Neighbor.uid 二次 sort）。
- 不同 d 之间**严格 d=1→d=1024 升序**（d=1 的所有邻居必早于 d=2，不跨 d 打乱）。
- 满 K 提前 return 铁律（KT-3b）：`out.size() >= K` 立即 return，绝不扫更大 d；`bucketAccessCounter[d]` 为精确断言（每 L/R 访问 +1）。

**KT-4 Seg SPI 仅影响实例 text_units 切分（seq 生成），DIST 计算完全跟随 seq 下标**：
- 业务若需要稳定 distance（跨 Seg 实现/版本一致），请直接调 `buildDistForSeq(docId, parentId, level, uidSeq)` [FulltextStore.buildDistForSeq L731](file:///I:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/fulltext/FulltextStore.java#L731) 自行构造 uids 序列，**不走 Seg SPI**；否则 MockJieba=10 覆盖 Seg5=0 时 seq 不同，distance 会同步变化（行为正确，非 bug）。

**KT-5 ZeroLeak13 独立类白名单（fulltext 包外实现规避）**：
- fulltext 包独立类白名单 = { TermDictStore, FulltextStore, FulltextSegmenter } 三主类（[P21_T5_GatekeeperZeroLeakTest TR3](file:///I:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/P21_T5_GatekeeperZeroLeakTest.java) 扫描检查）。
- 其余 CutUnit / SegOptions / TuRow / FarmHash64 等必须用 `static` 内嵌类；Seg5BuiltinProvider 必须放在 `com.q3lives.ds.database`（非 fulltext 包），禁止放 fulltext。
- NG-3 依赖红线（REV7 修订）：`Class.forName("com.huaban.analysis.jieba.JiebaSegmenter" | "org.wltea.analyzer.core.IKSegmenter" | "spacy.SpaCy")` 全抛 ClassNotFoundException（上述插件放 classpath 才生效，p2p-db 自身 0 实 jar 依赖）。**hanlp 已移出红线**：`com.hankcs:hanlp:portable-1.8.6` 为 compile 实依赖，Gatekeeper 不再对 `com.hankcs.hanlp.HanLP` 断言 CNF。

**KT-6 CHAR 层去 DIST 倒排（HC-8 实装落地，REV7）**：
- 实装点：[FulltextStore.ingestDoc Step4](file:///I:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/fulltext/FulltextStore.java) 用 `if (lv != DS0_CHAR)` 包裹成对 DIST 枚举；char 层仅保留 text_units 实例行（Step2/3）。
- 收益：省去 char 层 N(N-1)/2 对写（约 32B/pair）—— C=1,000 字符/doc ≈ 16MB；C=10,000 字符/doc ≈ 1.6GB。word/phrase/sentence/block/doc 五层 DIST 全保留。
- 兼容：`queryMetrics`/`charLevelSearch`（默认 false）不受影响；charLevelSearch=true 时仍可切 CHAR+WORD+PHRASE 三层做兜底召回（DIST 缺失不影响 byTerm 实例查询）。

**KT-7 HanLP 中文分词以硬依赖 Provider 接入（NG-3 红线修订，REV7）**：
- 实装：`com.q3lives.ds.database.HanLpProvider`（非 fulltext 包，规避 ZeroLeak 13 词类名扫描），`priority=100`（> MockJieba=10 > Seg5=0），注册于 `src/main/resources/META-INF/services/com.q3lives.ds.database.fulltext.FulltextSegmenter`。
- **硬依赖**：`pom.xml` 新增 `<dependency>com.hankcs:hanlp:portable-1.8.6</dependency>`（compile scope）；`available()` 恒 `true`、`priority()=100`，`resolveSegmenter` winner 恒为 HanLpProvider（生产 + 测试全量生效）。NG-3 红线**已将 hanlp 移出**，剩余禁忌为 jieba/IKAnalyzer/SpaCy/Lucene/HNSW/向量库。
- **Gatekeeper 同步**：P21~P29_T5 的 NG-3 `Class.forName` CNF 断言移除 `com.hankcs.hanlp.HanLP`（计数 5→4、9→8 等），`Java_21_起单调递增` 断言随之下调；P21_T2/P21_T5 winner 断言由 `priority>=10` 改为 `priority>=100`。
- 切分范围：仅 `level == DS1_WORD` 直连 HanLP（`HanLP.segment(text)` → `Term.word`）；其余 5 层直接委托 `Seg5BuiltinSegmenter`，保证层级语义不变。
- **规范化语义（REV8 补注，`HanLpProvider.cutWord`）**：HanLP(portable) 的 `Term.offset` **不回填（恒 0）**，故偏移改用**顺序光标** `text.indexOf(word, cursor)` 对齐原文（找不到则退化到光标位）；并做两项规范化以对齐 Seg5/MockJieba 既有语义——① **过滤纯空白 token**（`keepWhitespace` 时保留）；② 原文中**连续的 ASCII 字母/数字/下划线 token 合并为整词**（避免 `T_0_00` 被 HanLP 切成 `T_`+`0`+`00` 碎片）。
- 语义变更：HanLP WORD 层切分与 Seg5 逐字切分不同（CJK 检索语义变更），受影响的 CJK 检索测试须重基线（如 P25_T1 tr13 由 ASCII 整词改用 HanLP 认可整词）。

**KT-8 实体包含树（HC-9，REV8）**：
- 现状偏差：当前 [FulltextStore.ingestDoc](file:///I:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/fulltext/FulltextStore.java#L1293-L1351) 对**同一整篇 text** 按 6 层各切一遍，`parentId = docId*10 + lv`（层间**无父子语义**，仅"同 doc 同层假父"）；[search](file:///I:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/fulltext/FulltextStore.java#L2247-L2268) 仅召回 WORD 层 doc。
- REV8 目标：sentence 的 `parent_id` → 其所属 block；block 的 `parent_id` → 章节 DS5 节点；向上聚合链 `sentence→block→章节→篇→卷→全本`（级别≥4 统一 DS5 + parent_id 链）。
- 迁移影响：`parentId = docId*10+lv` 约定需改为**真实父实体 uid**；text_units IDX1/IDX3 与 DIST ctxKey（含 parentId）全部随动 → **属结构性改造**，须独立 T（T7）。

**KT-9 block 限长（HC-10，REV8）**：
- 现状偏差：当前 [cutBlock](file:///I:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/fulltext/FulltextStore.java#L1145-L1163) 仅按空行分隔，**无长度上限** → 长段落 = 单 block，DIST pair 数 O(B²) 无界。
- REV8 目标：`maxBlockChars`（默认 1024）约束，超长按**句边界**二次切（不切碎句子），多 block 同父（同章节）。

**KT-10 scope 倒排（HC-11，REV8）**：
- 现状偏差：代码中无 scope 概念（仅 fulltext/cluster/join 两文件因业务无关命中词 "Scope"，非本机制）。
- REV8 目标：`(scopeKey,scopeValue) → {unitId}` 与 `unitId → scopeSet` 双向上；复用 DsHashMap / DsColumnar；**不新增第 4 种 store 目录**；实现类放 fulltext 包外或 static 内嵌（ZeroLeak ≤3 顶级类）。

**KT-11 char 层词/词组倒排 + 精确优先降权（HC-8 豁免 + HC-12，REV8）**：
- 关键澄清：HC-8 只禁 char **两两 DIST**，**不禁** char 层「字符→词/词组」检索后置表 —— 二者物理目标不同（前者距离对 O(C²)，后者倒排链），**可共存**。
- 现状偏差：当前 [cutWord](file:///I:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/fulltext/FulltextStore.java#L1078-L1104) CJK 一字一词、[cutPhrase](file:///I:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/fulltext/FulltextStore.java#L1106-L1118) 仅相邻两词拼接，**无 char 层词/词组后置表**、**无同义/近义关系**。
- REV8 目标：char 层可倒排词/词组（消歧用）；`synonymSet` 一等属性；召回=**精确优先降权**（「人民」2/2 ≻「人民群众」2/4）。
- 边界：**打分公式归 P22**；P21 只交付 char 层**择优召回原语**。
