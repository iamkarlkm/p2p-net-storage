---
updated: "2026-09-01T15:21:13Z"
---
# DsString.getIndexId(String) 新增 API 验证

## Deliverables

- [DsString.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/core/DsString.java#L166-L188) 新增 `Long getIndexId(String key) throws IOException`：
  - 语义：返回字符串对应的 indexId；不存在（masterIndex miss）或已删除到 `refCount<=0` 时返回 `null`。
  - 纯读路径：`DsData.getIndexId(byte[])` + `DsData.getRefCountByIndexId(long) > 0` 二次防御校验。
  - 零副作用：不产生写 IO，不改变引用计数，Copy-On-Write 不触发。
  - `null` 与 `""` 归一化（与 `add`、`contains` 保持一致）。
- 关系不变式：对任意 `s`，`ds.contains(s) == (ds.getIndexId(s) != null)` 恒真。

## Verification Recipe

```bash
mvn test -pl p2p-db \
  '-Dtest=ds.DsStringBlockStoreTest,ds.DsStringRefCountTest' \
  '-Dsurefire.useFile=false'
```

Expected: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`

包含新增测例 [testGetIndexId](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/DsStringBlockStoreTest.java#L55-L93) 覆盖：
- 空库 miss → null
- add 后命中 → 返回值与 add 返回 id 相等
- 重复 add 同字符串 → id 保持一致
- `null` 与 `""` 归一化，返回同一 id
- release 两次：refCount=1 时仍命中，refCount=0 时退回 null

## 注意事项（第一次失败）

初稿在 refCount=2（add × 2）时，错误地使用单次 `remove(id)` 来验证归零，而 [DsData.remove](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/bucket/DsData.java#L316-L346) 仅做 refCount--，不等价于"强制删除"。修复方法：与 DsString 既有 API 语义保持一致，用 `release(id)` × 次数的方式精准驱动 refCount 从 2 → 1 → 0。
