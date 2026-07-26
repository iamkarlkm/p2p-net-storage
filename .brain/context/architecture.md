# Architecture

<!-- brain:begin context-architecture -->
Use this file for the structural shape of the repository.

## Architecture Notes

- Keep repo boundaries explicit and document key entrypoints in this file.
- Update this file when runtime architecture or integration boundaries change.
<!-- brain:end context-architecture -->

## Local Notes

- `lib/bin/` stores checked-in native runtime assets, including the Windows UDT JNI DLL set under `lib/bin/lib/amd64-Windows-gpp/jni/`.
- Treat these binaries as runtime dependencies owned by the repo, not as generated build output.
