# dsdb-columnar-store

- 新增稀疏列存储（零行头）：rowId 固定 64 位；每个 colId 一张 DsHashMap(rowId->valueId)；value 存储使用 DsFixedBucketStore，type=col_<colId>。
- colId 分配：columns.meta.yaml 维护 colKey->colId 与 nextColId；colId 只增不回收；删除列标记 deleted。
- 写入语义：oldValueId!=0 时调用 bucket.update；仅当返回 newValueId!=oldValueId（迁移）才更新列 map 指针。
- 删除列（硬删除）：批量遍历列 map 回收 valueId（bucket.remove）并清空 map；随后 best-effort 删除 DsHashMap 相关文件（Windows mmap 可能导致删除失败）。
- 表/复合列元数据：table.meta.yaml 记录 @DsField 列定义与 colId，以及 @DsCompositeField 的 group/length/bit ranges；signature 不变时不覆写。
- 复合列组也会注册为一列：每个 group 对应一个 colId（colKey=`<entity>#@composite:<group>`），可通过 columnar 存储直接写入/读取该 group 的 bytes。
- 元数据校验增强：拒绝重复 DsField 同名但类型/长度不一致；拒绝复合列组同名长度不一致；拒绝复合列位段越界/重叠/同名 item 重复。

验证：mvn -pl p2p-db test
