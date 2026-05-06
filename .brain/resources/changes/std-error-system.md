## Verification

- `mvn -pl p2p-core -DskipTests=false -Dtest=ServiceAvailabilityTest,AuthHandshakeQuicTest test`
- `mvn -pl p2p-core -DskipTests=true install`
- `mvn -pl p2p-transfer -DskipTests=true test`

## Notes

- `STD_ERROR(-1)` now carries a structured payload (`P2PStdError`) instead of a free-form string in core governance paths.
- Errors are standardized via `P2PErrorCode` (stable numeric codes + message keys + default messages).
- `AuthEnforcer` and `ServerMessageProcessor` now emit structured errors for handshake/login/permission, service unavailable, task not found, and unknown command routing.
- Client-side helpers in `p2p-transfer` now throw a structured exception when they receive `STD_ERROR` with `P2PStdError`, while remaining compatible with legacy string payloads.

