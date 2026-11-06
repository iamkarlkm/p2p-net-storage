---
created: "2026-09-17T03:01:04Z"
distill_scope: session
promotion_categories:
    - boundary_fact
    - invariant
    - verification_recipe
proposed_targets:
    - .brain/context/current-state.md
    - .brain/resources/changes/p7-follow-up-tgt-4-c4-binlog-lz4-frame-compression-for-dbentitybinlogframe-handler.md
    - AGENTS.md
source_session_id: "1789613086614434400"
source_task: P7 Follow-up Tgt-4 C4 Binlog LZ4 frame compression for DbEntityBinlogFrame Handler
title: P7 Follow-up Tgt-4 C4 Binlog LZ4 frame compression for DbEntityBinlogFrame Handler Distill Proposal
type: distill_proposal
updated: "2026-09-17T03:01:04Z"
---
# P7 Follow-up Tgt-4 C4 Binlog LZ4 frame compression for DbEntityBinlogFrame Handler Distill Proposal

## Source Provenance

- Mode: `session`
- Session: `1789613086614434400`
- Task: P7 Follow-up Tgt-4 C4 Binlog LZ4 frame compression for DbEntityBinlogFrame Handler
- Git baseline: `53a382e3982752d2331c288913dc999e6a5912b6`

### Commands Run

- `mvn -pl p2p-db test -Dtest=DsBinlogFrame_P7_Tgt4_LZ4_4_Test -q` (exit 0)
- `mvn -pl p2p-db test -Dtest=DsRegistryTest,DsRegistry_P31_MasterIndexPrefixTest,DsRegistry_P7_A1_GlobalIds_6_Test,DsRegistry_P7_Tgt3_HandlerTest,DsRegistryExtAttrs_P7_Exts_Test,DsRegistryExtAttrs_P7_Tgt5_HandlerTest,DsRegistry_Phase1_DbMetaPackedAttrs_8_Test,Orm_P7_Tgt2_Handler_6_Test,DbEntityBinlogFrame_P7_C4_HandlerTest,DsBinlogFrame_P7_Tgt4_LZ4_4_Test,DsBinlogBasicTest,DsBinlogReplicator_P7_C4_Test,DsStringBlockStoreTest,DsTypeFixedBucketStoreTest,DsTypeStringTest -q` (exit 0)

### Git Diff

- `.brain/context/current-state.md`
- `.brain/context/overview.md`
- `.brain/context/workflows.md`
- `.brain/resources/changes/25-archify-diagrams-p2p-net-storage-validate-deliver-visual-check-receipts-distill-proposal.md`
- `.brain/resources/changes/25-archify-diagrams-p2p-net-storage-validate-deliver-visual-check-receipts.md`
- `.brain/resources/changes/column-level-mvcc-dsfield-dsdatabaselocal-9-tests-12-classes-0f0e.md`
- `.brain/resources/changes/distill-proposal.md`
- `.brain/resources/changes/dsfreering-64bit-inode-set-contains-remove-snapshot-size-offerunique-wrap-tail-cap-distill-proposal.md`
- `.brain/resources/changes/dsfreering-64bit-inode-set-contains-remove-snapshot-size-offerunique-wrap-tail-cap.md`
- `.brain/resources/changes/dsfreering-expand-zero-copy-performance-first-rule.md`
- `.brain/resources/changes/dsfreering-inode-dirset-recipe.md`
- `.brain/resources/changes/dsfreering-set-semantics.md`
- `.brain/resources/changes/dsregistry-category-datatype-registry-directorytree-dsfreering-dshashmap.md`
- `.brain/resources/changes/dsregistry-p2-4directions-prefixfind-rwlock-ormbridge-multisz.md`
- `.brain/resources/changes/dsregistry-p2-p2-1-findbyprefix-listkeys-p2-2readwritelock-p2-3reg-database-orm-p2-4reg-multi-sz-reg-list.md`
- `.brain/resources/changes/dsregistry-p3-p3-1-findbyprefix-masterindex-p3-2-stampedlock-p3-3-ormbridge-distill-proposal.md`
- `.brain/resources/changes/dsregistry-p3-p3-1-findbyprefix-masterindex-p3-2-stampedlock-p3-3-ormbridge.md`
- `.brain/resources/changes/dsregistry-p4-keyidtodirid-hashmap-dir-type-205-typedid-encode-205-6-keyid-leaf-type-202-n-1-hashmap-1-nextsequence-distill-proposal.md`
- `.brain/resources/changes/dsregistry-tag-desc-spec-comment-helper-dsregistry-7-distill-proposal.md`
- `.brain/resources/changes/dsstring-contains.md`
- `.brain/resources/changes/dsstring-getindexid.md`
- `.brain/resources/changes/dstypebucketstore-dstypestring-16-8-40bit-multitypedataid.md`
- `.brain/resources/changes/dstypefixedbucketstore-dstypestring-typeid-rootdir-type-typeid-typeid-data-dat-master-index-free-ring.md`
- `.brain/resources/changes/full-backup-directory-format.md`
- `.brain/resources/changes/incremental-backup-wal-archive-metadata-filter.md`
- `.brain/resources/changes/mvcc-distill-proposal.md`
- `.brain/resources/changes/mvcc.md`
- `.brain/resources/changes/p2p-db-binlog-redo-undo-distill-proposal.md`
- `.brain/resources/changes/p2p-db-binlog-redo-undo.md`
- `.brain/resources/changes/p2p-db-binlog-rotate.md`
- `.brain/resources/changes/p2p-db-binlog-wal-dsmemory-wal-binlog-checkpoint-op-durability-flag-checkpoint-mvcc.md`
- `.brain/resources/changes/p2p-db.md`
- `.brain/resources/changes/p3-snapshot-distill-proposal.md`
- `.brain/resources/changes/p5-dsidlayout-serverid-partitionid-3-toglobal-fromglobal-migratelegacyids-durable-distill-proposal.md`
- `.brain/resources/changes/p5-dsidlayout-serverid-partitionid-3-toglobal-fromglobal-migratelegacyids-durable.md`
- `.brain/resources/changes/p6-dsregistry-fallback-gates-dsidlayout-crossinstance-p4vsp3-benchmark-durable.md`
- `.brain/resources/changes/p6-dsregistry-readnodemeta-fallback-dsidlayout-p4-vs-p3-i-o-benchmark-distill-proposal.md`
- `.brain/resources/changes/p6-dsregistry-readnodemeta-fallback-dsidlayout-p4-vs-p3-i-o-benchmark.md`
- `.brain/resources/changes/p7-dsregistry-distributed-namespace-distill-proposal.md`
- `.brain/resources/changes/p7-dsregistry-distributed-namespace-router-orm-binlog-async-c4-durable.md`
- `.brain/resources/changes/p7-dsregistry-distributed-namespace-router-orm-binlog-async-c4-verification-promotion-target.md`
- `.brain/resources/changes/p7-dsregistry-follow-up-after-p6-closed-fallback-or-g4-or-dsdb-dsregistry-distill-proposal.md`
- `.brain/resources/changes/p7-follow-up-tgt-1-c4-dbentitybinlogframeserverhandler-handler-0-24300-replaybinlog-24301-ack-appliedframes-10-t13-same-userc4-warm-up-10-writes-distill-proposal.md`
- `.brain/resources/changes/p7-follow-up-tgt-2-orm-rowid-dsidlayout-vs-tgt-5-extattrs-handler-vs-tgt-3-promote-distill-proposal.md`
- `.brain/resources/changes/p7-follow-up-tgt-2-vs-tgt-3-tgt-3-dsregistry-handler-6-p2p-server-rootdir-batch-distill-proposal.md`
- `.brain/resources/changes/p7-tgt-2-follow-up-orm-genericmanager-9-dsidlayout-24336-p2pcommand-18-18-model-9-handler-6-tests-distill-proposal.md`
- `.brain/resources/changes/phase1-dsregistry-db-closeout-current-state-block-recipe-section-iii-context-audit-distill-distill-proposal.md`
- `.brain/resources/changes/query-planner-or-narrowing-correlated-exists-cost-based.md`
- `.brain/resources/changes/timeline-restore-wal-entry-offset.md`
- `.brain/resources/decisions/dsfreering-64bit-inode-set-contains-remove-snapshot-size-offerunique-wrap-tail-cap.md`
- `.brain/resources/decisions/p5-dsidlayout-serverid-partitionid-3-toglobal-fromglobal-migratelegacyids-durable.md`
- `.brain/resources/testing-maven-output.md`
- `.trae/`
- `AGENTS.md`
- `docs/diagrams/`
- `docs/dsdb-design-status-and-roadmap.md`
- `docs/plan-dsdb-distributed.md`
- `docs/project-overview.md`
- `docs/project-workflows.md`
- `p2p-core/src/main/java/io/protostuff/StringSerializerWithByteBuf.java`
- `p2p-core/src/main/java/javax/net/p2p/api/P2PCommand.java`
- `p2p-core/src/main/java/javax/net/p2p/model/DbEntityBinlogFrameAck.java`
- `p2p-core/src/main/java/javax/net/p2p/model/DbEntityBinlogFrameRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/DbNodeForceMovePartitionRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/DbNodeForceMovePartitionResponse.java`
- `p2p-core/src/main/java/javax/net/p2p/model/DbNodeQueryIdsRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/DbNodeReplicateAck.java`
- `p2p-core/src/main/java/javax/net/p2p/model/DbNodeReplicateFrame.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtAttrsLastStringAck.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtAttrsListStringAck.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtAttrsWriteAck.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtaAddCommentRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtaAddDescRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtaAddSpecRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtaAddTagRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtaAddTagsRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtaHasTagRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtaLastCommentRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtaLastDescRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtaLastSpecRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtaListCommentsRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtaListDescsRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtaListSpecsRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtaListTagsFilteredRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtaListTagsRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtaRemoveAllCommentsRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtaRemoveAllDescsRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtaRemoveAllSpecsRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtaRemoveAllTagsRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtaRemoveLastCommentRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtaRemoveLastDescRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtaRemoveLastSpecRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtaRemoveTagRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/ExtaRemoveTagsRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/OrmCountAck.java`
- `p2p-core/src/main/java/javax/net/p2p/model/OrmCountRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/OrmExistsAck.java`
- `p2p-core/src/main/java/javax/net/p2p/model/OrmExistsRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/OrmFindRangeAck.java`
- `p2p-core/src/main/java/javax/net/p2p/model/OrmFindRangeRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/OrmGetAck.java`
- `p2p-core/src/main/java/javax/net/p2p/model/OrmGetOneAck.java`
- `p2p-core/src/main/java/javax/net/p2p/model/OrmGetOneRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/OrmGetRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/OrmListIdsAck.java`
- `p2p-core/src/main/java/javax/net/p2p/model/OrmListIdsRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/OrmPutAck.java`
- `p2p-core/src/main/java/javax/net/p2p/model/OrmPutRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/OrmRemoveAck.java`
- `p2p-core/src/main/java/javax/net/p2p/model/OrmRemoveRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/OrmSaveOrUpdateAck.java`
- `p2p-core/src/main/java/javax/net/p2p/model/OrmSaveOrUpdateRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/RegistryFindByPrefixAck.java`
- `p2p-core/src/main/java/javax/net/p2p/model/RegistryFindByPrefixKeysAck.java`
- `p2p-core/src/main/java/javax/net/p2p/model/RegistryFindByPrefixKeysRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/RegistryFindByPrefixRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/RegistryGetAck.java`
- `p2p-core/src/main/java/javax/net/p2p/model/RegistryGetRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/RegistryListChildrenAck.java`
- `p2p-core/src/main/java/javax/net/p2p/model/RegistryListChildrenRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/RegistryMkdirsRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/RegistryPutBytesRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/RegistryPutLongRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/RegistryPutStringRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/RegistryRemoveRequest.java`
- `p2p-core/src/main/java/javax/net/p2p/model/RegistryWriteAck.java`
- `p2p-core/src/main/java/javax/net/p2p/model/SimpRegistryEntry.java`
- `p2p-db/QuickMath.java`
- `p2p-db/dsdb/`
- `p2p-db/pom.xml`
- `p2p-db/src/main/java/com/q3lives/ds/annotation/DsField.java`
- `p2p-db/src/main/java/com/q3lives/ds/annotation/DsFieldGroup.java`
- `p2p-db/src/main/java/com/q3lives/ds/annotation/DsTable.java`
- `p2p-db/src/main/java/com/q3lives/ds/backup/`
- `p2p-db/src/main/java/com/q3lives/ds/binlog/DsBinlogCheckpoint.java`
- `p2p-db/src/main/java/com/q3lives/ds/binlog/DsBinlogEntry.java`
- `p2p-db/src/main/java/com/q3lives/ds/binlog/DsBinlogLayout.java`
- `p2p-db/src/main/java/com/q3lives/ds/binlog/DsBinlogLogLevel.java`
- `p2p-db/src/main/java/com/q3lives/ds/binlog/DsBinlogOpType.java`
- `p2p-db/src/main/java/com/q3lives/ds/binlog/DsBinlogPosition.java`
- `p2p-db/src/main/java/com/q3lives/ds/binlog/DsBinlogReplayer.java`
- `p2p-db/src/main/java/com/q3lives/ds/binlog/DsBinlogReplicator.java`
- `p2p-db/src/main/java/com/q3lives/ds/binlog/DsBinlogStore.java`
- `p2p-db/src/main/java/com/q3lives/ds/binlog/DsBinlogWriter.java`
- `p2p-db/src/main/java/com/q3lives/ds/bucket/DsFixedBucketStore.java`
- `p2p-db/src/main/java/com/q3lives/ds/bucket/DsTypeFixedBucketStore.java`
- `p2p-db/src/main/java/com/q3lives/ds/cluster/`
- `p2p-db/src/main/java/com/q3lives/ds/collections/DsFixedValueStore.java`
- `p2p-db/src/main/java/com/q3lives/ds/collections/DsHashMap.java`
- `p2p-db/src/main/java/com/q3lives/ds/collections/DsHashMapIdAllocator.java`
- `p2p-db/src/main/java/com/q3lives/ds/collections/DsHashMapInline.java`
- `p2p-db/src/main/java/com/q3lives/ds/core/DsFreeRing.java`
- `p2p-db/src/main/java/com/q3lives/ds/core/DsMemory.java`
- `p2p-db/src/main/java/com/q3lives/ds/core/DsString.java`
- `p2p-db/src/main/java/com/q3lives/ds/core/DsTypeString.java`
- `p2p-db/src/main/java/com/q3lives/ds/core/DsWAL.java`
- `p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java`
- `p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseServer.java`
- `p2p-db/src/main/java/com/q3lives/ds/database/DsTableLayout.java`
- `p2p-db/src/main/java/com/q3lives/ds/database/DsValueResolver.java`
- `p2p-db/src/main/java/com/q3lives/ds/database/adapter/DsTableAdapter.java`
- `p2p-db/src/main/java/com/q3lives/ds/database/columnar/ColumnCompression.java`
- `p2p-db/src/main/java/com/q3lives/ds/database/columnar/ColumnarStore.java`
- `p2p-db/src/main/java/com/q3lives/ds/database/columnar/DsColumnGroupStore.java`
- `p2p-db/src/main/java/com/q3lives/ds/database/columnar/DsDatabaseClusterClient.java`
- `p2p-db/src/main/java/com/q3lives/ds/database/columnar/DsDistributedTestRow.java`
- `p2p-db/src/main/java/com/q3lives/ds/database/columnar/DsPartitionManager.java`
- `p2p-db/src/main/java/com/q3lives/ds/database/integration/GenericManager.java`
- `p2p-db/src/main/java/com/q3lives/ds/database/integration/QueryWrapper.java`
- `p2p-db/src/main/java/com/q3lives/ds/database/schema/EntitySchemaUtil.java`
- `p2p-db/src/main/java/com/q3lives/ds/enums/Category.java`
- `p2p-db/src/main/java/com/q3lives/ds/enums/DataType.java`
- `p2p-db/src/main/java/com/q3lives/ds/registry/`
- `p2p-db/src/main/java/com/q3lives/ds/util/MultiTypeDataId.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/DbEntityBinlogFrameServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/DbNodeForceMovePartitionServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/DbNodeGetServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/DbNodePutServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/DbNodeQueryIdsServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/DbNodeRemoveServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/DbNodeReplicateFrameServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/ExtaAddCommentServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/ExtaAddDescServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/ExtaAddSpecServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/ExtaAddTagServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/ExtaAddTagsServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/ExtaHasTagServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/ExtaLastCommentServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/ExtaLastDescServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/ExtaLastSpecServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/ExtaListCommentsServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/ExtaListDescsServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/ExtaListSpecsServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/ExtaListTagsFilteredServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/ExtaListTagsServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/ExtaRemoveAllCommentsServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/ExtaRemoveAllDescsServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/ExtaRemoveAllSpecsServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/ExtaRemoveAllTagsServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/ExtaRemoveLastCommentServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/ExtaRemoveLastDescServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/ExtaRemoveLastSpecServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/ExtaRemoveTagServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/ExtaRemoveTagsServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/OrmCountServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/OrmExistsServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/OrmFindRangeServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/OrmGetOneServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/OrmGetServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/OrmListIdsServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/OrmPutServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/OrmRemoveServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/OrmSaveOrUpdateServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/OrmWarmUpHelper.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/RegistryFindByPrefixKeysServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/RegistryFindByPrefixServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/RegistryGetServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/RegistryListChildrenServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/RegistryMkdirsServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/RegistryPutBytesServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/RegistryPutLongServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/RegistryPutStringServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/RegistryRemoveServerHandler.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/Tgt3RegistryResolver.java`
- `p2p-db/src/test/java/com/q3lives/ds/backup/`
- `p2p-db/src/test/java/com/q3lives/ds/binlog/`
- `p2p-db/src/test/java/com/q3lives/ds/cluster/`
- `p2p-db/src/test/java/com/q3lives/ds/core/DsMemoryBufferLockStripedTest.java`
- `p2p-db/src/test/java/com/q3lives/ds/core/DsMemoryLruSamplingTest.java`
- `p2p-db/src/test/java/com/q3lives/ds/core/DsWALCrashRecoveryTest.java`
- `p2p-db/src/test/java/com/q3lives/ds/database/DsDatabaseServer_P7_B1_OrmRoutingTest.java`
- `p2p-db/src/test/java/com/q3lives/ds/database/DsValueResolverTest.java`
- `p2p-db/src/test/java/com/q3lives/ds/database/columnar/ColumnarStoreCompressionTest.java`
- `p2p-db/src/test/java/com/q3lives/ds/database/columnar/ColumnarStoreLayoutTest.java`
- `p2p-db/src/test/java/com/q3lives/ds/database/columnar/ColumnarStoreTest.java`
- `p2p-db/src/test/java/com/q3lives/ds/database/columnar/DsColumnGroupStoreTest.java`
- `p2p-db/src/test/java/com/q3lives/ds/database/columnar/DsDistributedQueryTest.java`
- `p2p-db/src/test/java/com/q3lives/ds/database/columnar/DsDistributedReplicaTest.java`
- `p2p-db/src/test/java/com/q3lives/ds/database/columnar/DsDistributedSingleNodePartitionTest.java`
- `p2p-db/src/test/java/com/q3lives/ds/database/columnar/DsDistributedTwoNodeRouterTest.java`
- `p2p-db/src/test/java/ds/BinlogFrameCompressHelper.java`
- `p2p-db/src/test/java/ds/DbEntityBinlogFrame_P7_C4_HandlerTest.java`
- `p2p-db/src/test/java/ds/DsBinlogBasicTest.java`
- `p2p-db/src/test/java/ds/DsBinlogFrame_P7_Tgt4_LZ4_4_Test.java`
- `p2p-db/src/test/java/ds/DsDatabaseLocalMvccTest.java`
- `p2p-db/src/test/java/ds/DsEqIndexPlannerOperatorsTest.java`
- `p2p-db/src/test/java/ds/DsFixedValueStoreTest.java`
- `p2p-db/src/test/java/ds/DsFreeRingAsInodeSetTest.java`
- `p2p-db/src/test/java/ds/DsHashMapIdAllocatorTest.java`
- `p2p-db/src/test/java/ds/DsHashMapInlineTest.java`
- `p2p-db/src/test/java/ds/DsHashMapTest.java`
- `p2p-db/src/test/java/ds/DsHashSetTest.java`
- `p2p-db/src/test/java/ds/DsIdLayoutTest.java`
- `p2p-db/src/test/java/ds/DsRegistryExtAttrs_P7_Exts_Test.java`
- `p2p-db/src/test/java/ds/DsRegistryExtAttrs_P7_Tgt5_HandlerTest.java`
- `p2p-db/src/test/java/ds/DsRegistryP4Benchmark.java`
- `p2p-db/src/test/java/ds/DsRegistryTest.java`
- `p2p-db/src/test/java/ds/DsRegistry_P21_PrefixFindTest.java`
- `p2p-db/src/test/java/ds/DsRegistry_P22_ReadWriteLockConcurrencyTest.java`
- `p2p-db/src/test/java/ds/DsRegistry_P23_OrmBridgeTest.java`
- `p2p-db/src/test/java/ds/DsRegistry_P24_MultiSzListTest.java`
- `p2p-db/src/test/java/ds/DsRegistry_P31_MasterIndexPrefixTest.java`
- `p2p-db/src/test/java/ds/DsRegistry_P33_BidirectionalOrmTest.java`
- `p2p-db/src/test/java/ds/DsRegistry_P51_DsIdLayoutTest.java`
- `p2p-db/src/test/java/ds/DsRegistry_P52_LegacyMigrationTest.java`
- `p2p-db/src/test/java/ds/DsRegistry_P6a_GatesValidationTest.java`
- `p2p-db/src/test/java/ds/DsRegistry_P6b_DsIdLayoutStressTest.java`
- `p2p-db/src/test/java/ds/DsRegistry_P7_A1_DistributedNodeIdTest.java`
- `p2p-db/src/test/java/ds/DsRegistry_P7_Tgt3_HandlerTest.java`
- `p2p-db/src/test/java/ds/DsRegistry_Phase1_DbMetaPackedAttrs_8_Test.java`
- `p2p-db/src/test/java/ds/DsStringBlockStoreTest.java`
- `p2p-db/src/test/java/ds/DsStringRefCountTest.java`
- `p2p-db/src/test/java/ds/DsTypeFixedBucketStoreTest.java`
- `p2p-db/src/test/java/ds/DsTypeStringTest.java`
- `p2p-db/src/test/java/ds/Orm_P7_Tgt2_Handler_6_Test.java`
- `p2p-db/src/test/java/ds/Orm_P7_Tgt2_UserEntity.java`
- `p2p-db/src/test/java/javax/net/p2p/server/handler/`
- `p2p-sync/src/main/java/javax/net/p2p/filesync/monitor/P2PSyncMonitorServer.java`
- `p2p-sync/src/test/java/com/q3lives/ds/core/DsMemoryHotColdTieringTest.java`
- `p2p-sync/src/test/java/javax/net/p2p/filesync/monitor/P2PSyncMonitorServerTest.java`
- `p2p-transfer/pom.xml`

```text
.brain/context/current-state.md                    | 1110 +++++++++++++++-
 .brain/context/overview.md                         |    1 +
 .brain/context/workflows.md                        |    6 +-
 .brain/resources/changes/distill-proposal.md       |  393 ++----
 .brain/resources/changes/p2p-db.md                 |   11 +-
 AGENTS.md                                          |  153 ++-
 docs/dsdb-design-status-and-roadmap.md             |  143 +-
 docs/project-overview.md                           |    1 +
 docs/project-workflows.md                          |    6 +-
 .../io/protostuff/StringSerializerWithByteBuf.java |    3 +-
 .../main/java/javax/net/p2p/api/P2PCommand.java    |  188 ++-
 p2p-db/pom.xml                                     |   20 +-
 .../java/com/q3lives/ds/annotation/DsField.java    |    7 +
 .../java/com/q3lives/ds/annotation/DsTable.java    |   41 +-
 .../java/com/q3lives/ds/binlog/DsBinlogEntry.java  |   21 +-
 .../java/com/q3lives/ds/binlog/DsBinlogLayout.java |    7 +
 .../java/com/q3lives/ds/binlog/DsBinlogOpType.java |    4 +-
 .../com/q3lives/ds/binlog/DsBinlogReplayer.java    |  162 ++-
 .../java/com/q3lives/ds/binlog/DsBinlogStore.java  |  164 ++-
 .../java/com/q3lives/ds/binlog/DsBinlogWriter.java |   14 +-
 .../com/q3lives/ds/bucket/DsFixedBucketStore.java  |   27 +-
 .../java/com/q3lives/ds/collections/DsHashMap.java |   23 +-
 .../main/java/com/q3lives/ds/core/DsFreeRing.java  |  139 +-
 .../main/java/com/q3lives/ds/core/DsMemory.java    |  476 +++++--
 .../main/java/com/q3lives/ds/core/DsString.java    |   59 +
 .../src/main/java/com/q3lives/ds/core/DsWAL.java   |  252 ----
 .../com/q3lives/ds/database/DsDatabaseLocal.java   | 1405 +++++++++++++++++++-
 .../com/q3lives/ds/database/DsDatabaseServer.java  |  635 +++++----
 .../ds/database/adapter/DsTableAdapter.java        |  110 +-
 .../ds/database/columnar/ColumnarStore.java        |  760 +++++++++--
 .../ds/database/integration/GenericManager.java    |  354 ++++-
 .../ds/database/integration/QueryWrapper.java      |   18 +-
 .../ds/database/schema/EntitySchemaUtil.java       |   11 +-
 .../ds/core/DsMemoryBufferLockStripedTest.java     |    6 +-
 .../q3lives/ds/core/DsMemoryLruSamplingTest.java   |    6 +-
 .../q3lives/ds/core/DsWALCrashRecoveryTest.java    |  232 ----
 .../ds/database/columnar/ColumnarStoreTest.java    |  128 +-
 p2p-db/src/test/java/ds/DsBinlogBasicTest.java     |   67 +
 .../java/ds/DsEqIndexPlannerOperatorsTest.java     |  112 ++
 p2p-db/src/test/java/ds/DsHashMapTest.java         |   78 +-
 p2p-db/src/test/java/ds/DsHashSetTest.java         |    5 +-
 .../src/test/java/ds/DsStringBlockStoreTest.java   |   84 ++
 p2p-db/src/test/java/ds/DsStringRefCountTest.java  |   31 +
 .../p2p/filesync/monitor/P2PSyncMonitorServer.java |   10 +
 .../filesync/monitor/P2PSyncMonitorServerTest.java |   82 ++
 p2p-transfer/pom.xml                               |   25 +-
 46 files changed, 5954 insertions(+), 1636 deletions(-)
```

### Recent Durable Notes

- No durable note edits were recorded after the session baseline.

## Promotion Review

### boundary_fact [promotable]

Summary: Record the durable outcome and touched boundaries from "P7 Follow-up Tgt-4 C4 Binlog LZ4 frame compression for DbEntityBinlogFrame Handler".

Target: `.brain/context/current-state.md`

Why promotable: repo changes touched concrete files and boundaries that future sessions may need

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 281 changed file(s)
- 2 successful verification command(s) recorded


### invariant [promotable]

Summary: Promote any durable workflow or interface rule that "P7 Follow-up Tgt-4 C4 Binlog LZ4 frame compression for DbEntityBinlogFrame Handler" changed.

Target: `AGENTS.md`

Why promotable: workflow or interface surfaces changed and may need an explicit durable rule

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 281 changed file(s)
- 2 successful verification command(s) recorded
- signal: workflow_surface_changed


### verification_recipe [promotable]

Summary: Capture the repeatable verification recipe that proved "P7 Follow-up Tgt-4 C4 Binlog LZ4 frame compression for DbEntityBinlogFrame Handler".

Target: `.brain/resources/changes/p7-follow-up-tgt-4-c4-binlog-lz4-frame-compression-for-dbentitybinlogframe-handler.md`

Why promotable: successful verification commands were recorded against the packet-driven work

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 281 changed file(s)
- 2 successful verification command(s) recorded


### decision [insufficient]

Summary: Preserve the rationale if "P7 Follow-up Tgt-4 C4 Binlog LZ4 frame compression for DbEntityBinlogFrame Handler" changed a technical or workflow decision.

Target: `.brain/resources/decisions/p7-follow-up-tgt-4-c4-binlog-lz4-frame-compression-for-dbentitybinlogframe-handler.md`

Why not promoted: the session does not show strong evidence that a durable decision changed

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 281 changed file(s)
- 2 successful verification command(s) recorded


### follow_up [insufficient]

Summary: Record the unresolved follow-up required to fully close "P7 Follow-up Tgt-4 C4 Binlog LZ4 frame compression for DbEntityBinlogFrame Handler".

Target: `.brain/context/current-state.md`

Why not promoted: no unresolved verification or execution follow-up remains

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 281 changed file(s)
- 2 successful verification command(s) recorded


### gotcha [insufficient]

Summary: Capture any recurring trap or regression guard exposed while working on "P7 Follow-up Tgt-4 C4 Binlog LZ4 frame compression for DbEntityBinlogFrame Handler".

Target: `.brain/context/current-state.md`

Why not promoted: no failed verification or execution signal exposed a recurring trap

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 281 changed file(s)
- 2 successful verification command(s) recorded


## Proposed Updates

### .brain/context/current-state.md

Reason: repo changes touched concrete files and boundaries that future sessions may need [boundary_fact]

Suggested update:

```md
- Summarize the durable outcome from "P7 Follow-up Tgt-4 C4 Binlog LZ4 frame compression for DbEntityBinlogFrame Handler".
- Note the touched boundaries: `.brain/`, `.trae/`.
- Mention the highest-signal changed files: `.brain/context/current-state.md`, `.brain/context/overview.md`, `.brain/context/workflows.md`, `.brain/resources/changes/25-archify-diagrams-p2p-net-storage-validate-deliver-visual-check-receipts-distill-proposal.md`, `.brain/resources/changes/25-archify-diagrams-p2p-net-storage-validate-deliver-visual-check-receipts.md`, `.brain/resources/changes/column-level-mvcc-dsfield-dsdatabaselocal-9-tests-12-classes-0f0e.md`.
```

### .brain/resources/changes/p7-follow-up-tgt-4-c4-binlog-lz4-frame-compression-for-dbentitybinlogframe-handler.md

Reason: successful verification commands were recorded against the packet-driven work [verification_recipe]

Suggested update:

```md
## Verification for P7 Follow-up Tgt-4 C4 Binlog LZ4 frame compression for DbEntityBinlogFrame Handler

- Capture only the commands that proved the work after review.
- `mvn -pl p2p-db test -Dtest=DsBinlogFrame_P7_Tgt4_LZ4_4_Test -q`
- `mvn -pl p2p-db test -Dtest=DsRegistryTest,DsRegistry_P31_MasterIndexPrefixTest,DsRegistry_P7_A1_GlobalIds_6_Test,DsRegistry_P7_Tgt3_HandlerTest,DsRegistryExtAttrs_P7_Exts_Test,DsRegistryExtAttrs_P7_Tgt5_HandlerTest,DsRegistry_Phase1_DbMetaPackedAttrs_8_Test,Orm_P7_Tgt2_Handler_6_Test,DbEntityBinlogFrame_P7_C4_HandlerTest,DsBinlogFrame_P7_Tgt4_LZ4_4_Test,DsBinlogBasicTest,DsBinlogReplicator_P7_C4_Test,DsStringBlockStoreTest,DsTypeFixedBucketStoreTest,DsTypeStringTest -q`
```

### AGENTS.md

Reason: workflow or interface surfaces changed and may need an explicit durable rule [invariant]

Suggested update:

```md
- If "P7 Follow-up Tgt-4 C4 Binlog LZ4 frame compression for DbEntityBinlogFrame Handler" changed a reusable workflow or interface rule, record it here as an operational invariant.
- Review the changed surfaces first: `.brain/context/current-state.md`, `.brain/context/overview.md`, `.brain/context/workflows.md`, `.brain/resources/changes/25-archify-diagrams-p2p-net-storage-validate-deliver-visual-check-receipts-distill-proposal.md`, `.brain/resources/changes/25-archify-diagrams-p2p-net-storage-validate-deliver-visual-check-receipts.md`, `.brain/resources/changes/column-level-mvcc-dsfield-dsdatabaselocal-9-tests-12-classes-0f0e.md`.
```
