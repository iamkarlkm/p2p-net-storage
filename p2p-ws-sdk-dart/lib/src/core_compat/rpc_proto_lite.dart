import "dart:typed_data";

import "../proto_lite.dart";

class RpcFrameLite {
  final int frameType;
  final int statusCode;
  final String statusMessage;
  final Uint8List payload;
  final int chunkIndex;
  final bool endOfStream;
  final bool endOfMessage;
  final int permits;
  final int maxInflightFrames;
  final int maxFrameBytes;

  RpcFrameLite({
    required this.frameType,
    required this.statusCode,
    required this.statusMessage,
    required this.payload,
    this.chunkIndex = 0,
    this.endOfStream = false,
    this.endOfMessage = false,
    this.permits = 0,
    this.maxInflightFrames = 0,
    this.maxFrameBytes = 0,
  });
}

Uint8List encodeRpcMeta({
  required int requestId,
  required String service,
  required String method,
  required int callType,
  String serviceVersion = "",
  int deadlineEpochMs = 0,
  String codec = "protobuf",
}) {
  final w = ProtoWriter();
  w.writeUint64(1, requestId);
  if (service.isNotEmpty) w.writeString(2, service);
  if (method.isNotEmpty) w.writeString(3, method);
  if (serviceVersion.isNotEmpty) w.writeString(4, serviceVersion);
  w.writeInt32(5, callType);
  w.writeUint64(6, deadlineEpochMs);
  if (codec.isNotEmpty) w.writeString(7, codec);
  return w.takeBytes();
}

Uint8List encodeRpcMetaMinimal({required int requestId}) {
  final w = ProtoWriter();
  w.writeUint64(1, requestId);
  return w.takeBytes();
}

Uint8List encodeRpcFrameOpenUnary({required Uint8List metaBytes, required Uint8List payload, bool endOfStream = true}) {
  final w = ProtoWriter();
  w.writeEmbedded(1, metaBytes);
  w.writeInt32(2, 1);
  if (payload.isNotEmpty) w.writeBytesField(3, payload);
  w.writeBool(6, endOfStream);
  return w.takeBytes();
}

Uint8List encodeRpcFlowControl({int permits = 0, int maxInflightFrames = 0, int maxFrameBytes = 0}) {
  final w = ProtoWriter();
  if (permits > 0) w.writeInt32(1, permits);
  if (maxInflightFrames > 0) w.writeInt32(2, maxInflightFrames);
  if (maxFrameBytes > 0) w.writeInt32(3, maxFrameBytes);
  return w.takeBytes();
}

Uint8List encodeRpcFrameOpenStream({
  required Uint8List metaBytes,
  required Uint8List payload,
  required int permits,
  int maxInflightFrames = 0,
  int maxFrameBytes = 0,
  bool endOfStream = true,
}) {
  final w = ProtoWriter();
  w.writeEmbedded(1, metaBytes);
  w.writeInt32(2, 1);
  if (payload.isNotEmpty) w.writeBytesField(3, payload);
  w.writeEmbedded(7, encodeRpcFlowControl(permits: permits, maxInflightFrames: maxInflightFrames, maxFrameBytes: maxFrameBytes));
  w.writeBool(6, endOfStream);
  return w.takeBytes();
}

Uint8List encodeRpcFrameData({
  required int requestId,
  required Uint8List payload,
  required int chunkIndex,
  required bool endOfMessage,
}) {
  final w = ProtoWriter();
  w.writeEmbedded(1, encodeRpcMetaMinimal(requestId: requestId));
  w.writeInt32(2, 2);
  if (payload.isNotEmpty) w.writeBytesField(3, payload);
  w.writeInt32(5, chunkIndex);
  w.writeBool(8, endOfMessage);
  w.writeBool(6, false);
  return w.takeBytes();
}

Uint8List encodeRpcFrameClose({required int requestId}) {
  final w = ProtoWriter();
  w.writeEmbedded(1, encodeRpcMetaMinimal(requestId: requestId));
  w.writeInt32(2, 3);
  w.writeBool(6, true);
  return w.takeBytes();
}

Uint8List encodeRpcFrameCancel({required int requestId}) {
  final w = ProtoWriter();
  w.writeEmbedded(1, encodeRpcMetaMinimal(requestId: requestId));
  w.writeInt32(2, 4);
  w.writeBool(6, true);
  return w.takeBytes();
}

Uint8List encodeRpcFrameWindowUpdate({required int requestId, required int permits}) {
  final w = ProtoWriter();
  w.writeEmbedded(1, encodeRpcMetaMinimal(requestId: requestId));
  w.writeInt32(2, 6);
  w.writeEmbedded(7, encodeRpcFlowControl(permits: permits));
  w.writeBool(6, true);
  return w.takeBytes();
}

RpcFrameLite decodeRpcFrame(Uint8List payload) {
  final r = ProtoReader(payload);
  var frameType = 0;
  Uint8List framePayload = Uint8List(0);
  var statusCode = 0;
  var statusMessage = "";
  var chunkIndex = 0;
  var endOfStream = false;
  var endOfMessage = false;
  var permits = 0;
  var maxInflightFrames = 0;
  var maxFrameBytes = 0;
  while (!r.isEOF) {
    final tag = r.readTag();
    switch (tag.fieldNumber) {
      case 2:
        frameType = r.readVarint();
        break;
      case 3:
        framePayload = r.readBytes();
        break;
      case 4:
        final statusBytes = r.readBytes();
        final s = ProtoReader(statusBytes);
        while (!s.isEOF) {
          final st = s.readTag();
          switch (st.fieldNumber) {
            case 1:
              statusCode = s.readVarint();
              break;
            case 2:
              statusMessage = s.readString();
              break;
            default:
              s.skipField(st.wireType);
              break;
          }
        }
        break;
      case 5:
        chunkIndex = r.readVarint();
        break;
      case 6:
        endOfStream = r.readVarint() != 0;
        break;
      case 7:
        final fcBytes = r.readBytes();
        final fc = ProtoReader(fcBytes);
        while (!fc.isEOF) {
          final fct = fc.readTag();
          switch (fct.fieldNumber) {
            case 1:
              permits = fc.readVarint();
              break;
            case 2:
              maxInflightFrames = fc.readVarint();
              break;
            case 3:
              maxFrameBytes = fc.readVarint();
              break;
            default:
              fc.skipField(fct.wireType);
              break;
          }
        }
        break;
      case 8:
        endOfMessage = r.readVarint() != 0;
        break;
      default:
        r.skipField(tag.wireType);
        break;
    }
  }
  return RpcFrameLite(
    frameType: frameType,
    statusCode: statusCode,
    statusMessage: statusMessage,
    payload: framePayload,
    chunkIndex: chunkIndex,
    endOfStream: endOfStream,
    endOfMessage: endOfMessage,
    permits: permits,
    maxInflightFrames: maxInflightFrames,
    maxFrameBytes: maxFrameBytes,
  );
}

Uint8List encodeHealthCheckRequest(String service) {
  final w = ProtoWriter();
  if (service.isNotEmpty) w.writeString(1, service);
  return w.takeBytes();
}

({bool healthy, bool ready, String message}) decodeHealthCheckResponse(Uint8List payload) {
  final r = ProtoReader(payload);
  var healthy = false;
  var ready = false;
  var message = "";
  while (!r.isEOF) {
    final tag = r.readTag();
    switch (tag.fieldNumber) {
      case 1:
        healthy = r.readVarint() != 0;
        break;
      case 2:
        ready = r.readVarint() != 0;
        break;
      case 3:
        message = r.readString();
        break;
      default:
        r.skipField(tag.wireType);
        break;
    }
  }
  return (healthy: healthy, ready: ready, message: message);
}

Uint8List encodeDiscoverRequest({required String service, required bool includeMethods}) {
  final w = ProtoWriter();
  if (service.isNotEmpty) w.writeString(1, service);
  w.writeBool(2, includeMethods);
  return w.takeBytes();
}

class DiscoverServiceLite {
  final String service;
  final String version;
  final List<DiscoverMethodLite> methods;

  DiscoverServiceLite({required this.service, required this.version, required this.methods});
}

class DiscoverMethodLite {
  final String method;
  final String inputType;
  final String outputType;
  final int callType;
  final bool idempotent;

  DiscoverMethodLite({
    required this.method,
    required this.inputType,
    required this.outputType,
    required this.callType,
    required this.idempotent,
  });
}

List<DiscoverServiceLite> decodeDiscoverResponse(Uint8List payload) {
  final r = ProtoReader(payload);
  final services = <DiscoverServiceLite>[];
  while (!r.isEOF) {
    final tag = r.readTag();
    if (tag.fieldNumber == 1 && tag.wireType == 2) {
      final svcBytes = r.readBytes();
      services.add(_decodeServiceDescriptor(svcBytes));
    } else {
      r.skipField(tag.wireType);
    }
  }
  return services;
}

DiscoverServiceLite _decodeServiceDescriptor(Uint8List payload) {
  final r = ProtoReader(payload);
  var service = "";
  var version = "";
  final methods = <DiscoverMethodLite>[];
  while (!r.isEOF) {
    final tag = r.readTag();
    switch (tag.fieldNumber) {
      case 1:
        service = r.readString();
        break;
      case 2:
        version = r.readString();
        break;
      case 3:
        if (tag.wireType == 2) {
          methods.add(_decodeMethodDescriptor(r.readBytes()));
        } else {
          r.skipField(tag.wireType);
        }
        break;
      default:
        r.skipField(tag.wireType);
        break;
    }
  }
  return DiscoverServiceLite(service: service, version: version, methods: methods);
}

DiscoverMethodLite _decodeMethodDescriptor(Uint8List payload) {
  final r = ProtoReader(payload);
  var method = "";
  var inputType = "";
  var outputType = "";
  var callType = 0;
  var idempotent = false;
  while (!r.isEOF) {
    final tag = r.readTag();
    switch (tag.fieldNumber) {
      case 1:
        method = r.readString();
        break;
      case 2:
        inputType = r.readString();
        break;
      case 3:
        outputType = r.readString();
        break;
      case 4:
        callType = r.readVarint();
        break;
      case 5:
        idempotent = r.readVarint() != 0;
        break;
      default:
        r.skipField(tag.wireType);
        break;
    }
  }
  return DiscoverMethodLite(
    method: method,
    inputType: inputType,
    outputType: outputType,
    callType: callType,
    idempotent: idempotent,
  );
}

Uint8List encodeEchoRequest(String message) {
  final w = ProtoWriter();
  if (message.isNotEmpty) w.writeString(1, message);
  return w.takeBytes();
}

({String message, int serverTime}) decodeEchoResponse(Uint8List payload) {
  final r = ProtoReader(payload);
  var message = "";
  var serverTime = 0;
  while (!r.isEOF) {
    final tag = r.readTag();
    switch (tag.fieldNumber) {
      case 1:
        message = r.readString();
        break;
      case 2:
        serverTime = r.readVarint64();
        break;
      default:
        r.skipField(tag.wireType);
        break;
    }
  }
  return (message: message, serverTime: serverTime);
}
