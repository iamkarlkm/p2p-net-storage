---
created: "2026-05-08T18:15:17Z"
distill_scope: session
promotion_categories:
    - boundary_fact
    - verification_recipe
proposed_targets:
    - .brain/context/current-state.md
    - .brain/resources/changes/dsdb-not-in.md
source_session_id: "1778263941315605600"
source_task: dsdb 动态查询：NOT_IN 索引排除集加速
title: dsdb 动态查询：NOT_IN 索引排除集加速 Distill Proposal
type: distill_proposal
updated: "2026-05-08T18:15:17Z"
---
# dsdb 动态查询：NOT_IN 索引排除集加速 Distill Proposal

## Source Provenance

- Mode: `session`
- Session: `1778263941315605600`
- Task: dsdb 动态查询：NOT_IN 索引排除集加速
- Git baseline: `a37a375af50bffb86207b90e1d2772672b303e89`

### Commands Run

- `mvn -pl p2p-db -Dtest=DbEntityP2PHandlersTest test` (exit 0)

### Git Diff

- `.brain/context/current-state.md`
- `.brain/resources/changes/dsdb-eq-distill-proposal.md`
- `.brain/resources/changes/dsdb-in-union-distill-proposal.md`
- `.brain/resources/changes/dsdb-in-union.md`
- `.brain/resources/changes/dsdb-or-dnf-and-or-distill-proposal.md`
- `.brain/resources/changes/dsdb-or-dnf-and-or.md`
- `.brain/resources/changes/dsdb.md`
- `p2p-core/src/main/java/javax/net/p2p/model/DbQuery.java`
- `p2p-core/src/main/java/javax/net/p2p/model/DbQueryOrGroup.java`
- `p2p-db/src/main/java/com/q3lives/ds/core/DsObject.java`
- `p2p-db/src/main/java/javax/net/p2p/server/handler/DbRowQueryIdsServerHandler.java`
- `p2p-db/src/test/java/com/q3lives/ds/database/DbEntityP2PHandlersTest.java`

```text
.brain/context/current-state.md                    |   8 +-
 .brain/resources/changes/dsdb.md                   |  20 +-
 .../src/main/java/javax/net/p2p/model/DbQuery.java |   3 +-
 .../main/java/com/q3lives/ds/core/DsObject.java    |  24 +--
 .../server/handler/DbRowQueryIdsServerHandler.java | 213 ++++++++++++++++++---
 .../ds/database/DbEntityP2PHandlersTest.java       | 186 ++++++++++++++++++
 6 files changed, 408 insertions(+), 46 deletions(-)
```

### Recent Durable Notes

- No durable note edits were recorded after the session baseline.

## Promotion Review

### boundary_fact [promotable]

Summary: Record the durable outcome and touched boundaries from "dsdb 动态查询：NOT_IN 索引排除集加速".

Target: `.brain/context/current-state.md`

Why promotable: repo changes touched concrete files and boundaries that future sessions may need

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 12 changed file(s)
- 1 successful verification command(s) recorded


### verification_recipe [promotable]

Summary: Capture the repeatable verification recipe that proved "dsdb 动态查询：NOT_IN 索引排除集加速".

Target: `.brain/resources/changes/dsdb-not-in.md`

Why promotable: successful verification commands were recorded against the packet-driven work

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 12 changed file(s)
- 1 successful verification command(s) recorded


### decision [insufficient]

Summary: Preserve the rationale if "dsdb 动态查询：NOT_IN 索引排除集加速" changed a technical or workflow decision.

Target: `.brain/resources/decisions/dsdb-not-in.md`

Why not promoted: the session does not show strong evidence that a durable decision changed

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 12 changed file(s)
- 1 successful verification command(s) recorded


### follow_up [insufficient]

Summary: Record the unresolved follow-up required to fully close "dsdb 动态查询：NOT_IN 索引排除集加速".

Target: `.brain/context/current-state.md`

Why not promoted: no unresolved verification or execution follow-up remains

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 12 changed file(s)
- 1 successful verification command(s) recorded


### gotcha [insufficient]

Summary: Capture any recurring trap or regression guard exposed while working on "dsdb 动态查询：NOT_IN 索引排除集加速".

Target: `.brain/context/current-state.md`

Why not promoted: no failed verification or execution signal exposed a recurring trap

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 12 changed file(s)
- 1 successful verification command(s) recorded


### invariant [insufficient]

Summary: Promote any durable workflow or interface rule that "dsdb 动态查询：NOT_IN 索引排除集加速" changed.

Target: `AGENTS.md`

Why not promoted: no workflow or contract surface changed strongly enough to justify a durable rule

Diagnostics:
- linked to 1 compiled packet(s)
- touches 2 boundary/boundaries
- touches 12 changed file(s)
- 1 successful verification command(s) recorded


## Proposed Updates

### .brain/context/current-state.md

Reason: repo changes touched concrete files and boundaries that future sessions may need [boundary_fact]

Suggested update:

```md
- Summarize the durable outcome from "dsdb 动态查询：NOT_IN 索引排除集加速".
- Note the touched boundaries: `.brain/`, `p2p-core/`.
- Mention the highest-signal changed files: `.brain/context/current-state.md`, `.brain/resources/changes/dsdb-eq-distill-proposal.md`, `.brain/resources/changes/dsdb-in-union-distill-proposal.md`, `.brain/resources/changes/dsdb-in-union.md`, `.brain/resources/changes/dsdb-or-dnf-and-or-distill-proposal.md`, `.brain/resources/changes/dsdb-or-dnf-and-or.md`.
```

### .brain/resources/changes/dsdb-not-in.md

Reason: successful verification commands were recorded against the packet-driven work [verification_recipe]

Suggested update:

```md
## Verification for dsdb 动态查询：NOT_IN 索引排除集加速

- Capture only the commands that proved the work after review.
- `mvn -pl p2p-db -Dtest=DbEntityP2PHandlersTest test`
```
