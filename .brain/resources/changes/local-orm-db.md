---
updated: "2026-05-04T04:23:30Z"
---
## Verification for local ORM database

- `mvn -pl p2p-db -DskipTests=false -Dtest=DsDatabaseLoclalOrmTest test`

## Notes

- Local DB root resolution:
  - System property `p2p.db.home` takes highest priority.
  - Otherwise load YAML from `-Dp2p.system.yaml=...` or `./SystemConfig.yaml`, then use `DbHome` (absolute or relative-to-yaml-dir).
- ORM entrypoint: `com.q3lives.ds.database.DsDatabaseLoclal`
