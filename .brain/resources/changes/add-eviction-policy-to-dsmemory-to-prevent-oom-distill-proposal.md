---
created: "2026-08-05T14:57:59Z"
distill_scope: session
promotion_categories:
    - boundary_fact
    - follow_up
    - gotcha
    - verification_recipe
proposed_targets:
    - .brain/context/current-state.md
    - .brain/context/current-state.md
    - .brain/context/current-state.md
    - .brain/resources/changes/add-eviction-policy-to-dsmemory-to-prevent-oom.md
source_session_id: "1785774712327806100"
source_task: Add eviction policy to DsMemory to prevent OOM
title: Add eviction policy to DsMemory to prevent OOM Distill Proposal
type: distill_proposal
updated: "2026-08-05T14:57:59Z"
---
# Add eviction policy to DsMemory to prevent OOM Distill Proposal

## Source Provenance

- Mode: `session`
- Session: `1785774712327806100`
- Task: Add eviction policy to DsMemory to prevent OOM
- Git baseline: `b16478285cd79cfc3a75d492911c580b4b270adc`

### Commands Run

- `mvn -pl p2p-db -Dmaven .repo.local=C:/Users/karl/.m2/repository -Dmaven .test.skip=true compile` (exit 1)
- `mvn -pl p2p-db -am -Dmaven.test.skip=true -Dmaven.repo.local=C:\Users\karl\.m2\repository -o install` (exit 0)
- `mvn -pl p2p-sync -Dmaven.repo.local=C:\Users\karl\.m2\repository -o -Dtest=DsMemoryEvictionTest -Dsurefire.failIfNoSpecifiedTests=false test` (exit 0)

### Git Diff

- `p2p-db/src/main/java/com/q3lives/ds/core/DsMemory.java`
- `p2p-sync/src/test/java/com/`

```text
.../main/java/com/q3lives/ds/core/DsMemory.java    | 487 +++++++++++++++++----
 1 file changed, 395 insertions(+), 92 deletions(-)
```

### Recent Durable Notes

- No durable note edits were recorded after the session baseline.

## Promotion Review

### boundary_fact [promotable]

Summary: Record the durable outcome and touched boundaries from "Add eviction policy to DsMemory to prevent OOM".

Target: `.brain/context/current-state.md`

Why promotable: repo changes touched concrete files and boundaries that future sessions may need

Diagnostics:
- linked to 2 compiled packet(s)
- touches 3 boundary/boundaries
- touches 2 changed file(s)
- 2 successful verification command(s) recorded
- 1 failed command(s) recorded


### follow_up [promotable]

Summary: Record the unresolved follow-up required to fully close "Add eviction policy to DsMemory to prevent OOM".

Target: `.brain/context/current-state.md`

Why promotable: the session still has unresolved verification or execution follow-up

Diagnostics:
- linked to 2 compiled packet(s)
- touches 3 boundary/boundaries
- touches 2 changed file(s)
- 2 successful verification command(s) recorded
- 1 failed command(s) recorded


### gotcha [promotable]

Summary: Capture any recurring trap or regression guard exposed while working on "Add eviction policy to DsMemory to prevent OOM".

Target: `.brain/context/current-state.md`

Why promotable: the session recorded failed commands that may deserve a durable trap note

Diagnostics:
- linked to 2 compiled packet(s)
- touches 3 boundary/boundaries
- touches 2 changed file(s)
- 2 successful verification command(s) recorded
- 1 failed command(s) recorded


### verification_recipe [promotable]

Summary: Capture the repeatable verification recipe that proved "Add eviction policy to DsMemory to prevent OOM".

Target: `.brain/resources/changes/add-eviction-policy-to-dsmemory-to-prevent-oom.md`

Why promotable: successful verification commands were recorded against the packet-driven work

Diagnostics:
- linked to 2 compiled packet(s)
- touches 3 boundary/boundaries
- touches 2 changed file(s)
- 2 successful verification command(s) recorded
- 1 failed command(s) recorded


### decision [insufficient]

Summary: Preserve the rationale if "Add eviction policy to DsMemory to prevent OOM" changed a technical or workflow decision.

Target: `.brain/resources/decisions/add-eviction-policy-to-dsmemory-to-prevent-oom.md`

Why not promoted: the session does not show strong evidence that a durable decision changed

Diagnostics:
- linked to 2 compiled packet(s)
- touches 3 boundary/boundaries
- touches 2 changed file(s)
- 2 successful verification command(s) recorded
- 1 failed command(s) recorded


### invariant [insufficient]

Summary: Promote any durable workflow or interface rule that "Add eviction policy to DsMemory to prevent OOM" changed.

Target: `AGENTS.md`

Why not promoted: no workflow or contract surface changed strongly enough to justify a durable rule

Diagnostics:
- linked to 2 compiled packet(s)
- touches 3 boundary/boundaries
- touches 2 changed file(s)
- 2 successful verification command(s) recorded
- 1 failed command(s) recorded


## Proposed Updates

### .brain/context/current-state.md

Reason: repo changes touched concrete files and boundaries that future sessions may need [boundary_fact]

Suggested update:

```md
- Summarize the durable outcome from "Add eviction policy to DsMemory to prevent OOM".
- Note the touched boundaries: `p2p-core/`, `p2p-db/`, `p2p-sync/`.
- Mention the highest-signal changed files: `p2p-db/src/main/java/com/q3lives/ds/core/DsMemory.java`, `p2p-sync/src/test/java/com/`.
```

### .brain/context/current-state.md

Reason: the session recorded failed commands that may deserve a durable trap note [gotcha]

Suggested update:

```md
- Capture the recurring trap exposed while working on "Add eviction policy to DsMemory to prevent OOM" only if it will matter again.
- Failed command to inspect: `mvn -pl p2p-db -Dmaven .repo.local=C:/Users/karl/.m2/repository -Dmaven .test.skip=true compile`
```

### .brain/context/current-state.md

Reason: the session still has unresolved verification or execution follow-up [follow_up]

Suggested update:

```md
- Record the unresolved follow-up for "Add eviction policy to DsMemory to prevent OOM" only if it should survive this session.
- Failed command still needing follow-up: `mvn -pl p2p-db -Dmaven .repo.local=C:/Users/karl/.m2/repository -Dmaven .test.skip=true compile`
```

### .brain/resources/changes/add-eviction-policy-to-dsmemory-to-prevent-oom.md

Reason: successful verification commands were recorded against the packet-driven work [verification_recipe]

Suggested update:

```md
## Verification for Add eviction policy to DsMemory to prevent OOM

- Capture only the commands that proved the work after review.
- `mvn -pl p2p-db -am -Dmaven.test.skip=true -Dmaven.repo.local=C:/Users/karl/.m2/repository -o install`
- `mvn -pl p2p-sync -Dmaven.repo.local=C:/Users/karl/.m2/repository -o -Dtest=DsMemoryEvictionTest -Dsurefire.failIfNoSpecifiedTests=false test`
```
