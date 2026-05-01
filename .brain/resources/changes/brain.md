---
title: Brain
updated: "2026-05-01T04:47:11Z"
---
## Verification

- `brain doctor`
- `brain find p2p-net-storage`
- `brain search "p2p-net-storage brain smoke validation"`
- `brain session finish`

## Notes

- Default `brain` command flow now passes again after repairing the user-level config and removing `NUL` contamination from project-local Brain state files.
- `brain doctor` now reports `config`, `workspace`, `note_integrity`, and `index_freshness` as `ok` under the default config path.
- A clean smoke path for future diagnosis is: `brain doctor` first, then `brain find p2p-net-storage`, then a targeted `brain search ...`, and finally `brain session finish`.
- If default commands regress with `yaml: control characters are not allowed`, the first inspection target should be `AppData/Roaming/brain/config.yaml`, followed by `.brain/state/history.jsonl` and `.brain/state/backups/*.bak`.
