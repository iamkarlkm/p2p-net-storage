import "dart:typed_data";

import "core_ws_client.dart";
import "protostuff.dart";
import "rpc_proto_lite.dart";

class CoreRpcClient {
  final CoreWsClient _ws;

  CoreRpcClient(this._ws);

  Future<List<DiscoverServiceLite>> discover({String service = "", bool includeMethods = false}) async {
    final req = encodeDiscoverRequest(service: service, includeMethods: includeMethods);
    final frame = await _unary(commandName: "RPC_DISCOVER", service: service, method: "Discover", requestPayload: req);
    return decodeDiscoverResponse(frame.payload);
  }

  Future<({bool healthy, bool ready, String message})> health({required String service}) async {
    final req = encodeHealthCheckRequest(service);
    final frame = await _unary(commandName: "RPC_HEALTH", service: service, method: "Check", requestPayload: req);
    return decodeHealthCheckResponse(frame.payload);
  }

  Future<({String message, int serverTime})> echo(String message) async {
    final req = encodeEchoRequest(message);
    final frame = await _unary(
      commandName: "RPC_UNARY",
      service: "p2p.rpc.echo.v1.EchoService",
      method: "Echo",
      requestPayload: req,
    );
    return decodeEchoResponse(frame.payload);
  }

  Future<RpcFrameLite> _unary({
    required String commandName,
    required String service,
    required String method,
    required Uint8List requestPayload,
  }) async {
    final reqId = _ws.allocateSeq();
    final meta = encodeRpcMeta(
      requestId: reqId,
      service: service,
      method: method,
      callType: 1,
      deadlineEpochMs: DateTime.now().millisecondsSinceEpoch + 10 * 1000,
    );
    final open = encodeRpcFrameOpenUnary(metaBytes: meta, payload: requestPayload, endOfStream: true);
    final cmd = _ws.mustOrdinal(commandName);
    final resp = await _ws.sendAndAwait(CoreP2PWrapper(seq: reqId, commandOrdinal: cmd, data: open), encrypt: true);
    final frame = decodeRpcFrame(resp.data);
    if (frame.frameType == 5) {
      throw StateError(frame.statusMessage.isEmpty ? "RPC ERROR" : frame.statusMessage);
    }
    if (frame.statusCode != 0 && frame.payload.isEmpty) {
      throw StateError(frame.statusMessage.isEmpty ? "RPC failed" : frame.statusMessage);
    }
    return frame;
  }
}
