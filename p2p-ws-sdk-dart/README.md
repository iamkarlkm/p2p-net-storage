# p2p-ws-sdk-dart

Dart SDK for `p2p-ws-protocol`.

## Layout

- `lib/src/commands.dart`: command constants
- `lib/src/frame.dart`: WS binary frame header (8 bytes) encode/decode
- `lib/src/xor.dart`: XOR (no-wrap) cipher
- `lib/src/proto_lite.dart`: minimal protobuf codec for protocol messages
- `lib/src/messages/*`: protocol message codecs
- `lib/src/handshake.dart`: RSA-OAEP(SHA-256) decrypt + handshake helpers
- `lib/src/rsa.dart`: RSA-SHA256 (PKCS#1 v1.5) signing helper
- `lib/src/session.dart`: websocket session (handshake + encrypted wrapper I/O)
- `tool/verify_vectors.dart`: verify `p2p-ws-protocol/test-vectors/*`

## Verify vectors

From repo root:

```bash
cd p2p-ws-sdk-dart
dart pub get
dart run tool/verify_vectors.dart
```

## Echo client

```bash
cd p2p-ws-sdk-dart
dart pub get
dart run example/echo_client.dart ../p2p-ws-protocol/examples/client.yaml
```

## Center join

```bash
cd p2p-ws-sdk-dart
dart pub get
dart run example/center_join.dart ../p2p-ws-protocol/examples/center_client.yaml
```

## Peer connect

```bash
cd p2p-ws-sdk-dart
dart pub get
dart run example/peer_connect.dart ../p2p-ws-protocol/examples/peer1.yaml 2
```

## Peer node (server)

Requires `storage_locations` in the YAML config. File operations use protobuf payloads in `P2PWrapper.data`:

- `command=14/15 (PUT_FILE/FORCE_PUT_FILE)`: `FileDataModel{store_id,path,data,...}`
- `command=7/8 (GET_FILE/R_OK_GET_FILE)`: `FileDataModel{store_id,path}` / `FileDataModel{...,data}`
- `command=20/43 (GET_FILE_SEGMENTS/R_OK_GET_FILE_SEGMENTS)`: `FileSegmentsDataModel`
- `command=21/44 (PUT_FILE_SEGMENTS/PUT_FILE_SEGMENTS_COMPLETE)`: `FileSegmentsDataModel`
- `command=22 (CHECK_FILE)`: `FileDataModel{store_id,path,length,md5}`
- `command=46 (INFO_FILE)`: `FileDataModel{store_id,path}` (md5=="" will be filled)
- `command=50 (FILE_RENAME)`: `FileRenameRequest{store_id,src_path,dst_path}`
- `command=51 (FILE_LIST)`: `FileListRequest{store_id,path,page,page_size}` (page_size default 100) -> `STD_OK.data = FileListResponse`
- `command=52 (FILE_EXISTS)`: `FileDataModel{store_id,path}` -> `STD_OK` / `STD_ERROR`
- `command=53 (FILE_MKDIRS)`: `FileDataModel{store_id,path}` -> `STD_OK` / `STD_ERROR`

```bash
cd p2p-ws-sdk-dart
dart pub get
dart run bin/p2pd.dart ../p2p-ws-protocol/examples/peer1.yaml
```

## E2E file ops

Start a peer node first, then run:

```bash
cd p2p-ws-sdk-dart
dart pub get
dart run example/e2e_file_ops.dart ../p2p-ws-protocol/examples/peer1.yaml
```

### Connect hint (optional)

Requires `enable_connect_hint: true` in the YAML config.

```bash
cd p2p-ws-sdk-dart
dart pub get
dart run bin/p2pd.dart ../p2p-ws-protocol/examples/peer1.yaml 2
```
