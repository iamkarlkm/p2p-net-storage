$ErrorActionPreference = "Stop"
$diagramsRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$archify = "node ""c:\Users\karl\.trae\skills\archify\bin\archify.mjs"""

Write-Host "Bootstrapping 25 Archify diagrams under $diagramsRoot" -ForegroundColor Cyan

# ---------------- 0. Create module dirs ----------------
@("p2p-db","p2p-core","p2p-sync","p2p-ws","p2p-im") | ForEach-Object {
    $d = Join-Path $diagramsRoot $_
    New-Item -ItemType Directory -Force -Path $d | Out-Null
    Write-Host "  + dir: $d" -ForegroundColor DarkGray
}

# ---------------- 1. p2p-db ----------------
@"
{
  "schema_version": 1, "diagram_type": "architecture",
  "meta": {
    "title": "p2p-db Architecture",
    "visual_preset": "blueprint", "animation": "trace",
    "quality_profile": "showcase", "locale": "zh-CN",
    "views": [
      {"id":"orm","label":"ORM Write Path","focus":["java-orm","genericmgr","db-local","mvcc","eqindex"],"note":"Java 对象 → GenericManager → DsDatabaseLocal 写 MVCC + Index"},
      {"id":"binlog","label":"Binlog & Backup","focus":["db-local","binlog","full-backup","inc-backup","timeline"],"note":"写事件进 Binlog → 全量 + 增量备份链 → 时间点恢复"}
    ]
  },
  "components": [
    {"id":"java-orm","type":"frontend","label":"Java ORM Layer","sublabel":"@DsTable / @DsField","pos":[40,130],"size":[136,68],"tag":"Java SDK"},
    {"id":"genericmgr","type":"backend","label":"GenericManager","sublabel":"Query Planner","pos":[230,130],"size":[140,68],"tag":"p2p-db"},
    {"id":"db-local","type":"backend","label":"DsDatabaseLocal","sublabel":"MVCC + Binlog","pos":[430,130],"size":[156,78],"tag":"p2p-db"},
    {"id":"mvcc","type":"database","label":"MVCC Store","sublabel":"version records","pos":[650,70],"size":[138,64],"tag":"versioned"},
    {"id":"eqindex","type":"database","label":"EqIndex / Range","sublabel":"Indexed RowIdSet","pos":[650,210],"size":[138,64],"tag":"O(1) lookup"},
    {"id":"binlog","type":"messagebus","label":"DsBinlogStore","sublabel":"REDO/UNDO Frames","pos":[430,290],"size":[156,60],"tag":"append only"},
    {"id":"full-backup","type":"cloud","label":"Full Backup","sublabel":"data/index/binlog tar","pos":[230,350],"size":[140,60],"tag":"snapshot"},
    {"id":"inc-backup","type":"cloud","label":"Incremental","sublabel":"delta binlog","pos":[40,350],"size":[136,60],"tag":"diff chain"},
    {"id":"timeline","type":"external","label":"Timeline Restore","sublabel":"replay binlog [a,b)","pos":[650,350],"size":[138,60],"tag":"PITR"}
  ],
  "boundaries": [
    {"kind":"region","label":"p2p-db Core Runtime","wraps":["genericmgr","db-local","mvcc","eqindex","binlog"],"pad":18},
    {"kind":"security-group","label":"Persistence","wraps":["mvcc","eqindex","binlog"],"pad":12}
  ],
  "connections": [
    {"from":"java-orm","to":"genericmgr","label":"put / remove / query","variant":"emphasis","route":"straight"},
    {"from":"genericmgr","to":"db-local","label":"plan → execute","variant":"emphasis","route":"straight"},
    {"from":"db-local","to":"mvcc","label":"version write","route":"orthogonal-v","labelAt":[508,150]},
    {"from":"db-local","to":"eqindex","label":"index update","route":"orthogonal-v","labelAt":[508,230]},
    {"from":"db-local","to":"binlog","label":"append REDO/UNDO","variant":"dashed","fromSide":"bottom","toSide":"top"},
    {"from":"binlog","to":"full-backup","label":"BINLOG_FIXED/DYN/BYTES","variant":"dashed","fromSide":"bottom","toSide":"right","via":[[508,350],[370,350],[370,400]]},
    {"from":"full-backup","to":"inc-backup","label":"delta diff","variant":"dashed","route":"straight","labelAt":[160,320]},
    {"from":"binlog","to":"timeline","label":"replay events","variant":"security","fromSide":"right","toSide":"left","via":[[588,320],[588,380],[720,380]]}
  ],
  "cards": [
    {"dot":"cyan","title":"Core Layers","items":["Java ORM 注解定义表结构","GenericManager 做 Query Planner + 多 Index 交并","DsDatabaseLocal 单进程 MVCC + Binlog"]},
    {"dot":"emerald","title":"Durability","items":["物理 WAL 已删除，Binlog 唯一持久化流水","Full=全量快照，Incremental=Binlog 差量链","Timeline Restore = base + replay [a,b)"]},
    {"dot":"rose","title":"Index Invariant","items":["Index 仅缩小候选 RowIdSet","最终过滤权威 matchesAll(wrapper,entity)","MVCC 列 versionId 索引，非 MVCC 列 rowId 索引"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-db\01-architecture.json") -Encoding UTF8

@"
{
  "schema_version": 1, "diagram_type": "workflow",
  "meta": {
    "title": "p2p-db MVCC Write + Binlog Workflow",
    "visual_preset": "signal-flow", "animation": "trace",
    "quality_profile": "showcase", "locale": "zh-CN"
  },
  "lanes": [
    {"id":"orm","label":"ORM Caller"},
    {"id":"planner","label":"Query Planner"},
    {"id":"mvcc","label":"MVCC Engine"},
    {"id":"binlog","label":"Binlog Store"},
    {"id":"backup","label":"Backup Chain"},
    {"id":"recover","label":"Recovery","variant":"exception"}
  ],
  "phases": [
    {"id":"plan","label":"Plan + Gate","fromCol":0,"toCol":1},
    {"id":"write","label":"Execute + Durable","fromCol":2,"toCol":3,"variant":"emphasis"},
    {"id":"post","label":"Backup + Recovery","fromCol":4,"toCol":5}
  ],
  "mainPath": ["user-call","plan-query","mvcc-write","binlog-append","full-snap","replay-binlog"],
  "nodes": [
    {"id":"user-call","lane":"orm","col":0,"type":"external","label":"ORM put(entity)","sublabel":"Java API"},
    {"id":"plan-query","lane":"planner","col":1,"type":"backend","label":"matchesAll gate","sublabel":"schema check + row key"},
    {"id":"mvcc-write","lane":"mvcc","col":2,"type":"database","label":"MVCC insert version","sublabel":"versionId & rowId"},
    {"id":"index-update","lane":"planner","col":2,"type":"backend","label":"EqIndex + Range update","tag":"rowIdSet"},
    {"id":"binlog-append","lane":"binlog","col":3,"type":"messagebus","label":"append REDO/UNDO","sublabel":"FNV-1a tableId"},
    {"id":"full-snap","lane":"backup","col":4,"type":"cloud","label":"Full snapshot","tag":"Files.copy"},
    {"id":"inc-delta","lane":"backup","col":4,"type":"cloud","label":"Inc binlog diff","tag":"manifest compare"},
    {"id":"replay-binlog","lane":"recover","col":5,"type":"backend","label":"replay [basePos,end)","tag":"parallel by rowKey"},
    {"id":"rollback-undo","lane":"recover","col":5,"type":"security","label":"UNDO skip frame","sublabel":"FLAG_BIT_UNDO_LOG"}
  ],
  "edges": [
    {"from":"user-call","to":"plan-query","variant":"emphasis","route":"drop"},
    {"from":"plan-query","to":"mvcc-write","variant":"emphasis","route":"drop"},
    {"from":"mvcc-write","to":"index-update","variant":"default","route":"bottom-channel"},
    {"from":"index-update","to":"binlog-append","variant":"emphasis","route":"drop"},
    {"from":"binlog-append","to":"full-snap","variant":"dashed","route":"drop"},
    {"from":"full-snap","to":"inc-delta","variant":"dashed","route":"bottom-channel"},
    {"from":"inc-delta","to":"replay-binlog","variant":"security","route":"drop"},
    {"from":"binlog-append","to":"rollback-undo","variant":"security","route":"outside-right"}
  ],
  "cards": [
    {"dot":"emerald","title":"MVCC Column Opt-In","items":["@DsField(mvcc=true) 列索引 versionId；其他列 rowId","schema signature 含 |M=，开关 mvcc 会改 schemaId 防止脏读"]},
    {"dot":"rose","title":"Binlog Invariant","items":["所有 INSERT/UPDATE/DELETE 必 append Binlog","并行重放按 rowKey 分区，实际写串行 replayApplyLock","UNDO frame 重放跳过，数据库只向前"]},
    {"dot":"cyan","title":"Backup Semantics","items":["Full Snapshot 用 Files.copy（非 hard link），快照独立","增量只带 Binlog 三角色（FIXED/DYN/BYTES），恢复先 base → 按时间序叠增量"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-db\02-workflow.json") -Encoding UTF8

@"
{
  "schema_version": 1, "diagram_type": "sequence",
  "meta": {
    "title": "p2p-db MVCC put → Binlog → Backup Sequence",
    "viewBox":[900,760], "animation":"trace",
    "quality_profile":"showcase", "locale":"zh-CN"
  },
  "participants": [
    {"id":"app","type":"external","label":"Java App","sublabel":"ORM"},
    {"id":"gm","type":"backend","label":"GenericManager","sublabel":"QueryWrapper"},
    {"id":"db","type":"backend","label":"DsDatabaseLocal","sublabel":"MVCC + DsBinlogStore"},
    {"id":"mvcc","type":"database","label":"MVCC Bucket","sublabel":"version records"},
    {"id":"idx","type":"database","label":"EqIndexStore","sublabel":"RowIdSet"},
    {"id":"bl","type":"messagebus","label":"DsBinlog","sublabel":"REDO frame"},
    {"id":"fs","type":"cloud","label":"Backup Dir","sublabel":"full + inc"}
  ],
  "segments": [
    {"from":150,"to":320,"label":"Plan + Schema Gate"},
    {"from":340,"to":560,"label":"MVCC + Index Write"},
    {"from":580,"to":700,"label":"Durability"}
  ],
  "messages": [
    {"id":"c1","from":"app","to":"gm","y":190,"label":"put(User(id=7, name=x))","variant":"emphasis"},
    {"id":"c2","from":"gm","to":"db","y":240,"label":"putEntity(User.class, entity)","variant":"emphasis"},
    {"id":"c3","from":"db","to":"mvcc","y":300,"label":"alloc versionId + rowId","variant":"default"},
    {"id":"c4","from":"mvcc","to":"db","y":340,"label":"bytes written","variant":"return"},
    {"id":"c5","from":"db","to":"idx","y":400,"label":"add(versionId, colValue)","variant":"default"},
    {"id":"c6","from":"idx","to":"db","y":440,"label":"indexed ok","variant":"return"},
    {"id":"c7","from":"db","to":"bl","y":500,"label":"append REDO tableId=Fnv(User)","variant":"dashed"},
    {"id":"c8","from":"bl","to":"db","y":540,"label":"sequence N","variant":"return"},
    {"id":"c9","from":"db","to":"gm","y":600,"label":"entity ok","variant":"return"},
    {"id":"c10","from":"gm","to":"app","y":640,"label":"future done","variant":"return"},
    {"id":"c11","from":"bl","to":"fs","y":680,"label":"async snapshot diff → inc backup","variant":"dashed","route":"orthogonal-h"}
  ],
  "activations": [
    {"participant":"gm","from":190,"to":648,"type":"backend"},
    {"participant":"db","from":236,"to":608,"type":"backend"},
    {"participant":"mvcc","from":296,"to":346,"type":"database"},
    {"participant":"idx","from":396,"to":446,"type":"database"},
    {"participant":"bl","from":496,"to":546,"type":"messagebus"}
  ],
  "cards": [
    {"dot":"emerald","title":"MVCC Write Order","items":["先分配 versionId/rowId 写 MVCC 槽 → 更 Index → 最后 append Binlog REDO","Binlog 位置 exclusive watermark: fixedOffset=下帧偏移, sequence=最后写帧序号"]},
    {"dot":"rose","title":"FNV tableId","items":["Binlog 每条表用 class.getName() 的 FNV-1a 作 tableId","列 colId 用 schema 中 0-based 列序，schema 改则 signature 改变"]},
    {"dot":"amber","title":"New Slot All-Zero Invariant","items":["DsFixedBucketStore.get 对新 slot 返回全零","old row 解读必须 isAllZero(bytes) 判定，否则 INSERT 会错写 UPDATE + UNDO"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-db\03-sequence.json") -Encoding UTF8

@"
{
  "schema_version": 1, "diagram_type": "dataflow",
  "meta": {
    "title": "p2p-db Data Flow: Write → Binlog → Backup → Restore",
    "viewBox":[1080,760],"animation":"trace","quality_profile":"showcase","locale":"zh-CN"
  },
  "stages": [
    {"label":"Entities"},{"label":"Engine"},{"label":"Primary Storage"},{"label":"Durable Stream"},{"label":"Backup / Restore"}
  ],
  "nodes": [
    {"id":"entity","type":"frontend","label":"Java Entities","sublabel":"@DsTable","stage":0,"row":1,"tag":"ORM"},
    {"id":"plan","type":"backend","label":"Query + MVCC Planner","sublabel":"GenericManager","stage":1,"row":1,"tag":"p2p-db"},
    {"id":"rowstore","type":"database","label":"Row / MVCC Store","sublabel":"DsFixedBucketStore","stage":2,"row":0,"tag":"hot"},
    {"id":"indexstore","type":"database","label":"EqIndex / Range","sublabel":"RowIdSet","stage":2,"row":2,"tag":"cold index"},
    {"id":"binlog","type":"messagebus","label":"Binlog Pipeline","sublabel":"REDO/UNDO 3 roles","stage":3,"row":1,"tag":"append only"},
    {"id":"fullb","type":"cloud","label":"Full Backup","sublabel":"data+index+meta","stage":4,"row":0,"tag":"snapshot"},
    {"id":"incb","type":"cloud","label":"Inc Backup","sublabel":"binlog delta","stage":4,"row":2,"tag":"manifest diff"},
    {"id":"restore","type":"backend","label":"Timeline Restore","sublabel":"DsTimelineRestore","stage":4,"row":4,"tag":"PITR"}
  ],
  "flows": [
    {"id":"e1","from":"entity","to":"plan","label":"put/remove/query","classification":"entity graph","variant":"emphasis","fromSide":"right","toSide":"left","via":[[205,280],[205,280]],"labelAt":[205,280]},
    {"id":"e2","from":"plan","to":"rowstore","label":"version + row bytes","classification":"MVCC write","variant":"emphasis","fromSide":"top","toSide":"bottom","via":[[320,240],[320,180],[470,180]]},
    {"id":"e3","from":"plan","to":"indexstore","label":"rowIdSet updates","classification":"index deltas","variant":"default","fromSide":"bottom","toSide":"top","via":[[320,310],[320,360],[470,360]]},
    {"id":"e4","from":"rowstore","to":"binlog","label":"REDO frame","classification":"durable","variant":"dashed","fromSide":"right","toSide":"left"},
    {"id":"e5","from":"indexstore","to":"binlog","label":"index redo","classification":"durable","variant":"dashed"},
    {"id":"e6","from":"binlog","to":"fullb","label":"BINLOG_FIXED/DYN/BYTES","classification":"full snapshot","variant":"emphasis"},
    {"id":"e7","from":"binlog","to":"incb","label":"delta frames","classification":"incremental","variant":"dashed"},
    {"id":"e8","from":"fullb","to":"restore","label":"base restore","classification":"data files","variant":"security","fromSide":"bottom","toSide":"top"},
    {"id":"e9","from":"incb","to":"restore","label":"replay [basePos,end)","classification":"ordered frames","variant":"security","fromSide":"bottom","toSide":"top"}
  ],
  "cards": [
    {"dot":"cyan","title":"Primary Split","items":["MVCC 列=versionId 路由，历史版本保留；非 MVCC=rowId 路由，更新即删旧索引"]},
    {"dot":"rose","title":"Binlog 3 Roles","items":["BINLOG_FIXED=定长；BINLOG_DYN=变长；BINLOG_BYTES=大字节；备份目录 3 子目录；恢复映射回 dbHome 树"]},
    {"dot":"emerald","title":"Restore 2-Phase","items":["Phase 1: 把备份链的 binlog 复制到 targetDbHome/_binlog/","Phase 2: 显式调用 DsDatabaseLocal.replayBinlog(base,end) 应用事件"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-db\04-dataflow.json") -Encoding UTF8

@"
{
  "schema_version": 1, "diagram_type": "lifecycle",
  "meta": {
    "title": "p2p-db MVCC Entity Version Lifecycle",
    "viewBox":[980,680], "animation":"trace",
    "visual_preset": "signal-flow", "quality_profile":"showcase", "locale":"zh-CN"
  },
  "lanes": [
    {"id":"main","label":"MVCC Version Rail"},
    {"id":"index","label":"Index Invalidation"},
    {"id":"binlog","label":"Binlog + Backup"},
    {"id":"term","label":"Terminal States"}
  ],
  "states": [
    {"id":"new","type":"start","label":"New Entity","sublabel":"pre-insert","lane":"main","col":0,"step":"01","tag":"transient"},
    {"id":"inserted","type":"active","label":"Inserted v1","sublabel":"rowId + versionId","lane":"main","col":1,"step":"02","tag":"committed"},
    {"id":"update-v2","type":"active","label":"Update v2","sublabel":"patchNonMvccCols","lane":"main","col":2,"step":"03","tag":"visible"},
    {"id":"update-v3","type":"active","label":"Update v3","sublabel":"readTimestamp resolve","lane":"main","col":3,"step":"04","tag":"visible"},
    {"id":"remove-v4","type":"failure","label":"Removed Tombstone","sublabel":"row tombstone","lane":"main","col":4,"step":"05","tag":"invisible"},
    {"id":"idx-drop","type":"waiting","label":"Index Drop Old","sublabel":"non-mvcc delete","lane":"index","col":1,"tag":"cleanup"},
    {"id":"idx-version","type":"waiting","label":"Index Add New","sublabel":"mvcc -> versionId","lane":"index","col":3,"tag":"retain hist"},
    {"id":"redo-frm","type":"active","label":"Append REDO Frame","sublabel":"tableId FNV-1a","lane":"binlog","col":2,"tag":"durable"},
    {"id":"snap-full","type":"waiting","label":"Full Snapshot","sublabel":"Files.copy","lane":"binlog","col":4,"tag":"stable"},
    {"id":"ok-fin","type":"success","label":"Restore OK","sublabel":"replay [a,b)","lane":"term","col":2,"tag":"terminal"},
    {"id":"corrupt","type":"failure","label":"Schema Mismatch","sublabel":"signature mismatch","lane":"term","col":4,"tag":"fatal"}
  ],
  "transitions": [
    {"from":"inserted","to":"idx-drop","variant":"security","route":"straight","fromSide":"bottom","toSide":"top"},
    {"from":"update-v2","to":"redo-frm","variant":"dashed","route":"straight","fromSide":"bottom","toSide":"top"},
    {"from":"update-v3","to":"idx-version","variant":"default","route":"straight","fromSide":"bottom","toSide":"top"},
    {"from":"remove-v4","to":"snap-full","variant":"dashed","route":"straight","fromSide":"bottom","toSide":"top"},
    {"from":"redo-frm","to":"ok-fin","variant":"emphasis","route":"drop"},
    {"from":"new","to":"corrupt","variant":"security","route":"outside-right"}
  ],
  "cards": [
    {"dot":"cyan","title":"Version Visibility","items":["读取 readTimestamp 解析可见版本；非 MVCC 列 patchNonMvccCols 从最新提交写入覆盖","@DsTable(mvcc=true) 等价所有列 mvcc=true"]},
    {"dot":"rose","title":"Signature Safety","items":["SchemaDef.signature 的 |M= 列级位标记；开关 mvcc 改 schemaId；老版本读会 signature mismatch 终止而非脏读"]},
    {"dot":"emerald","title":"Restore Replay","items":["replayBinlog 期间 DsBinlogContext.isInReplay() 短路 append，不产生新 Binlog","并行重放：按 rowKey 分片；实际 put/remove 串行锁"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-db\05-lifecycle.json") -Encoding UTF8


# ---------------- 2. p2p-core ----------------
@"
{
  "schema_version": 1, "diagram_type": "architecture",
  "meta": {
    "title": "p2p-core Network Architecture",
    "visual_preset": "blueprint","animation":"trace","quality_profile":"showcase","locale":"zh-CN",
    "views":[{"id":"transports","label":"Transports","focus":["udp","quic","tcp","ws"],"note":"4 种传输统一 Frame 格式"},{"id":"crypto","label":"Crypto Modes","focus":["session","auth","cryptomode"],"note":"4 Cryptomode: PLAIN/RANDOM/AUTH_SIG/CERT_CHAIN"}]
  },
  "components": [
    {"id":"app-sdk","type":"frontend","label":"App / SDK","sublabel":"Java / Dart / TS / C","pos":[30,180],"size":[136,72]},
    {"id":"rpc","type":"backend","label":"RPC Layer","sublabel":"request/stream/bidi","pos":[220,180],"size":[148,72],"tag":"p2p-core"},
    {"id":"session","type":"backend","label":"Session Manager","sublabel":"P2PCommand ordinal","pos":[420,180],"size":[148,72],"tag":"p2p-core"},
    {"id":"auth","type":"security","label":"Auth Handshake","sublabel":"4 Crypto Modes","pos":[620,100],"size":[144,68],"tag":"TLS / sig"},
    {"id":"frame","type":"messagebus","label":"Frame Codec","sublabel":"maxFrameBytes","pos":[420,310],"size":[148,60],"tag":"wire format"},
    {"id":"udp","type":"cloud","label":"UDP v2 Segment","sublabel":"MTU safe","pos":[220,400],"size":[148,58],"tag":"p2p-core"},
    {"id":"quic","type":"cloud","label":"QUIC Stream","sublabel":"mux over UDP","pos":[420,400],"size":[148,58],"tag":"QUIC"},
    {"id":"tcp","type":"cloud","label":"TCP Stream","sublabel":"byte stream","pos":[620,400],"size":[148,58],"tag":"TCP"},
    {"id":"ws","type":"cloud","label":"WebSocket","sublabel":":80/:443 FW OK","pos":[820,400],"size":[148,58],"tag":"WS"}
  ],
  "boundaries": [
    {"kind":"region","label":"p2p-core Core","wraps":["rpc","session","frame","auth"],"pad":16},
    {"kind":"security-group","label":"4 Transports","wraps":["udp","quic","tcp","ws"],"pad":12}
  ],
  "connections": [
    {"from":"app-sdk","to":"rpc","label":"unary/stream/bidi","variant":"emphasis","route":"straight"},
    {"from":"rpc","to":"session","label":"P2PCommand router","variant":"emphasis","route":"straight"},
    {"from":"session","to":"auth","label":"handshake (client/server random)","variant":"security"},
    {"from":"session","to":"frame","label":"encode frame","variant":"default"},
    {"from":"frame","to":"udp","label":"UDP v2 segmented","variant":"dashed"},
    {"from":"frame","to":"quic","label":"QUIC single stream","variant":"dashed"},
    {"from":"frame","to":"tcp","label":"TCP framed","variant":"dashed"},
    {"from":"frame","to":"ws","label":"WS binary frame","variant":"dashed"}
  ],
  "cards": [
    {"dot":"cyan","title":"Uniform Wire Format","items":["RPC Layer 下所有 4 传输进同一 Frame Codec；maxFrameBytes 一致；P2PCommand 序号是路由唯一 ID"]},
    {"dot":"rose","title":"Crypto Mode Contract","items":["PLAIN=无加密；RANDOM=会话随机密钥；AUTH_SIG=双方握手证书签名；CERT_CHAIN=真实 X509 链；模式可逐连接独立选择"]},
    {"dot":"emerald","title":"Cancel Contract","items":["RPC StdCancel 语义：Java SDK cancel() → server STOP frame；Dart Python C TS 一致；p2p-core-wire-format 跨语言对齐"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-core\01-architecture.json") -Encoding UTF8

@"
{
  "schema_version": 1, "diagram_type": "workflow",
  "meta": {
    "title": "p2p-core Connection Handshake (4 Crypto Modes)",
    "visual_preset": "signal-flow","animation":"trace","quality_profile":"showcase","locale":"zh-CN"
  },
  "lanes": [
    {"id":"client","label":"Client"},{"id":"wire","label":"Wire 4 Transports"},
    {"id":"crypto","label":"Crypto Engine"},{"id":"server","label":"Server"},
    {"id":"err","label":"Reject Path","variant":"exception"}
  ],
  "phases":[{"id":"hello","label":"Hello + Nonce","fromCol":0,"toCol":1},{"id":"exchange","label":"Key Exchange","fromCol":2,"toCol":2,"variant":"emphasis"},{"id":"session","label":"Session OK","fromCol":3,"toCol":4}],
  "mainPath":["open","client-random","server-random","derive-key","session-ready","app-call"],
  "nodes": [
    {"id":"open","lane":"client","col":0,"type":"frontend","label":"Dial(address, mode)","sublabel":"4 modes"},
    {"id":"client-random","lane":"wire","col":1,"type":"messagebus","label":"ClientHello","sublabel":"clientNonce"},
    {"id":"server-random","lane":"wire","col":1,"type":"messagebus","label":"ServerHello","sublabel":"serverNonce"},
    {"id":"derive-key","lane":"crypto","col":2,"type":"security","label":"KDF + Mode Select","sublabel":"PLAIN/RANDOM/AUTH/CERT"},
    {"id":"sig-verify","lane":"crypto","col":2,"type":"security","label":"Sig Verify","tag":"AUTH / CERT"},
    {"id":"session-ready","lane":"server","col":3,"type":"backend","label":"Session Registered","sublabel":"conn id"},
    {"id":"app-call","lane":"server","col":4,"type":"success","label":"RPC Ready","sublabel":"maxFrameBytes"},
    {"id":"bad-sig","lane":"err","col":2,"type":"failure","label":"Bad Signature","tag":"drop"},
    {"id":"no-cert","lane":"err","col":4,"type":"failure","label":"No Cert Chain","tag":"CERT guard"}
  ],
  "edges": [
    {"from":"open","to":"client-random","variant":"emphasis","route":"drop"},
    {"from":"client-random","to":"server-random","variant":"default","route":"bottom-channel"},
    {"from":"server-random","to":"derive-key","variant":"emphasis","route":"drop"},
    {"from":"derive-key","to":"sig-verify","variant":"security","route":"bottom-channel"},
    {"from":"sig-verify","to":"session-ready","variant":"emphasis","route":"drop"},
    {"from":"session-ready","to":"app-call","variant":"emphasis","route":"drop"},
    {"from":"sig-verify","to":"bad-sig","variant":"security","route":"outside-right"},
    {"from":"session-ready","to":"no-cert","variant":"security","route":"outside-right"}
  ],
  "cards": [
    {"dot":"cyan","title":"Hello Nonce","items":["PLAIN/RANDOM/AUTH_SIG/CERT_CHAIN 都先发 ClientHello/ServerHello 随机 nonce；KDF 双方 nonce + 模式独立输出会话密钥"]},
    {"dot":"rose","title":"Security Guards","items":["CERT_CHAIN：若无证书链直接拒绝；AUTH_SIG：签名错直接 drop，不复用连接；避免明文可 downgrade 攻击"]},
    {"dot":"emerald","title":"Ordinal Contract","items":["P2PCommand enum.ordinal 在 Java/Dart/Python/C/TS 生成的 wire format 必须完全一致；改动 enum 顺序会引起跨语言不可互操作，需 p2p-core-wire-format 重新发布"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-core\02-workflow.json") -Encoding UTF8

@"
{
  "schema_version": 1, "diagram_type": "sequence",
  "meta": {
    "title": "p2p-core QUIC Auth_SIG Handshake + First RPC",
    "viewBox":[900,760],"animation":"trace","quality_profile":"showcase","locale":"zh-CN"
  },
  "participants": [
    {"id":"cli","type":"external","label":"Java/Dart Client"},
    {"id":"quic-cli","type":"backend","label":"QUIC Client","sublabel":"UDP 0-RTT/1-RTT"},
    {"id":"quic-srv","type":"backend","label":"QUIC Server","sublabel":"listener"},
    {"id":"srv","type":"backend","label":"p2p-core Server","sublabel":"Session"},
    {"id":"crypto","type":"security","label":"Crypto Engine","sublabel":"AUTH_SIG"},
    {"id":"router","type":"messagebus","label":"P2PCommand Router","sublabel":"ordinal"},
    {"id":"handler","type":"backend","label":"Handler","sublabel":"biz logic"}
  ],
  "segments":[{"from":150,"to":300,"label":"QUIC + Crypto Hello"},{"from":320,"to":520,"label":"Session Registered"},{"from":540,"to":700,"label":"First RPC"}],
  "messages": [
    {"m1","from":"cli","to":"quic-cli","y":190,"label":"dial(host, AUTH_SIG)","variant":"emphasis"},
    {"m2","from":"quic-cli","to":"quic-srv","y":235,"label":"QUIC Initial + ClientHello(nonce)","variant":"security"},
    {"m3","from":"quic-srv","to":"quic-cli","y":275,"label":"ServerHello(nonce) + ServerCertSig","variant":"security","variant":"return"},
    {"m4","from":"quic-cli","to":"crypto","y":330,"label":"verify(sig)","variant":"security"},
    {"m5","from":"crypto","to":"srv","y":370,"label":"session key OK","variant":"return"},
    {"m6","from":"srv","to":"router","y":425,"label":"register(sessionId)","variant":"default"},
    {"m7","from":"router","to":"srv","y":465,"label":"routing table ready","variant":"return"},
    {"m8","from":"cli","to":"quic-cli","y":518,"label":"DbNodePutReq(stream chunked)","variant":"emphasis"},
    {"m9","from":"quic-cli","to":"quic-srv","y":560,"label":"encrypted PUT frame","variant":"default"},
    {"m10","from":"quic-srv","to":"handler","y":605,"label":"call put handler","variant":"default"},
    {"m11","from":"handler","to":"cli","y":650,"label":"putResp done","variant":"return"}
  ],
  "activations": [
    {"participant":"quic-cli","from":188,"to":608,"type":"backend"},
    {"participant":"quic-srv","from":232,"to":610,"type":"backend"},
    {"participant":"srv","from":366,"to":612,"type":"backend"},
    {"participant":"router","from":422,"to":468,"type":"messagebus"}
  ],
  "cards": [
    {"dot":"emerald","title":"Handshake Order","items":["QUIC → Hello nonce 交换 → AUTH_SIG verify signature → register session → 业务 frame 才放行；会话未注册前的业务帧直接丢弃"]},
    {"dot":"rose","title":"Ordinal Routing","items":["每个业务命令 DbNodePutReq / DbNodeGetReq / QueryIdsReq 对应唯一 P2PCommand.ordinal；跨语言 wire-format 生成器保持一致"]},
    {"dot":"amber","title":"maxFrameBytes","items":["Session 协商 maxFrameBytes；后续发送自动分块 UDP v2 Segment + QUIC/TCP stream chunking"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-core\03-sequence.json") -Encoding UTF8

@"
{
  "schema_version": 1, "diagram_type": "dataflow",
  "meta": {
    "title": "p2p-core Data Flow: RPC → Frame Codec → 4 Transports → Router",
    "viewBox":[1080,760],"animation":"trace","quality_profile":"showcase","locale":"zh-CN"
  },
  "stages":[{"label":"SDK Callers"},{"label":"RPC + Session"},{"label":"Frame + Crypto"},{"label":"Transports"},{"label":"Server Router"}],
  "nodes": [
    {"id":"java","type":"frontend","label":"Java SDK","stage":0,"row":0,"tag":"p2p-core"},
    {"id":"dart","type":"frontend","label":"Dart SDK","stage":0,"row":2,"tag":"Flutter"},
    {"id":"py","type":"frontend","label":"Python SDK","stage":0,"row":4,"tag":"CLI"},
    {"id":"rpc","type":"backend","label":"RPC Layer","sublabel":"unary/stream/bidi","stage":1,"row":2,"tag":"p2p-core"},
    {"id":"session","type":"backend","label":"Session + Cancel","sublabel":"StdCancel","stage":1,"row":4,"tag":"connection"},
    {"id":"codec","type":"messagebus","label":"Frame Codec","sublabel":"maxFrameBytes","stage":2,"row":1,"tag":"wire format"},
    {"id":"crypt","type":"security","label":"Crypto","sublabel":"4 modes","stage":2,"row":3,"tag":"encrypt"},
    {"id":"udp","type":"cloud","label":"UDP v2","stage":3,"row":0,"tag":"MTU"},
    {"id":"quic","type":"cloud","label":"QUIC","stage":3,"row":2,"tag":"QUIC"},
    {"id":"tcp","type":"cloud","label":"TCP","stage":3,"row":4,"tag":"TCP"},
    {"id":"ws","type":"cloud","label":"WebSocket","stage":3,"row":6,"tag":"WS"},
    {"id":"router","type":"backend","label":"P2PCommand Router","stage":4,"row":2,"tag":"ordinal"},
    {"id":"handler","type":"backend","label":"Handler Registry","stage":4,"row":4,"tag":"biz"}
  ],
  "flows": [
    {"f1","from":"java","to":"rpc","label":"Java API call","classification":"caller","variant":"default"},
    {"f2","from":"dart","to":"rpc","label":"Dart stream","classification":"caller","variant":"default"},
    {"f3","from":"py","to":"rpc","label":"Python SDK","classification":"caller","variant":"default"},
    {"f4","from":"rpc","to":"session","label":"sessionId + cancel","classification":"session","variant":"emphasis"},
    {"f5","from":"rpc","to":"codec","label":"frame body","classification":"bytes","variant":"emphasis"},
    {"f6","from":"session","to":"crypt","label":"key/nonce","classification":"secret","variant":"security"},
    {"f7","from":"codec","to":"crypt","label":"plaintext","classification":"frame","variant":"default"},
    {"f8","from":"crypt","to":"udp","label":"segmented UDP","classification":"wire","variant":"dashed"},
    {"f9","from":"crypt","to":"quic","label":"QUIC frame","classification":"wire","variant":"dashed"},
    {"f10","from":"crypt","to":"tcp","label":"TCP bytes","classification":"wire","variant":"dashed"},
    {"f11","from":"crypt","to":"ws","label":"WS binary","classification":"wire","variant":"dashed"},
    {"f12","from":"udp","to":"router","label":"decrypted","classification":"frame","variant":"default"},
    {"f13","from":"quic","to":"router","label":"decrypted","classification":"frame","variant":"default"},
    {"f14","from":"tcp","to":"router","label":"decrypted","classification":"frame","variant":"default"},
    {"f15","from":"ws","to":"router","label":"decrypted","classification":"frame","variant":"default"},
    {"f16","from":"router","to":"handler","label":"dispatch ordinal","classification":"handler","variant":"emphasis"}
  ],
  "cards": [
    {"dot":"cyan","title":"Single Wire Format","items":["4 种 SDK / 4 种传输统一 Frame Codec；maxFrameBytes、P2PCommand 路由一致，跨 SDK 透明"]},
    {"dot":"rose","title":"StdCancel","items":["取消路径和业务路径分离：StdCancel 只影响本 session RPC；其他连接会话不受影响；Python/C/TS/Dart SDK 都实现同构 stop 帧语义"]},
    {"dot":"emerald","title":"Transport Agnostic","items":["应用层看不到传输差异：UDP/QUIC/TCP/WS 进入 Server Router 前已完整还原同一个解密后的业务帧"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-core\04-dataflow.json") -Encoding UTF8

@"
{
  "schema_version": 1, "diagram_type": "lifecycle",
  "meta": {
    "title": "p2p-core Connection Lifecycle + StdCancel",
    "viewBox":[980,680],"animation":"trace","visual_preset":"signal-flow","quality_profile":"showcase","locale":"zh-CN"
  },
  "lanes": [
    {"id":"main","label":"Connection Rail"},
    {"id":"crypto","label":"Crypto State"},
    {"id":"rpc","label":"RPC Operations"},
    {"id":"term","label":"Terminal"}
  ],
  "states": [
    {"id":"idle","type":"start","label":"Idle","sublabel":"no conn","lane":"main","col":0,"step":"01"},
    {"id":"dialing","type":"active","label":"Dialing","sublabel":"QUIC/TCP/WS","lane":"main","col":1,"step":"02"},
    {"id":"helloing","type":"decision","label":"Hello / Key","sublabel":"4 modes","lane":"crypto","col":1,"step":"03"},
    {"id":"ready","type":"success","label":"Session Ready","sublabel":"conn id","lane":"main","col":2,"step":"04"},
    {"id":"rpc-run","type":"active","label":"Running RPC","sublabel":"req|stream|bidi","lane":"rpc","col":2,"tag":"running"},
    {"id":"cancelling","type":"waiting","label":"StdCancel","sublabel":"stop frame","lane":"rpc","col":3,"tag":"stop"},
    {"id":"resume","type":"waiting","label":"Resume","sublabel":"reconnect","lane":"main","col":3,"tag":"reconnect"},
    {"id":"close","type":"failure","label":"Closed","sublabel":"conn close","lane":"main","col":4,"step":"05"},
    {"id":"success-end","type":"success","label":"All Commands Done","sublabel":"graceful","lane":"term","col":2,"tag":"terminal"},
    {"id":"handshake-fail","type":"failure","label":"Handshake Fail","sublabel":"sig/cert","lane":"term","col":4,"tag":"terminal"}
  ],
  "transitions": [
    {"from":"dialing","to":"helloing","variant":"security","route":"straight","fromSide":"right","toSide":"left"},
    {"from":"helloing","to":"ready","variant":"emphasis","route":"straight","fromSide":"right","toSide":"left"},
    {"from":"ready","to":"rpc-run","variant":"default","route":"straight","fromSide":"bottom","toSide":"top"},
    {"from":"rpc-run","to":"cancelling","variant":"security","route":"straight","fromSide":"right","toSide":"left"},
    {"from":"cancelling","to":"ready","variant":"dashed","route":"straight","fromSide":"bottom","toSide":"bottom"},
    {"from":"ready","to":"resume","variant":"dashed","route":"drop"},
    {"from":"helloing","to":"handshake-fail","variant":"security","route":"outside-right"}
  ],
  "cards": [
    {"dot":"cyan","title":"Session Ready Gate","items":["只有 Hello 完成、密钥交换完成的连接才能进入 Ready；否则业务帧进入一律 drop，避免半握手状态下注入"]},
    {"dot":"amber","title":"StdCancel Scope","items":["取消只针对该 RPC 上下文（cancel-token 绑定请求 id）；不影响其他并发 RPC；stop frame 是显式语义，不依赖连接关闭"]},
    {"dot":"emerald","title":"Reconnect Resume","items":["断连后进入 Resume，等待上层决策重连策略；App/SDK 层可自行指数退避"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-core\05-lifecycle.json") -Encoding UTF8


# ---------------- 3. p2p-sync ----------------
@"
{
  "schema_version": 1, "diagram_type": "architecture",
  "meta": {
    "title": "p2p-sync File Sync Architecture",
    "visual_preset": "blueprint","animation":"trace","quality_profile":"showcase","locale":"zh-CN",
    "views":[{"id":"upload","label":"Upload Path","focus":["local-fs","chunk","dedup","transport","remote-fs"],"note":"本地文件 → 分块 → 去重 → 4 种传输 → 远端"},{"id":"monitor","label":"Monitor & Resume","focus":["monitor","up-idx","finalize"],"note":"后台 fs monitor + upload 索引断点续传"}]
  },
  "components": [
    {"id":"local-fs","type":"external","label":"Local FS","sublabel":"NTFS/ext4","pos":[40,140],"size":[128,64]},
    {"id":"monitor","type":"backend","label":"P2PSyncMonitor","sublabel":"inotify / FSWatcher","pos":[40,340],"size":[128,60],"tag":"daemon"},
    {"id":"chunk","type":"backend","label":"Chunker","sublabel":"content-hash chunk","pos":[230,140],"size":[144,64],"tag":"rabin/cdc"},
    {"id":"dedup","type":"database","label":"Dedup Index","sublabel":"hash → chunkId","pos":[430,70],"size":[140,64],"tag":"p2p-db"},
    {"id":"transport","type":"messagebus","label":"p2p-core Transport","sublabel":"UDP/QUIC/TCP/WS","pos":[430,240],"size":[140,64],"tag":"4 modes"},
    {"id":"up-idx","type":"database","label":"Upload Index","sublabel":"namespace + task","pos":[230,340],"size":[144,60],"tag":"resumable"},
    {"id":"remote-fs","type":"external","label":"Remote FS","sublabel":"peer storage","pos":[650,140],"size":[128,64]},
    {"id":"finalize","type":"backend","label":"Finalize + Rename","sublabel":"atomic move","pos":[650,290],"size":[128,60],"tag":"strong check"},
    {"id":"e2e","type":"security","label":"E2E Strong Check","sublabel":"content-hash final","pos":[650,420],"size":[128,60],"tag":"compare"}
  ],
  "boundaries": [
    {"kind":"region","label":"p2p-sync Local","wraps":["local-fs","monitor","chunk","dedup","up-idx","transport"],"pad":14},
    {"kind":"security-group","label":"Remote Peer","wraps":["remote-fs","finalize","e2e"],"pad":14}
  ],
  "connections": [
    {"from":"local-fs","to":"chunk","label":"file bytes","variant":"emphasis"},
    {"from":"monitor","to":"chunk","label":"file change event","variant":"dashed"},
    {"from":"chunk","to":"dedup","label":"hash lookup","route":"orthogonal-v","labelAt":[302,110]},
    {"from":"dedup","to":"transport","label":"unique chunks only","route":"orthogonal-v","labelAt":[500,160]},
    {"from":"chunk","to":"up-idx","label":"chunk progress","variant":"dashed","fromSide":"bottom","toSide":"top"},
    {"from":"transport","to":"remote-fs","label":"chunks in order","variant":"emphasis"},
    {"from":"remote-fs","to":"finalize","label":"chunks assembled","variant":"dashed"},
    {"from":"finalize","to":"e2e","label":"rename final","variant":"security"}
  ],
  "cards": [
    {"dot":"cyan","title":"Content Deduplication","items":["内容哈希分块；去重索引存在则直接跳过该 chunk；跨文件跨任务全局共享唯一 chunk 字节"]},
    {"dot":"emerald","title":"Resume + Namespace","items":["Upload Index 以 namespace+task 分桶，避免跨任务 chunk id 冲突；断点续传根据已上传的 chunk list 跳过已完成部分"]},
    {"dot":"rose","title":"Atomic Finalize","items":["远端 chunk 合入临时文件 → finalize 原子 move → 强 E2E 内容哈希校验；不满足则回滚到临时文件"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-sync\01-architecture.json") -Encoding UTF8

@"
{
  "schema_version": 1, "diagram_type": "workflow",
  "meta": {
    "title": "p2p-sync Resumable Upload Workflow",
    "visual_preset":"signal-flow","animation":"trace","quality_profile":"showcase","locale":"zh-CN"
  },
  "lanes": [
    {"id":"user","label":"Sync Caller"},
    {"id":"prepare","label":"Plan + Dedup"},
    {"id":"transport","label":"Transport Chunks"},
    {"id":"verify","label":"Strong E2E Check"},
    {"id":"resume","label":"Resume On Retry","variant":"exception"}
  ],
  "phases":[{"id":"plan","label":"Plan","fromCol":0,"toCol":1},{"id":"upload","label":"Upload","fromCol":2,"toCol":2,"variant":"emphasis"},{"id":"check","label":"Finalize","fromCol":3,"toCol":4}],
  "mainPath":["start-sync","plan-chunks","dedup-hash","push-chunks","assemble","strong-check","done"],
  "nodes": [
    {"id":"start-sync","lane":"user","col":0,"type":"external","label":"sync(local→remote, ns)","sublabel":"task id"},
    {"id":"plan-chunks","lane":"prepare","col":1,"type":"backend","label":"Chunk File","sublabel":"CDC"},
    {"id":"dedup-hash","lane":"prepare","col":1,"type":"database","label":"Dedup Hash","tag":"p2p-db"},
    {"id":"push-chunks","lane":"transport","col":2,"type":"messagebus","label":"Push Chunk Stream","sublabel":"p2p-core"},
    {"id":"assemble","lane":"verify","col":3,"type":"backend","label":"Assemble Chunks","sublabel":"tmp target"},
    {"id":"strong-check","lane":"verify","col":3,"type":"security","label":"Strong Content Check","sublabel":"hash compare"},
    {"id":"done","lane":"user","col":4,"type":"success","label":"Finalize Atomic","sublabel":"rename OK"},
    {"id":"up-idx-save","lane":"resume","col":2,"type":"database","label":"Save Up-Idx","tag":"namespace+task"},
    {"id":"retry-skip","lane":"resume","col":4,"type":"backend","label":"Skip Already Done","tag":"resume from idx"}
  ],
  "edges": [
    {"from":"start-sync","to":"plan-chunks","variant":"emphasis","route":"drop"},
    {"from":"plan-chunks","to":"dedup-hash","variant":"default","route":"bottom-channel"},
    {"from":"dedup-hash","to":"push-chunks","variant":"emphasis","route":"drop"},
    {"from":"push-chunks","to":"assemble","variant":"default","route":"drop"},
    {"from":"assemble","to":"strong-check","variant":"security","route":"bottom-channel"},
    {"from":"strong-check","to":"done","variant":"emphasis","route":"drop"},
    {"from":"push-chunks","to":"up-idx-save","variant":"dashed","route":"outside-right"},
    {"from":"up-idx-save","to":"retry-skip","variant":"dashed","route":"drop"}
  ],
  "cards": [
    {"dot":"cyan","title":"Dedup","items":["每 chunk 哈希查 p2p-db Dedup Index → 已存在跨任务不再推；极大节省广域网带宽 + 重传"]},
    {"dot":"rose","title":"Strong Final Check","items":["chunk 全部到位后必须做最终全文件内容哈希，与本地 origin 逐字节一致；不通过则不进行最终 rename，避免静默 corrupt"]},
    {"dot":"emerald","title":"Resume Semantics","items":["重传启动时 read Upload Index(namespace, task) → 已完成 chunk 直接跳过；Upload Index 分桶避免任务串扰"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-sync\02-workflow.json") -Encoding UTF8

@"
{
  "schema_version": 1, "diagram_type": "sequence",
  "meta": {
    "title": "p2p-sync Chunked Resumable Upload (p2p-core QUIC)",
    "viewBox":[900,760],"animation":"trace","quality_profile":"showcase","locale":"zh-CN"
  },
  "participants": [
    {"id":"cli","type":"external","label":"Sync Client"},
    {"id":"up-idx","type":"database","label":"Upload Index","sublabel":"ns/task"},
    {"id":"chunker","type":"backend","label":"Chunker","sublabel":"CDC + hash"},
    {"id":"core","type":"backend","label":"p2p-core QUIC","sublabel":"auth_sig"},
    {"id":"srv","type":"backend","label":"Sync Server","sublabel":"assembler"},
    {"id":"tmp","type":"cloud","label":"Tmp Target File","sublabel":"remote"},
    {"id":"final","type":"cloud","label":"Final File","sublabel":"atomic rename"}
  ],
  "segments":[{"from":150,"to":310,"label":"Resume From Idx"},{"from":330,"to":540,"label":"Chunk Stream"},{"from":560,"to":700,"label":"Finalize + E2E"}],
  "messages": [
    {"id":"s1","from":"cli","to":"up-idx","y":185,"label":"get(ns,task) → done chunk list","variant":"default"},
    {"id":"s2","from":"up-idx","to":"cli","y":225,"label":"done: [0,1,4]","variant":"return"},
    {"id":"s3","from":"cli","to":"chunker","y":278,"label":"chunk file, skip 0,1,4","variant":"default"},
    {"id":"s4","from":"chunker","to":"cli","y":320,"label":"chunks: [2,3,5..]","variant":"return"},
    {"id":"s5","from":"cli","to":"core","y":372,"label":"chunk[i] stream (chunked RPC)","variant":"emphasis"},
    {"id":"s6","from":"core","to":"srv","y":415,"label":"encrypted chunk frames","variant":"default"},
    {"id":"s7","from":"srv","to":"tmp","y":465,"label":"write chunk bytes","variant":"dashed"},
    {"id":"s8","from":"tmp","to":"srv","y":505,"label":"write ack","variant":"return"},
    {"id":"s9","from":"srv","to":"cli","y":555,"label":"per chunk ack","variant":"return"},
    {"id":"s10","from":"cli","to":"up-idx","y":600,"label":"mark chunk[i] done","variant":"dashed"},
    {"id":"s11","from":"cli","to":"srv","y":645,"label":"finalize(hash)","variant":"security"},
    {"id":"s12","from":"srv","to":"final","y":680,"label":"atomic rename(tmp → final)","variant":"emphasis"}
  ],
  "activations": [
    {"participant":"cli","from":183,"to":690,"type":"external"},
    {"participant":"chunker","from":274,"to":324,"type":"backend"},
    {"participant":"core","from":370,"to":558,"type":"backend"},
    {"participant":"srv","from":412,"to":684,"type":"backend"}
  ],
  "cards": [
    {"dot":"emerald","title":"Resume Hot Path","items":["打开 Upload Index 拿到 done chunk，chunker 直接跳过；不用重算已上传字节"]},
    {"dot":"rose","title":"Atomic Rename","items":["server 端所有 chunk 先写 tmp；finalize 请求触发 hash compare → 原子 rename 才对外部可见；失败保留 tmp 给下次 resume"]},
    {"dot":"amber","title":"Namespace + Task","items":["Up-Idx 必须 composite key: (namespace, task, chunkId)；避免不同同步任务的同名 chunk 串扰"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-sync\03-sequence.json") -Encoding UTF8

@"
{
  "schema_version": 1, "diagram_type": "dataflow",
  "meta": {
    "title": "p2p-sync Data Flow: File → Chunks → Dedup → Transport → Assembly",
    "viewBox":[1080,760],"animation":"trace","quality_profile":"showcase","locale":"zh-CN"
  },
  "stages":[{"label":"Watched FS"},{"label":"Prepare"},{"label":"Dedup + Idx"},{"label":"Transport"},{"label":"Remote Assembly"}],
  "nodes": [
    {"id":"watch","type":"external","label":"Watch Events","sublabel":"monitor","stage":0,"row":1,"tag":"P2PSyncMonitor"},
    {"id":"read","type":"frontend","label":"Read Local File","sublabel":"bytes","stage":0,"row":3,"tag":"local fs"},
    {"id":"chunk","type":"backend","label":"CDC Chunker","sublabel":"hash","stage":1,"row":2,"tag":"rabin"},
    {"id":"dedup","type":"database","label":"Global Dedup","sublabel":"p2p-db","stage":2,"row":0,"tag":"hash → chunk"},
    {"id":"up-idx","type":"database","label":"Upload Index","sublabel":"p2p-db","stage":2,"row":4,"tag":"ns/task/done"},
    {"id":"net","type":"messagebus","label":"p2p-core","sublabel":"QUIC/TCP/UDP/WS","stage":3,"row":2,"tag":"auth_sig"},
    {"id":"remote-tmp","type":"cloud","label":"Remote Tmp","sublabel":"chunks","stage":4,"row":0,"tag":"tmp file"},
    {"id":"remote-final","type":"cloud","label":"Remote Final","sublabel":"atomic rename","stage":4,"row":2,"tag":"visible"},
    {"id":"e2e-hash","type":"security","label":"Strong E2E Hash","sublabel":"compare","stage":4,"row":4,"tag":"content hash"}
  ],
  "flows": [
    {"id":"s1","from":"watch","to":"chunk","label":"file changed","classification":"event","variant":"dashed"},
    {"id":"s2","from":"read","to":"chunk","label":"file bytes","classification":"bytes","variant":"emphasis"},
    {"id":"s3","from":"chunk","to":"dedup","label":"chunk hash query","classification":"dedup","variant":"default"},
    {"id":"s4","from":"dedup","to":"chunk","label":"unique only","classification":"boolean","variant":"default"},
    {"id":"s5","from":"chunk","to":"up-idx","label":"per chunk progress","classification":"progress","variant":"dashed"},
    {"id":"s6","from":"up-idx","to":"chunk","label":"skip already done","classification":"resume","variant":"dashed"},
    {"id":"s7","from":"chunk","to":"net","label":"unique chunk stream","classification":"wire","variant":"emphasis"},
    {"id":"s8","from":"net","to":"remote-tmp","label":"chunk bytes","classification":"wire","variant":"default"},
    {"id":"s9","from":"remote-tmp","to":"remote-final","label":"atomic rename","classification":"finalize","variant":"emphasis"},
    {"id":"s10","from":"remote-final","to":"e2e-hash","label":"final hash","classification":"strong check","variant":"security"}
  ],
  "cards": [
    {"dot":"cyan","title":"Monitor Trigger","items":["P2PSyncMonitor 捕获修改/rename/delete；然后走同一 Chunker→ Dedup → Transport 流水线，不用手工触发"]},
    {"dot":"emerald","title":"Two p2p-db Roles","items":["Dedup Index=内容哈希全局唯一；Upload Index=ns/task 下完成列表；两个表共同提供 Dedup + Resume 两个核心能力"]},
    {"dot":"rose","title":"E2E Strong","items":["Strong Content Check 是最终一致性的唯一保证；只要 hash 不等就不 rename，任何 chunk 丢/错都会在 finalize 暴露"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-sync\04-dataflow.json") -Encoding UTF8

@"
{
  "schema_version": 1, "diagram_type": "lifecycle",
  "meta": {
    "title": "p2p-sync Upload Task Lifecycle (Resumable + Strong)",
    "viewBox":[980,680],"animation":"trace","visual_preset":"signal-flow","quality_profile":"showcase","locale":"zh-CN"
  },
  "lanes":[{"id":"main","label":"Upload Rail"},{"id":"idx","label":"Up-Idx Writes"},{"id":"check","label":"Strong Checks"},{"id":"term","label":"Terminal"}],
  "states": [
    {"id":"created","type":"start","label":"Task Created","sublabel":"ns + path","lane":"main","col":0,"step":"01"},
    {"id":"planned","type":"waiting","label":"Planned","sublabel":"chunk list","lane":"main","col":1,"step":"02"},
    {"id":"pushing","type":"active","label":"Pushing Chunks","sublabel":"stream","lane":"main","col":2,"step":"03"},
    {"id":"paused","type":"waiting","label":"Paused","sublabel":"net drop","lane":"main","col":3,"step":"04"},
    {"id":"assembled","type":"success","label":"Assembled","sublabel":"remote tmp","lane":"main","col":4,"step":"05"},
    {"id":"persist","type":"active","label":"Persist Progress","sublabel":"per chunk","lane":"idx","col":2,"tag":"write"},
    {"id":"resume","type":"waiting","label":"Resume Skip","sublabel":"read done list","lane":"idx","col":3,"tag":"read"},
    {"id":"hash-check","type":"decision","label":"E2E Hash Match?","lane":"check","col":4,"tag":"gate"},
    {"id":"ok","type":"success","label":"Finalized OK","sublabel":"visible","lane":"term","col":2,"tag":"terminal"},
    {"id":"fail","type":"failure","label":"Hash Mismatch","sublabel":"retry resume","lane":"term","col":4,"tag":"retryable"}
  ],
  "transitions": [
    {"from":"pushing","to":"persist","variant":"dashed","route":"straight","fromSide":"bottom","toSide":"top"},
    {"from":"paused","to":"resume","variant":"dashed","route":"straight","fromSide":"bottom","toSide":"top"},
    {"from":"assembled","to":"hash-check","variant":"security","route":"straight","fromSide":"bottom","toSide":"top"},
    {"from":"resume","to":"pushing","variant":"emphasis","route":"drop"},
    {"from":"hash-check","to":"ok","variant":"emphasis","route":"drop"},
    {"from":"hash-check","to":"fail","variant":"security","route":"outside-right"}
  ],
  "cards": [
    {"dot":"cyan","title":"Chunk Persist Progress","items":["每收到 per-chunk ack 就写 Up-Idx；这样崩溃/网络断开后恢复只需要读 done 列表即可跳过已发 chunk"]},
    {"dot":"amber","title":"Paused→Resume","items":["Paused 不撤销任何 progress；下次运行 Resume Skip 直接从 idx 读；不用重推已完成 chunk，是广域网 sync 体验的关键"]},
    {"dot":"rose","title":"Hash Gate","items":["Finalized 前必须哈希 gate；match → Finalized OK 原子 rename；否则 Hash Mismatch，可再尝试 resume 重推只错的 chunk"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-sync\05-lifecycle.json") -Encoding UTF8


# ---------------- 4. p2p-ws ----------------
@"
{
  "schema_version": 1, "diagram_type": "architecture",
  "meta": {
    "title": "p2p-ws WebSocket SDK Architecture",
    "visual_preset":"blueprint","animation":"trace","quality_profile":"showcase","locale":"zh-CN",
    "views":[{"id":"sdk","label":"SDK Languages","focus":["sdk-java","sdk-dart","sdk-py","sdk-ts","sdk-c"],"note":"5 语言同构 SDK"},{"id":"rpc-ops","label":"RPC Operations","focus":["unary","stream","bidi","cancel"],"note":"4 类 RPC 与 p2p-core 对齐"}]
  },
  "components": [
    {"id":"sdk-java","type":"frontend","label":"Java SDK","sublabel":"Android/JVM","pos":[30,50],"size":[130,58],"tag":"p2p-ws"},
    {"id":"sdk-dart","type":"frontend","label":"Dart SDK","sublabel":"Flutter","pos":[30,130],"size":[130,58],"tag":"p2p-ws"},
    {"id":"sdk-py","type":"frontend","label":"Python SDK","sublabel":"CLI","pos":[30,210],"size":[130,58],"tag":"p2p-ws"},
    {"id":"sdk-ts","type":"frontend","label":"TypeScript SDK","sublabel":"Browser/Node","pos":[30,290],"size":[130,58],"tag":"p2p-ws"},
    {"id":"sdk-c","type":"frontend","label":"C SDK","sublabel":"embedded","pos":[30,370],"size":[130,58],"tag":"p2p-ws"},
    {"id":"ws-client","type":"backend","label":"WS Client","sublabel":"ws:// or wss://","pos":[230,220],"size":[140,68],"tag":"ws"},
    {"id":"frame-route","type":"messagebus","label":"WS Frame Router","sublabel":"p2p-core wire","pos":[430,220],"size":[150,68],"tag":"p2p-core"},
    {"id":"unary","type":"backend","label":"Unary RPC","sublabel":"req/resp","pos":[650,60],"size":[136,58],"tag":"sdk"},
    {"id":"stream","type":"backend","label":"Server Stream","sublabel":"chunked","pos":[650,140],"size":[136,58],"tag":"sdk"},
    {"id":"bidi","type":"backend","label":"Bidi Stream","sublabel":"duplex","pos":[650,220],"size":[136,58],"tag":"sdk"},
    {"id":"cancel","type":"security","label":"StdCancel","sublabel":"stop frame","pos":[650,300],"size":[136,58],"tag":"sdk"},
    {"id":"rpc-event","type":"backend","label":"RPC Events","sublabel":"frame/error/end","pos":[650,380],"size":[136,58],"tag":"sdk"},
    {"id":"ws-srv","type":"backend","label":"p2p-ws Server","sublabel":"handler","pos":[890,220],"size":[150,68],"tag":"server"}
  ],
  "boundaries": [
    {"kind":"region","label":"Language SDKs","wraps":["sdk-java","sdk-dart","sdk-py","sdk-ts","sdk-c"],"pad":16},
    {"kind":"security-group","label":"WS Layer","wraps":["ws-client","frame-route"],"pad":14}
  ],
  "connections": [
    {"from":"sdk-java","to":"ws-client","label":"Java API","variant":"default"},
    {"from":"sdk-dart","to":"ws-client","label":"Dart API","variant":"default"},
    {"from":"sdk-py","to":"ws-client","label":"Python API","variant":"default"},
    {"from":"sdk-ts","to":"ws-client","label":"TS API","variant":"default"},
    {"from":"sdk-c","to":"ws-client","label":"C API","variant":"default"},
    {"from":"ws-client","to":"frame-route","label":"WS binary frame","variant":"emphasis"},
    {"from":"frame-route","to":"unary","label":"unary response","route":"orthogonal-v","labelAt":[505,120]},
    {"from":"frame-route","to":"stream","label":"chunk stream","route":"orthogonal-v","labelAt":[505,180]},
    {"from":"frame-route","to":"bidi","label":"bidi frames","route":"orthogonal-v","labelAt":[505,250]},
    {"from":"frame-route","to":"cancel","label":"stop frame","variant":"security","route":"orthogonal-v","labelAt":[505,320]},
    {"from":"frame-route","to":"rpc-event","label":"event emit","variant":"dashed"},
    {"from":"frame-route","to":"ws-srv","label":"frame dispatch","variant":"emphasis"}
  ],
  "cards": [
    {"dot":"cyan","title":"SDK Isomorphism","items":["Java/Dart/Python/TS/C 5 语言共享 P2PCommand 路由 + 同一 RPC 抽象；取消/事件/错误语义对齐"]},
    {"dot":"rose","title":"StdCancel Uniform","items":["5 语言 SDK 都实现 StdCancel stop frame 语义；不依赖连接关闭；跨语言行为一致"]},
    {"dot":"emerald","title":"p2p-core Wire","items":["WS Client 将 SDK 发出的 RPC 直接编码为 p2p-core 兼容 frame；服务端可直接用 p2p-core Handler Registry + frame router"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-ws\01-architecture.json") -Encoding UTF8

@"
{
  "schema_version": 1, "diagram_type": "workflow",
  "meta": {
    "title": "p2p-ws SDK Connect → Auth → Subscribe → Publish Workflow",
    "visual_preset":"signal-flow","animation":"trace","quality_profile":"showcase","locale":"zh-CN"
  },
  "lanes":[{"id":"app","label":"User App"},{"id":"sdk","label":"WS SDK"},{"id":"wire","label":"WS Wire"},{"id":"srv","label":"WS Server"},{"id":"err","label":"Fail Path","variant":"exception"}],
  "phases":[{"id":"conn","label":"Conn + Auth","fromCol":0,"toCol":2},{"id":"ops","label":"Pub/Sub","fromCol":3,"toCol":4,"variant":"emphasis"}],
  "mainPath":["connect","handshake","auth","sub","pub","msg-push","cancel"],
  "nodes": [
    {"id":"connect","lane":"app","col":0,"type":"external","label":"connect(url, token)","sublabel":"ws or wss"},
    {"id":"handshake","lane":"sdk","col":1,"type":"backend","label":"WS Handshake","sublabel":"Upgrade"},
    {"id":"auth","lane":"srv","col":2,"type":"security","label":"Token Auth","tag":"RBAC"},
    {"id":"sub","lane":"sdk","col":3,"type":"messagebus","label":"Subscribe(topic)","sublabel":"topic filter"},
    {"id":"pub","lane":"sdk","col":3,"type":"messagebus","label":"Publish(topic, msg)"},
    {"id":"msg-push","lane":"sdk","col":4,"type":"backend","label":"OnMessage","sublabel":"push"},
    {"id":"cancel","lane":"err","col":4,"type":"security","label":"StdCancel","sublabel":"stop topic"},
    {"id":"bad-auth","lane":"err","col":2,"type":"failure","label":"401 Unauthorized","tag":"drop conn"},
    {"id":"bad-sub","lane":"err","col":4,"type":"failure","label":"Forbidden Topic","tag":"RBAC"}
  ],
  "edges": [
    {"from":"connect","to":"handshake","variant":"emphasis","route":"drop"},
    {"from":"handshake","to":"auth","variant":"security","route":"drop"},
    {"from":"auth","to":"sub","variant":"emphasis","route":"drop"},
    {"from":"sub","to":"pub","variant":"default","route":"bottom-channel"},
    {"from":"pub","to":"msg-push","variant":"emphasis","route":"drop"},
    {"from":"msg-push","to":"cancel","variant":"security","route":"outside-right"},
    {"from":"auth","to":"bad-auth","variant":"security","route":"outside-right"},
    {"from":"sub","to":"bad-sub","variant":"security","route":"outside-right"}
  ],
  "cards": [
    {"dot":"cyan","title":"Upgrade Gate","items":["先 HTTP Upgrade 到 WS → 然后 Auth token → 才放行 subscribe 命令；否则 Upgrade 过但 Auth 失败直接 401 close"]},
    {"dot":"rose","title":"RBAC on Topic","items":["Subscribe 执行前服务端做 allowCommands/rbac 检查；无权限直接 Forbidden；Publish 同样"]},
    {"dot":"emerald","title":"StdCancel","items":["取消订阅是 SDK 发 StdCancel stop 帧（绑定 sub id）；服务端取消匹配订阅，不需要关 WS 连接本身"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-ws\02-workflow.json") -Encoding UTF8

@"
{
  "schema_version": 1, "diagram_type": "sequence",
  "meta": {
    "title": "p2p-ws SDK Connect + Auth + Subscribe + Publish",
    "viewBox":[900,760],"animation":"trace","quality_profile":"showcase","locale":"zh-CN"
  },
  "participants": [
    {"id":"app","type":"external","label":"App / Browser"},
    {"id":"sdk","type":"backend","label":"p2p-ws SDK","sublabel":"Java/Dart/TS"},
    {"id":"ws","type":"backend","label":"WS Transport","sublabel":"wss://"},
    {"id":"srv","type":"backend","label":"p2p-ws Server"},
    {"id":"rbac","type":"security","label":"RBAC","sublabel":"allowCommands"},
    {"id":"router","type":"messagebus","label":"Handler Router","sublabel":"P2PCommand"},
    {"id":"biz","type":"backend","label":"Biz Handler","sublabel":"pub/sub"}
  ],
  "segments":[{"from":150,"to":280,"label":"WS Upgrade + Auth"},{"from":300,"to":500,"label":"Subscribe"},{"from":520,"to":700,"label":"Publish Push"}],
  "messages": [
    {"id":"w1","from":"app","to":"sdk","y":185,"label":"connect(wss://, token)","variant":"emphasis"},
    {"id":"w2","from":"sdk","to":"ws","y":225,"label":"HTTP Upgrade","variant":"default"},
    {"id":"w3","from":"ws","to":"srv","y":265,"label":"WS connected","variant":"return"},
    {"id":"w4","from":"srv","to":"rbac","y":318,"label":"Auth(token)","variant":"security"},
    {"id":"w5","from":"rbac","to":"srv","y":360,"label":"sessionId + roles","variant":"return"},
    {"id":"w6","from":"sdk","to":"ws","y":415,"label":"Subscribe frame(topic=T)","variant":"default"},
    {"id":"w7","from":"ws","to":"router","y":458,"label":"decoded frame","variant":"default"},
    {"id":"w8","from":"router","to":"biz","y":508,"label":"dispatch Subscribe","variant":"default"},
    {"id":"w9","from":"biz","to":"sdk","y":555,"label":"SubscribedOK(subId=7)","variant":"return"},
    {"id":"w10","from":"sdk","to":"ws","y":605,"label":"Publish(T, msg)","variant":"default"},
    {"id":"w11","from":"biz","to":"sdk","y":655,"label":"OnMessage(subId=7, msg)","variant":"return"}
  ],
  "activations": [
    {"participant":"sdk","from":182,"to":680,"type":"backend"},
    {"participant":"ws","from":224,"to":608,"type":"backend"},
    {"participant":"srv","from":262,"to":562,"type":"backend"},
    {"participant":"router","from":454,"to":564,"type":"messagebus"}
  ],
  "cards": [
    {"dot":"emerald","title":"Sub Id","items":["订阅返回 subId；后续推送/取消都携带 subId；单连接多订阅互不干扰"]},
    {"dot":"rose","title":"RBAC allowCommands","items":["RBAC 按角色的 allowCommands 决定能否 Subscribe/Publish 某 topic；命令级粒度"]},
    {"dot":"cyan","title":"SDK Uniform","items":["跨 5 语言：Connect → Auth → Subscribe → Publish → OnMessage 生命周期完全同构"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-ws\03-sequence.json") -Encoding UTF8

@"
{
  "schema_version": 1, "diagram_type": "dataflow",
  "meta": {
    "title": "p2p-ws Data Flow: 5 SDKs → WS → Handler → Events",
    "viewBox":[1080,760],"animation":"trace","quality_profile":"showcase","locale":"zh-CN"
  },
  "stages":[{"label":"SDKs"},{"label":"WS Codec"},{"label":"Router"},{"label":"Handlers"},{"label":"Events"}],
  "nodes": [
    {"id":"sdkj","type":"frontend","label":"Java SDK","stage":0,"row":0,"tag":"JVM"},
    {"id":"sdkd","type":"frontend","label":"Dart SDK","stage":0,"row":1,"tag":"Flutter"},
    {"id":"sdkp","type":"frontend","label":"Python SDK","stage":0,"row":2,"tag":"CLI"},
    {"id":"sdkt","type":"frontend","label":"TS SDK","stage":0,"row":3,"tag":"Browser"},
    {"id":"sdkc","type":"frontend","label":"C SDK","stage":0,"row":4,"tag":"embed"},
    {"id":"codec","type":"messagebus","label":"WS Frame Codec","stage":1,"row":2,"tag":"binary"},
    {"id":"router","type":"backend","label":"P2PCommand Router","sublabel":"ordinal","stage":2,"row":2,"tag":"p2p-core"},
    {"id":"h-unary","type":"backend","label":"Unary Handler","stage":3,"row":0,"tag":"RPC"},
    {"id":"h-stream","type":"backend","label":"Stream Handler","stage":3,"row":2,"tag":"RPC"},
    {"id":"h-bidi","type":"backend","label":"Bidi Handler","stage":3,"row":4,"tag":"RPC"},
    {"id":"h-cancel","type":"security","label":"StdCancel","stage":3,"row":5,"tag":"RPC"},
    {"id":"evt","type":"messagebus","label":"Event Emitter","stage":4,"row":1,"tag":"onXxx"},
    {"id":"trace","type":"cloud","label":"Tracing","stage":4,"row":3,"tag":"OTEL"}
  ],
  "flows": [
    {"id":"w1","from":"sdkj","to":"codec","label":"binary WS","classification":"frame","variant":"default"},
    {"id":"w2","from":"sdkd","to":"codec","label":"binary WS","classification":"frame","variant":"default"},
    {"id":"w3","from":"sdkp","to":"codec","label":"binary WS","classification":"frame","variant":"default"},
    {"id":"w4","from":"sdkt","to":"codec","label":"binary WS","classification":"frame","variant":"default"},
    {"id":"w5","from":"sdkc","to":"codec","label":"binary WS","classification":"frame","variant":"default"},
    {"id":"w6","from":"codec","to":"router","label":"frame decoded","classification":"dispatch","variant":"emphasis"},
    {"id":"w7","from":"router","to":"h-unary","label":"unary","classification":"rpc","variant":"default"},
    {"id":"w8","from":"router","to":"h-stream","label":"server stream","classification":"rpc","variant":"default"},
    {"id":"w9","from":"router","to":"h-bidi","label":"bidi","classification":"rpc","variant":"default"},
    {"id":"w10","from":"router","to":"h-cancel","label":"stop frame","classification":"cancel","variant":"security"},
    {"id":"w11","from":"h-unary","to":"evt","label":"emit frame/end","classification":"event","variant":"dashed"},
    {"id":"w12","from":"h-stream","to":"evt","label":"emit chunk/end","classification":"event","variant":"dashed"},
    {"id":"w13","from":"h-bidi","to":"evt","label":"emit frame/end","classification":"event","variant":"dashed"},
    {"id":"w14","from":"h-unary","to":"trace","label":"span","classification":"trace","variant":"dashed"}
  ],
  "cards": [
    {"dot":"cyan","title":"Uniform WS Binary","items":["5 语言 SDK 输出同构 WS binary frame；服务端只认一个 frame codec"]},
    {"dot":"rose","title":"One Router","items":["P2PCommand Router（p2p-core 路由表）对 p2p-ws 与 UDP/QUIC/TCP 全部一致，Handler 不用分传输写"]},
    {"dot":"emerald","title":"Event Emitter","items":["所有 Handler 结果统一进入 Event Emitter 向外 onXxx 回调；Tracing 边车不进入 SDK 主热路径"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-ws\04-dataflow.json") -Encoding UTF8

@"
{
  "schema_version": 1, "diagram_type": "lifecycle",
  "meta": {
    "title": "p2p-ws SDK Session Lifecycle (Connect → Authed → Subscribed → Closing)",
    "viewBox":[980,680],"animation":"trace","visual_preset":"signal-flow","quality_profile":"showcase","locale":"zh-CN"
  },
  "lanes":[{"id":"main","label":"Session Rail"},{"id":"auth","label":"Auth"},{"id":"sub","label":"Subscribe Ops"},{"id":"term","label":"Terminal"}],
  "states": [
    {"id":"new","type":"start","label":"New Session","sublabel":"pre connect","lane":"main","col":0,"step":"01"},
    {"id":"upgrade","type":"active","label":"Upgrade WS","sublabel":"HTTP 101","lane":"main","col":1,"step":"02"},
    {"id":"authing","type":"decision","label":"Authing","sublabel":"token/rbac","lane":"auth","col":1,"step":"03"},
    {"id":"authed","type":"success","label":"Authed","sublabel":"session id","lane":"main","col":2,"step":"04"},
    {"id":"subscribing","type":"active","label":"Subscribing","sublabel":"topic=T","lane":"sub","col":2,"tag":"request"},
    {"id":"subscribed","type":"waiting","label":"Subscribed","sublabel":"subId","lane":"sub","col":3,"tag":"active"},
    {"id":"publishing","type":"active","label":"Pushing","sublabel":"messages","lane":"sub","col":3,"tag":"flow"},
    {"id":"closing","type":"failure","label":"Closing","sublabel":"stop or close","lane":"main","col":4,"step":"05"},
    {"id":"ok-fin","type":"success","label":"Graceful End","sublabel":"all subs unsub","lane":"term","col":2,"tag":"terminal"},
    {"id":"kick","type":"failure","label":"Auth Failed","sublabel":"kick conn","lane":"term","col":4,"tag":"terminal"}
  ],
  "transitions": [
    {"from":"upgrade","to":"authing","variant":"security","route":"straight","fromSide":"right","toSide":"left"},
    {"from":"authing","to":"authed","variant":"emphasis","route":"straight","fromSide":"right","toSide":"left"},
    {"from":"authed","to":"subscribing","variant":"default","route":"straight","fromSide":"bottom","toSide":"top"},
    {"from":"subscribing","to":"subscribed","variant":"emphasis","route":"drop"},
    {"from":"subscribed","to":"publishing","variant":"dashed","route":"bottom-channel"},
    {"from":"publishing","to":"closing","variant":"security","route":"drop"},
    {"from":"closing","to":"ok-fin","variant":"emphasis","route":"drop"},
    {"from":"authing","to":"kick","variant":"security","route":"outside-right"}
  ],
  "cards": [
    {"dot":"cyan","title":"Session Gate","items":["Upgrade 完成 + Auth 通过才进入 Authed；否则直接 kick，不接受任何 subscribe/publish 帧"]},
    {"dot":"emerald","title":"Subscribed → Pushing","items":["Subscribed 状态持续可发 Pushing；StdCancel 只退单订阅，其他 subId 继续维持 Subscribed"]},
    {"dot":"rose","title":"Graceful Close","items":["Closing 必须发送 close 帧给所有订阅；session 释放所有 subId，避免泄漏"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-ws\05-lifecycle.json") -Encoding UTF8


# ---------------- 5. p2p-im ----------------
@"
{
  "schema_version": 1, "diagram_type": "architecture",
  "meta": {
    "title": "p2p-im Instant Messaging Architecture",
    "visual_preset":"blueprint","animation":"trace","quality_profile":"showcase","locale":"zh-CN",
    "views":[{"id":"send","label":"Send Path","focus":["client","ingest","msg-store","msg-db"],"note":"IM 消息入库 + 投递"},{"id":"push","label":"Push Path","focus":["presence","push","notify"],"note":"在线推送 + 离线补偿"}]
  },
  "components": [
    {"id":"client","type":"frontend","label":"IM Clients","sublabel":"移动端/Web/PC","pos":[30,130],"size":[136,70],"tag":"p2p-ws SDK"},
    {"id":"ingest","type":"backend","label":"Ingest Gateway","sublabel":"p2p-ws server","pos":[230,130],"size":[148,70],"tag":"p2p-ws"},
    {"id":"router","type":"messagebus","label":"Message Router","sublabel":"convId → shard","pos":[430,130],"size":[150,70],"tag":"p2p-core"},
    {"id":"msg-store","type":"database","label":"Message Store","sublabel":"p2p-db MVCC","pos":[650,70],"size":[140,68],"tag":"durable"},
    {"id":"msg-index","type":"database","label":"Conv/User Index","sublabel":"EqIndex + Range","pos":[650,210],"size":[140,68],"tag":"p2p-db"},
    {"id":"presence","type":"backend","label":"Presence","sublabel":"online user set","pos":[430,290],"size":[150,60],"tag":"p2p-db DsFreeRing"},
    {"id":"push","type":"backend","label":"Push Service","sublabel":"APNs/FCM/WebPush","pos":[230,370],"size":[148,60],"tag":"push"},
    {"id":"notify","type":"external","label":"Notify Receipt","sublabel":"delivered/read","pos":[30,370],"size":[136,60],"tag":"receipt"}
  ],
  "boundaries": [
    {"kind":"region","label":"p2p-im Server","wraps":["ingest","router","msg-store","msg-index","presence"],"pad":16}
  ],
  "connections": [
    {"from":"client","to":"ingest","label":"SendMessage(msg)","variant":"emphasis"},
    {"from":"ingest","to":"router","label":"route convId"},
    {"from":"router","to":"msg-store","label":"append MVCC","route":"orthogonal-v","labelAt":[505,140]},
    {"from":"router","to":"msg-index","label":"index convId/userId","route":"orthogonal-v","labelAt":[505,240]},
    {"from":"router","to":"presence","label":"target online?","variant":"default"},
    {"from":"presence","to":"ingest","label":"online → WS push","variant":"dashed","fromSide":"bottom","toSide":"bottom"},
    {"from":"presence","to":"push","label":"offline → APNs/FCM","variant":"dashed"},
    {"from":"push","to":"notify","label":"delivery receipt","variant":"dashed"}
  ],
  "cards": [
    {"dot":"cyan","title":"p2p-db 三角色","items":["Message Store = MVCC 存 message body；Conv/User Index = 查询收件箱；Presence = DsFreeRing(64-bit uid Set) 作为"在线 uid 集合"语义"]},
    {"dot":"emerald","title":"Two Delivery Path","items":["用户在线 Presence in set → WS 直接推；离线 → Push Service 走系统推送，并在消息索引中标记 unread"]},
    {"dot":"rose","title":"Receipts","items":["delivered/read receipt 是双向显式事件；客户端发送 receipt 给 Ingest → 更新 msg-index 对应消息状态"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-im\01-architecture.json") -Encoding UTF8

@"
{
  "schema_version": 1, "diagram_type": "workflow",
  "meta": {
    "title": "p2p-im Send Message → Persist → Push → Receipts Workflow",
    "visual_preset":"signal-flow","animation":"trace","quality_profile":"showcase","locale":"zh-CN"
  },
  "lanes":[{"id":"send","label":"Sender"},{"id":"durable","label":"Persist + Index"},{"id":"presence","label":"Presence"},{"id":"deliver","label":"Receiver"},{"id":"err","label":"Fail Path","variant":"exception"}],
  "phases":[{"id":"durable","label":"Durable","fromCol":1,"toCol":1,"variant":"emphasis"},{"id":"deliver","label":"Deliver","fromCol":2,"toCol":3}],
  "mainPath":["u-send","msg-persist","idx-write","presence-lkup","ws-push","deliver-receipt","read-receipt"],
  "nodes": [
    {"id":"u-send","lane":"send","col":0,"type":"external","label":"User Send","sublabel":"IM Client"},
    {"id":"msg-persist","lane":"durable","col":1,"type":"database","label":"Msg MVCC","sublabel":"p2p-db"},
    {"id":"idx-write","lane":"durable","col":1,"type":"database","label":"Conv Index","tag":"rowIdSet"},
    {"id":"presence-lkup","lane":"presence","col":2,"type":"backend","label":"Presence Lookup","sublabel":"DsFreeRing contains"},
    {"id":"ws-push","lane":"deliver","col":3,"type":"backend","label":"WS Push","tag":"online"},
    {"id":"sys-push","lane":"deliver","col":3,"type":"backend","label":"Offline Push","tag":"APNs/FCM"},
    {"id":"deliver-receipt","lane":"deliver","col":3,"type":"messagebus","label":"Delivered Event"},
    {"id":"read-receipt","lane":"send","col":0,"type":"success","label":"Read Receipt","sublabel":"双向确认"},
    {"id":"persist-fail","lane":"err","col":1,"type":"failure","label":"Persist Fail","sublabel":"retry send"}
  ],
  "edges": [
    {"from":"u-send","to":"msg-persist","variant":"emphasis","route":"drop"},
    {"from":"msg-persist","to":"idx-write","variant":"default","route":"bottom-channel"},
    {"from":"idx-write","to":"presence-lkup","variant":"emphasis","route":"drop"},
    {"from":"presence-lkup","to":"ws-push","variant":"emphasis","route":"drop"},
    {"from":"presence-lkup","to":"sys-push","variant":"security","route":"outside-right"},
    {"from":"ws-push","to":"deliver-receipt","variant":"dashed","route":"bottom-channel"},
    {"from":"deliver-receipt","to":"read-receipt","variant":"emphasis","route":"drop"},
    {"from":"msg-persist","to":"persist-fail","variant":"security","route":"outside-right"}
  ],
  "cards": [
    {"dot":"cyan","title":"Presence = DsFreeRing Set","items":["Presence Lookup 就是 DsFreeRing.contains(uid)；这是本改 DsFreeRing 新增 Set 语义的典型 inode 集合用法：64-bit uid → contains/offerUnique/remove/snapshot"]},
    {"dot":"emerald","title":"Persist First","items":["先 msg 写 p2p-db MVCC 并索引 conv/user；然后才做 Presence 路由和推送。这样任何断连都可重放未推送"]},
    {"dot":"rose","title":"Receipt Contract","items":["Delivered 是服务端事件；Read 是客户端主动事件；两者都是 msg-id 级细粒度，支持单会话内多消息独立状态"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-im\02-workflow.json") -Encoding UTF8

@"
{
  "schema_version": 1, "diagram_type": "sequence",
  "meta": {
    "title": "p2p-im Send → Persist → Presence → Deliver → Read Receipt",
    "viewBox":[900,760],"animation":"trace","quality_profile":"showcase","locale":"zh-CN"
  },
  "participants": [
    {"id":"ua","type":"external","label":"Alice (Client A)"},
    {"id":"ingest","type":"backend","label":"Ingest","sublabel":"p2p-ws"},
    {"id":"store","type":"database","label":"Msg Store","sublabel":"p2p-db"},
    {"id":"idx","type":"database","label":"Conv Index","sublabel":"p2p-db"},
    {"id":"presence","type":"backend","label":"Presence","sublabel":"DsFreeRing(uid Set)"},
    {"id":"ub","type":"external","label":"Bob (Client B)"},
    {"id":"receipts","type":"messagebus","label":"Receipt Bus"}
  ],
  "segments":[{"from":150,"to":320,"label":"Persist"},{"from":340,"to":500,"label":"Presence Route"},{"from":520,"to":700,"label":"Deliver + Receipts"}],
  "messages": [
    {"id":"m1","from":"ua","to":"ingest","y":190,"label":"SendMessage(to=Bob, body)","variant":"emphasis"},
    {"id":"m2","from":"ingest","to":"store","y":235,"label":"MVCC insert(msgId, body..)","variant":"default"},
    {"id":"m3","from":"store","to":"ingest","y":275,"label":"persisted msgId","variant":"return"},
    {"id":"m4","from":"ingest","to":"idx","y":328,"label":"index(userId=Bob, convId)","variant":"default"},
    {"id":"m5","from":"idx","to":"ingest","y":368,"label":"indexed","variant":"return"},
    {"id":"m6","from":"ingest","to":"presence","y":420,"label":"contains(Bob uid)","variant":"default"},
    {"id":"m7","from":"presence","to":"ingest","y":462,"label":"true (online)","variant":"return"},
    {"id":"m8","from":"ingest","to":"ub","y":510,"label":"WS push(msgId, body)","variant":"emphasis"},
    {"id":"m9","from":"ub","to":"receipts","y":555,"label":"emit delivered(msgId)","variant":"dashed"},
    {"id":"m10","from":"ub","to":"receipts","y":600,"label":"emit read(msgId)","variant":"dashed"},
    {"id":"m11","from":"receipts","to":"ua","y":645,"label":"delivered/read receipt","variant":"return"}
  ],
  "activations": [
    {"participant":"ingest","from":188,"to":560,"type":"backend"},
    {"participant":"store","from":232,"to":278,"type":"database"},
    {"participant":"idx","from":324,"to":370,"type":"database"},
    {"participant":"presence","from":418,"to":466,"type":"backend"}
  ],
  "cards": [
    {"dot":"emerald","title":"Receipt Chain","items":["Bob 收到→ delivered；Bob 阅读→ read；Receipt Bus 推送回 Alice；Alice 本地状态 UI 双勾"]},
    {"dot":"cyan","title":"Presence = DsFreeRing Set","items":["contains(Bob uid) 是 O(n) 两段计数遍历；单 server 在线用户 ≤ 100 万内完全 OK；超了可拆 16 分桶"]},
    {"dot":"rose","title":"Persist-Then-Route","items":["如果 route 但消息没持久成功，等于虚假发送；Persist-Then-Route 保证失败可 client 安全重试，不会产生假 delivered"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-im\03-sequence.json") -Encoding UTF8

@"
{
  "schema_version": 1, "diagram_type": "dataflow",
  "meta": {
    "title": "p2p-im Data Flow: Send → Store → Index → Presence → Push → Receipts",
    "viewBox":[1080,760],"animation":"trace","quality_profile":"showcase","locale":"zh-CN"
  },
  "stages":[{"label":"Clients"},{"label":"Ingest"},{"label":"Durable"},{"label":"Routing"},{"label":"Receipts"}],
  "nodes": [
    {"id":"cli-a","type":"frontend","label":"Alice","stage":0,"row":0,"tag":"mobile"},
    {"id":"cli-b","type":"frontend","label":"Bob","stage":0,"row":3,"tag":"mobile"},
    {"id":"ing","type":"backend","label":"Ingest","sublabel":"p2p-ws","stage":1,"row":1,"tag":"gateway"},
    {"id":"store","type":"database","label":"Msg MVCC","sublabel":"p2p-db","stage":2,"row":0,"tag":"MVCC"},
    {"id":"idx","type":"database","label":"Conv/User Index","sublabel":"p2p-db","stage":2,"row":1,"tag":"EqIndex"},
    {"id":"presence","type":"backend","label":"Presence UID Set","sublabel":"DsFreeRing","stage":2,"row":3,"tag":"Set"},
    {"id":"ws-push","type":"backend","label":"WS Push","stage":3,"row":1,"tag":"online"},
    {"id":"sys-push","type":"cloud","label":"APNs/FCM","stage":3,"row":3,"tag":"offline"},
    {"id":"evt-deliv","type":"messagebus","label":"Delivered","stage":4,"row":0,"tag":"event"},
    {"id":"evt-read","type":"messagebus","label":"Read","stage":4,"row":1,"tag":"event"},
    {"id":"evt-trace","type":"cloud","label":"Tracing","stage":4,"row":3,"tag":"OTEL"}
  ],
  "flows": [
    {"id":"m1","from":"cli-a","to":"ing","label":"SendMessage","classification":"app command","variant":"emphasis"},
    {"id":"m2","from":"ing","to":"store","label":"MVCC write","classification":"bytes","variant":"default"},
    {"id":"m3","from":"ing","to":"idx","label":"userId + convId index","classification":"index","variant":"default"},
    {"id":"m4","from":"ing","to":"presence","label":"contains(uid)","classification":"set","variant":"default"},
    {"id":"m5","from":"presence","to":"ws-push","label":"online","classification":"route","variant":"emphasis"},
    {"id":"m6","from":"presence","to":"sys-push","label":"offline","classification":"route","variant":"dashed"},
    {"id":"m7","from":"ws-push","to":"cli-b","label":"WS frame","classification":"wire","variant":"emphasis"},
    {"id":"m8","from":"sys-push","to":"cli-b","label":"system push","classification":"sys push","variant":"dashed"},
    {"id":"m9","from":"cli-b","to":"evt-deliv","label":"ack delivered","classification":"event","variant":"default"},
    {"id":"m10","from":"cli-b","to":"evt-read","label":"ack read","classification":"event","variant":"default"},
    {"id":"m11","from":"evt-deliv","to":"evt-trace","label":"span","classification":"trace","variant":"dashed"}
  ],
  "cards": [
    {"dot":"cyan","title":"3 p2p-db Components Roles","items":["Msg Store = MVCC：消息 body 多版本历史；Index = EqIndex：收件箱/会话查询；Presence = DsFreeRing（本次新增 Set 语义）：在线 uid 集合"]},
    {"dot":"rose","title":"Split Delivery","items":["Presence DsFreeRing.contains → WS 在线推；否则 APNs/FCM 系统推；两条路径互斥但共同覆盖 100% 用户场景"]},
    {"dot":"emerald","title":"Receipt Events","items":["Delivered/Read 都是独立 event；对 Alice 可见的双勾完全靠 receipts 事件，不靠服务端轮询"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-im\04-dataflow.json") -Encoding UTF8

@"
{
  "schema_version": 1, "diagram_type": "lifecycle",
  "meta": {
    "title": "p2p-im Message Lifecycle + Delivered/Read Receipts",
    "viewBox":[980,680],"animation":"trace","visual_preset":"signal-flow","quality_profile":"showcase","locale":"zh-CN"
  },
  "lanes":[{"id":"main","label":"Message Rail"},{"id":"presence","label":"Presence"},{"id":"receipts","label":"Receipts"},{"id":"term","label":"Terminal"}],
  "states": [
    {"id":"created","type":"start","label":"Client Created","sublabel":"pending send","lane":"main","col":0,"step":"01"},
    {"id":"sent","type":"active","label":"Sent","sublabel":"sent to ingest","lane":"main","col":1,"step":"02"},
    {"id":"persisted","type":"success","label":"Persisted","sublabel":"p2p-db MVCC","lane":"main","col":2,"step":"03"},
    {"id":"routed","type":"active","label":"Routed","sublabel":"convId shard","lane":"main","col":3,"step":"04"},
    {"id":"delivered","type":"waiting","label":"Delivered","sublabel":"client ack","lane":"main","col":4,"step":"05"},
    {"id":"online-ok","type":"waiting","label":"Online Match","sublabel":"Ws Push","lane":"presence","col":3,"tag":"dsfreering contains"},
    {"id":"offline-fallback","type":"waiting","label":"Offline","sublabel":"Sys Push","lane":"presence","col":3,"tag":"APNs/FCM"},
    {"id":"delivered-evt","type":"active","label":"Delivered Event","sublabel":"receipts","lane":"receipts","col":4,"tag":"1 tick"},
    {"id":"read-evt","type":"active","label":"Read Event","sublabel":"receipts","lane":"receipts","col":4,"tag":"2 tick"},
    {"id":"done-double","type":"success","label":"Read Final","sublabel":"double check","lane":"term","col":3,"tag":"terminal"},
    {"id":"persist-fail","type":"failure","label":"PersistFail","sublabel":"retry","lane":"term","col":1,"tag":"retryable"}
  ],
  "transitions": [
    {"from":"routed","to":"online-ok","variant":"emphasis","route":"straight","fromSide":"bottom","toSide":"top"},
    {"from":"routed","to":"offline-fallback","variant":"dashed","route":"drop"},
    {"from":"online-ok","to":"delivered","variant":"emphasis","route":"drop"},
    {"from":"delivered","to":"delivered-evt","variant":"default","route":"straight","fromSide":"bottom","toSide":"top"},
    {"from":"delivered-evt","to":"read-evt","variant":"dashed","route":"bottom-channel"},
    {"from":"read-evt","to":"done-double","variant":"emphasis","route":"drop"},
    {"from":"persisted","to":"persist-fail","variant":"security","route":"outside-right"}
  ],
  "cards": [
    {"dot":"cyan","title":"Persisted → Delivered","items":["消息必须先 Persisted 成功，才能进入 delivery；是"已持久化"和"已送达"两阶段 UI 状态（单勾 vs 双勾第一勾）"]},
    {"dot":"amber","title":"Presence Branch","items":["Routed 阶段读取 DsFreeRing uid 集合；命中 → Online Match→Ws Push；不命中 → Offline Fallback→系统推送，两种路径最终都会到 Delivered 状态"]},
    {"dot":"emerald","title":"Read Receipt is Terminal","items":["Done Double Check = Delivered + Read 双事件都已发出；聊天 UI 才显示真正的双勾已读"]}
  ]
}
"@ | Set-Content (Join-Path $diagramsRoot "p2p-im\05-lifecycle.json") -Encoding UTF8

Write-Host "`nDone. 25 JSON candidates written:" -ForegroundColor Green
Get-ChildItem -Path (Join-Path $diagramsRoot "*\*.json") -Recurse |
    Where-Object { $_.FullName -match "0[1-5]-" } |
    ForEach-Object { Write-Host ("  {0,-50}  {1,7} B" -f "$($_.Directory.Name)\$($_.Name)", $_.Length) }

Write-Host "`nNext step: .\run-all.ps1  (runs validate → deliver → visual-check for all 25)" -ForegroundColor Cyan
