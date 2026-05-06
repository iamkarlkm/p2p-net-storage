---
updated: "2026-05-04T06:16:19Z"
---
## Remote ORM (DsDatabaseServer) + P2PCommand

### Commands

- `DB_ENTITY_PUT(24000)` -> `R_OK_DB_ENTITY_PUT(24001)`
- `DB_ENTITY_GET(24002)` -> `R_OK_DB_ENTITY_GET(24003)`
- Category: `P2PServiceCategory.DS_DB`

### Relations payload

- `DbEntityPutRequest.relations` carries an encoded `DbEntityRelationsPayload` (protostuff bytes).
- `DbEntityGetResponse.relations` returns the encoded relations payload when `withRelations=true`.

### Config (client)

- YAML: `dsdb.yaml` (default `./dsdb.yaml`, or `-Dds.db.yaml=...`)
- Server address: `server.ip/server.port`
- Auth (inline):
  - `server.auth` follows the `AuthConfig` schema (client section used)
  - Implementation sets `p2p.auth.inlineYaml` and `p2p.auth.inlineBaseDir` for `handshake/login`

### Verification

- `mvn -pl p2p-core -DskipTests=true install`
- `mvn -pl p2p-db -DskipTests=false "-Dtest=DsDatabaseLoclalOrmTest,DbEntityP2PHandlersTest" test`
