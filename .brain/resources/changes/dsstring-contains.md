---
updated: "2026-09-01T14:33:02Z"
---
# DsString.contains 新增 API + 引用计数零副作用验证

## Task

- 新增 `DsString.contains(String)`：判断字符串是否在存储中存在，不改变引用计数、不产生写 IO。

## Deliverables

- [DsString.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/core/DsString.java#L148-L164) 新增 `contains` 方法：
  - 空字符串与 null 归一化（与 `add` 语义一致）。
  - 先走 `DsData.getIndexId(byte[])`（masterIndex 纯读，无写 IO），命中再读 `getRefCountByIndexId > 0` 防御性校验。
- [DsStringBlockStoreTest](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/DsStringBlockStoreTest.java#L26-L53) `testContainsBasic`：
  - 空库 `contains("abc"/null/"")` 均 false。
  - `add("abc")` 后 `contains("abc")=true`、`contains("xyz")=false`。
  - `add("")` 与 `add(null)` 返回同一 indexId，`contains("")` 与 `contains(null)` 互 true。
  - `remove(abcId)` 后 `contains("abc")=false`、空串仍 true。
- [DsStringRefCountTest](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/DsStringRefCountTest.java#L34-L63) `testContainsDoesNotChangeRefCount`：
  - refCount=1 → contains → refCount 保持 1。
  - retain 后 refCount=2，连续 5 次 contains → refCount 保持 2。
  - release 两次到 refCount=0，`contains("hello")=false`。

## Verification Recipe

```bash
mvn test -pl p2p-db \
  '-Dtest=ds.DsStringBlockStoreTest,ds.DsStringRefCountTest' \
  '-Dsurefire.useFile=false'
```

Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`

## Invariants / Rules Promoted

- 任何 DsString 上层新增方法，若语义是只读（不改变生命周期/引用计数），必须走 `DsData.getIndexId` 等纯读路径；禁止用 `add + remove` 模拟读语义，避免无谓写 IO 与 refCount 抖动。
- `contains` 的实现必须同时满足：① masterIndex 存在映射、② refCount > 0；仅查 masterIndex 不足以在并发极端窗口下严格对外语义（`masterIndex.remove` 与 bucket refCount 归零之间存在顺序空窗）。
