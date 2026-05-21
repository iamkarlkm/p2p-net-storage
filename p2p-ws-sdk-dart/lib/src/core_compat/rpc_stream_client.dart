import "dart:async";
import "dart:typed_data";

import "core_ws_client.dart";
import "protostuff.dart";
import "rpc_proto_lite.dart";

class CoreRpcStreamClient {
  final CoreWsClient _ws;

  CoreRpcStreamClient(this._ws);

  Future<CoreRpcServerStream> openServerStream({
    required String service,
    required String method,
    required Uint8List requestPayload,
    int permits = 2,
    int windowUpdateBatch = 2,
    int maxInflightFrames = 0,
    int maxFrameBytes = 0,
    Duration timeout = const Duration(seconds: 10),
  }) async {
    return _openInboundStream(
      commandName: "RPC_STREAM",
      callType: 2,
      service: service,
      method: method,
      requestPayload: requestPayload,
      permits: permits,
      windowUpdateBatch: windowUpdateBatch,
      maxInflightFrames: maxInflightFrames,
      maxFrameBytes: maxFrameBytes,
      timeout: timeout,
    );
  }

  Future<CoreRpcServerStream> openEventStream({
    required String service,
    required String method,
    required Uint8List requestPayload,
    int permits = 2,
    int windowUpdateBatch = 2,
    int maxInflightFrames = 0,
    int maxFrameBytes = 0,
    Duration timeout = const Duration(seconds: 10),
  }) async {
    return _openInboundStream(
      commandName: "RPC_EVENT",
      callType: 2,
      service: service,
      method: method,
      requestPayload: requestPayload,
      permits: permits,
      windowUpdateBatch: windowUpdateBatch,
      maxInflightFrames: maxInflightFrames,
      maxFrameBytes: maxFrameBytes,
      timeout: timeout,
    );
  }

  Future<CoreRpcClientStream> openClientStream({
    required String service,
    required String method,
    int permits = 2,
    int windowUpdateBatch = 2,
    int maxInflightFrames = 0,
    int maxFrameBytes = 0,
    Duration timeout = const Duration(seconds: 10),
  }) async {
    return _openDuplexStream(
      callType: 3,
      service: service,
      method: method,
      permits: permits,
      windowUpdateBatch: windowUpdateBatch,
      maxInflightFrames: maxInflightFrames,
      maxFrameBytes: maxFrameBytes,
      timeout: timeout,
    ) as CoreRpcClientStream;
  }

  Future<CoreRpcBidiStream> openBidiStream({
    required String service,
    required String method,
    int permits = 2,
    int windowUpdateBatch = 2,
    int maxInflightFrames = 0,
    int maxFrameBytes = 0,
    Duration timeout = const Duration(seconds: 10),
  }) async {
    return _openDuplexStream(
      callType: 4,
      service: service,
      method: method,
      permits: permits,
      windowUpdateBatch: windowUpdateBatch,
      maxInflightFrames: maxInflightFrames,
      maxFrameBytes: maxFrameBytes,
      timeout: timeout,
    ) as CoreRpcBidiStream;
  }

  Future<CoreRpcServerStream> _openInboundStream({
    required String commandName,
    required int callType,
    required String service,
    required String method,
    required Uint8List requestPayload,
    required int permits,
    required int windowUpdateBatch,
    required int maxInflightFrames,
    required int maxFrameBytes,
    required Duration timeout,
  }) async {
    final requestId = _ws.allocateSeq();
    final meta = encodeRpcMeta(requestId: requestId, service: service, method: method, callType: callType, deadlineEpochMs: DateTime.now().millisecondsSinceEpoch + timeout.inMilliseconds);
    final open = encodeRpcFrameOpenStream(
      metaBytes: meta,
      payload: requestPayload,
      permits: permits,
      maxInflightFrames: maxInflightFrames,
      maxFrameBytes: maxFrameBytes,
      endOfStream: true,
    );

    final session = _InboundStreamSession(
      requestId: requestId,
      windowUpdateBatch: windowUpdateBatch,
      sendWindowUpdate: (p) => _sendWindowUpdate(requestId: requestId, permits: p),
      onRequestWindowUpdate: null,
    );
    _ws.registerStreamHandler(requestId, (w) => session.onWrapper(w));

    final cmd = _ws.mustOrdinal(commandName);
    final ack = await _ws.sendStreamOpen(
      CoreStreamP2PWrapper(seq: requestId, commandOrdinal: cmd, data: open, index: 0, completed: false, canceled: false),
      timeout: timeout,
    );
    if (ack.commandOrdinal != _ws.mustOrdinal("STREAM_ACK")) {
      _ws.registerStreamHandler(requestId, null);
      throw StateError("open stream failed: ${ack.commandOrdinal}");
    }
    return CoreRpcServerStream(_ws, requestId, session);
  }

  Future<Object> _openDuplexStream({
    required int callType,
    required String service,
    required String method,
    required int permits,
    required int windowUpdateBatch,
    required int maxInflightFrames,
    required int maxFrameBytes,
    required Duration timeout,
  }) async {
    final requestId = _ws.allocateSeq();
    final meta = encodeRpcMeta(requestId: requestId, service: service, method: method, callType: callType, deadlineEpochMs: DateTime.now().millisecondsSinceEpoch + timeout.inMilliseconds);
    final open = encodeRpcFrameOpenStream(
      metaBytes: meta,
      payload: Uint8List(0),
      permits: permits,
      maxInflightFrames: maxInflightFrames,
      maxFrameBytes: maxFrameBytes,
      endOfStream: false,
    );

    final outboundWindow = _OutboundWindow(permits);
    final session = _InboundStreamSession(
      requestId: requestId,
      windowUpdateBatch: windowUpdateBatch,
      sendWindowUpdate: (p) => _sendWindowUpdate(requestId: requestId, permits: p),
      onRequestWindowUpdate: (p) => outboundWindow.addPermits(p),
    );
    _ws.registerStreamHandler(requestId, (w) => session.onWrapper(w));

    final ack = await _ws.sendStreamOpen(
      CoreStreamP2PWrapper(seq: requestId, commandOrdinal: _ws.mustOrdinal("RPC_STREAM"), data: open, index: 0, completed: false, canceled: false),
      timeout: timeout,
    );
    if (ack.commandOrdinal != _ws.mustOrdinal("STREAM_ACK")) {
      _ws.registerStreamHandler(requestId, null);
      throw StateError("open stream failed: ${ack.commandOrdinal}");
    }

    final sender = _OutboundStreamSender(_ws, requestId, outboundWindow, maxFrameBytes);
    if (callType == 3) {
      return CoreRpcClientStream(_ws, requestId, sender, session);
    }
    return CoreRpcBidiStream(_ws, requestId, sender, session);
  }

  Future<void> _sendWindowUpdate({required int requestId, required int permits}) async {
    final frame = encodeRpcFrameWindowUpdate(requestId: requestId, permits: permits);
    await _ws.send(CoreP2PWrapper(seq: requestId, commandOrdinal: _ws.mustOrdinal("RPC_CONTROL"), data: frame), encrypt: true);
  }
}

class CoreRpcServerStream extends Stream<Uint8List> {
  final CoreWsClient _ws;
  final int _requestId;
  final _InboundStreamSession _session;

  CoreRpcServerStream(this._ws, this._requestId, this._session);

  @override
  StreamSubscription<Uint8List> listen(void Function(Uint8List event)? onData, {Function? onError, void Function()? onDone, bool? cancelOnError}) {
    return _session.stream.listen(onData, onError: onError, onDone: onDone, cancelOnError: cancelOnError);
  }

  Future<void> cancel() async {
    _ws.registerStreamHandler(_requestId, null);
    _session.close();
    final frame = encodeRpcFrameCancel(requestId: _requestId);
    await _ws.send(CoreP2PWrapper(seq: _requestId, commandOrdinal: _ws.mustOrdinal("RPC_CONTROL"), data: frame), encrypt: true);
  }

  void close() {
    _ws.registerStreamHandler(_requestId, null);
    _session.close();
  }
}

class CoreRpcClientStream {
  final CoreWsClient _ws;
  final int _requestId;
  final _OutboundStreamSender _sender;
  final _InboundStreamSession _inbound;
  bool _done = false;

  CoreRpcClientStream(this._ws, this._requestId, this._sender, this._inbound);

  Future<void> sendMessage(Uint8List payload) async {
    if (_done) throw StateError("stream closed");
    await _sender.sendMessage(payload);
  }

  Future<Uint8List> halfCloseAndAwait() async {
    if (_done) throw StateError("stream closed");
    _done = true;
    await _sender.sendClose();
    return await _inbound.nextMessageOrEmpty();
  }

  Future<void> cancel() async {
    if (_done) return;
    _done = true;
    _ws.registerStreamHandler(_requestId, null);
    _inbound.close();
    final frame = encodeRpcFrameCancel(requestId: _requestId);
    await _ws.send(CoreP2PWrapper(seq: _requestId, commandOrdinal: _ws.mustOrdinal("RPC_CONTROL"), data: frame), encrypt: true);
  }
}

class CoreRpcBidiStream extends Stream<Uint8List> {
  final CoreWsClient _ws;
  final int _requestId;
  final _OutboundStreamSender _sender;
  final _InboundStreamSession _inbound;
  bool _done = false;

  CoreRpcBidiStream(this._ws, this._requestId, this._sender, this._inbound);

  Future<void> sendMessage(Uint8List payload) async {
    if (_done) throw StateError("stream closed");
    await _sender.sendMessage(payload);
  }

  Future<void> halfClose() async {
    if (_done) throw StateError("stream closed");
    _done = true;
    await _sender.sendClose();
  }

  Future<void> cancel() async {
    if (_done) return;
    _done = true;
    _ws.registerStreamHandler(_requestId, null);
    _inbound.close();
    final frame = encodeRpcFrameCancel(requestId: _requestId);
    await _ws.send(CoreP2PWrapper(seq: _requestId, commandOrdinal: _ws.mustOrdinal("RPC_CONTROL"), data: frame), encrypt: true);
  }

  @override
  StreamSubscription<Uint8List> listen(void Function(Uint8List event)? onData, {Function? onError, void Function()? onDone, bool? cancelOnError}) {
    return _inbound.stream.listen(onData, onError: onError, onDone: onDone, cancelOnError: cancelOnError);
  }
}

class _InboundStreamSession {
  final int requestId;
  final int windowUpdateBatch;
  final Future<void> Function(int permits) sendWindowUpdate;
  final void Function(int permits)? onRequestWindowUpdate;

  final _ctl = StreamController<Uint8List>(sync: true);
  final _chunks = <Uint8List>[];
  var _credits = 0;
  var _closed = false;

  _InboundStreamSession({
    required this.requestId,
    required this.windowUpdateBatch,
    required this.sendWindowUpdate,
    required this.onRequestWindowUpdate,
  });

  Stream<Uint8List> get stream => _ctl.stream;

  void onWrapper(CoreStreamP2PWrapper w) {
    if (_closed) return;
    final frame = decodeRpcFrame(w.data);
    if (frame.frameType == 6) {
      if (frame.permits > 0) onRequestWindowUpdate?.call(frame.permits);
      return;
    }
    if (frame.frameType == 7) {
      return;
    }
    if (frame.frameType == 5) {
      _ctl.addError(StateError(frame.statusMessage.isEmpty ? "RPC ERROR" : frame.statusMessage));
      close();
      return;
    }
    if (frame.payload.isNotEmpty) {
      _chunks.add(frame.payload);
    }
    if (frame.endOfMessage) {
      final merged = _merge(_chunks);
      _chunks.clear();
      _credits += 1;
      if (_credits >= windowUpdateBatch) {
        final p = _credits;
        _credits = 0;
        unawaited(sendWindowUpdate(p));
      }
      _ctl.add(merged);
    }
    if (frame.frameType == 3 || frame.endOfStream) {
      if (frame.statusCode != 0 && frame.payload.isEmpty) {
        _ctl.addError(StateError(frame.statusMessage.isEmpty ? "RPC failed" : frame.statusMessage));
      }
      close();
    }
  }

  Future<Uint8List> nextMessageOrEmpty() async {
    try {
      return await stream.first;
    } catch (_) {
      return Uint8List(0);
    }
  }

  void close() {
    if (_closed) return;
    _closed = true;
    _ctl.close();
  }
}

class _OutboundWindow {
  var _permits = 0;
  final _waiters = <Completer<void>>[];

  _OutboundWindow(int initialPermits) {
    _permits = initialPermits < 0 ? 0 : initialPermits;
  }

  void addPermits(int p) {
    if (p <= 0) return;
    _permits += p;
    while (_permits > 0 && _waiters.isNotEmpty) {
      _permits -= 1;
      _waiters.removeAt(0).complete();
    }
  }

  Future<void> acquire() {
    if (_permits > 0) {
      _permits -= 1;
      return Future.value();
    }
    final c = Completer<void>();
    _waiters.add(c);
    return c.future;
  }
}

class _OutboundStreamSender {
  final CoreWsClient _ws;
  final int _requestId;
  final _OutboundWindow _window;
  final int _maxFrameBytes;
  var _index = 1;

  _OutboundStreamSender(this._ws, this._requestId, this._window, int maxFrameBytes) : _maxFrameBytes = maxFrameBytes < 0 ? 0 : maxFrameBytes;

  Future<void> sendMessage(Uint8List payload) async {
    final chunks = _chunk(payload, _maxFrameBytes);
    for (var i = 0; i < chunks.length; i++) {
      await _window.acquire();
      final frame = encodeRpcFrameData(requestId: _requestId, payload: chunks[i], chunkIndex: i, endOfMessage: i == chunks.length - 1);
      await _ws.sendStream(
        CoreStreamP2PWrapper(seq: _requestId, commandOrdinal: _ws.mustOrdinal("RPC_STREAM"), data: frame, index: _index, completed: false, canceled: false),
        encrypt: true,
      );
      _index += 1;
    }
  }

  Future<void> sendClose() async {
    final frame = encodeRpcFrameClose(requestId: _requestId);
    await _ws.sendStream(
      CoreStreamP2PWrapper(seq: _requestId, commandOrdinal: _ws.mustOrdinal("RPC_STREAM"), data: frame, index: _index, completed: true, canceled: false),
      encrypt: true,
    );
    _index += 1;
  }
}

Uint8List _merge(List<Uint8List> chunks) {
  if (chunks.isEmpty) return Uint8List(0);
  if (chunks.length == 1) return chunks[0];
  var total = 0;
  for (final c in chunks) {
    total += c.length;
  }
  final out = Uint8List(total);
  var off = 0;
  for (final c in chunks) {
    out.setRange(off, off + c.length, c);
    off += c.length;
  }
  return out;
}

List<Uint8List> _chunk(Uint8List payload, int maxFrameBytes) {
  if (maxFrameBytes <= 0 || payload.length <= maxFrameBytes) return [payload];
  final out = <Uint8List>[];
  for (var off = 0; off < payload.length; off += maxFrameBytes) {
    out.add(Uint8List.sublistView(payload, off, (off + maxFrameBytes > payload.length) ? payload.length : off + maxFrameBytes));
  }
  return out;
}

