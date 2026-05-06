---
updated: "2026-05-04T05:14:05Z"
---
## Verification for ORM + YAML DbHome

- `mvn -pl p2p-db -DskipTests=true test`
- `mvn -pl p2p-db -DskipTests=false -Dtest=DsDatabaseLoclalOrmTest test`

## YAML / system properties

- YAML file: `SystemConfig.yaml`
  - key: `DbHome` (absolute path, or path relative to the YAML file directory)
- System properties:
  - `p2p.db.home` (highest priority)
  - `p2p.system.yaml` (explicit YAML file path; defaults to `./SystemConfig.yaml` when present)
