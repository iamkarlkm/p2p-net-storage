import "dart:io";
import "dart:math";
import "dart:typed_data";

import "package:convert/convert.dart";
import "package:crypto/crypto.dart" as crypto;

import "commands.dart";
import "crypto.dart";
import "frame.dart";
import "handshake.dart";
import "keyfile.dart";
import "messages/control.dart";
import "messages/control_plane.dart";
import "messages/data.dart";
import "messages/im.dart";
import "messages/wrapper.dart";
import "shared_storage.dart";
import "xor.dart";

class P2PServerConfig {
  final int listenPort;
  final String wsPath;
  final int magic;
  final int version;
  final int flagsPlain;
  final int flagsEncrypted;
  final int maxFramePayload;

  const P2PServerConfig({
    required this.listenPort,
    required this.wsPath,
    required this.magic,
    required this.version,
    required this.flagsPlain,
    required this.flagsEncrypted,
    required this.maxFramePayload,
  });
}

class P2PServer {
  final P2PServerConfig config;
  final String keyfilePath;
  final SharedStorageRegistry storage;
  final Uint8List serverNodeKey;

  HttpServer? _http;

  P2PServer({
    required this.config,
    required this.keyfilePath,
    required this.storage,
    required this.serverNodeKey,
  });

  Future<void> start() async {
    final http = await HttpServer.bind(InternetAddress.anyIPv4, config.listenPort);
    _http = http;
    http.listen(_handleHttp);
  }

  Future<void> stop() async {
    final http = _http;
    _http = null;
    await http?.close(force: true);
  }

  Future<void> _handleHttp(HttpRequest req) async {
    if (!WebSocketTransformer.isUpgradeRequest(req)) {
      req.response.statusCode = HttpStatus.badRequest;
      await req.response.close();
      return;
    }
    final ws = await WebSocketTransformer.upgrade(req);
    final keyf = await KeyFileReader.open(keyfilePath);
    final s = _InboundSession(ws, config, keyf, serverNodeKey, storage);
    await s.run();
  }
}

class _InboundSession {
  static final Map<String, _InboundSession> _onlineUsers = {};
  static final Map<String, List<IMChatModel>> _chatHistory = {};
  static final Map<String, String> _msgSenderIndex = {};
  static final Map<String, String> _msgStatusIndex = {};
  static final Map<String, IMGroupModel> _groups = {};
  static final Map<String, Set<String>> _groupMembers = {};
  static final Map<String, List<IMChatModel>> _groupHistory = {};
  static final Map<String, Set<String>> _groupAdmins = {};

  static const int _imStorePublicU32 = 0xFFFFFFFF;
  static const int _imStoreGroupU32 = 0xFFFFFFFE;
  static const int _imStorePrivateU32 = 0xFFFFFFFD;

  final WebSocket _ws;
  final P2PServerConfig _cfg;
  final KeyFileReader _keyf;
  final Uint8List _serverNodeKey;
  final SharedStorageRegistry _storage;

  int? _offset;
  String? _userId;
  IMUserModel? _userModel;

  late final Map<int, Future<void> Function(P2PWrapper)> _handlers = _buildHandlers();

  _InboundSession(this._ws, this._cfg, this._keyf, this._serverNodeKey, this._storage);

  Future<void> run() async {
    try {
      await for (final msg in _ws) {
        if (msg is! List<int>) continue;
        final f = decodeFrame(Uint8List.fromList(msg));
        final cipherPayload = f.cipherPayload;
        final off = _offset;
        Uint8List plainPayload;
        if (off == null) {
          plainPayload = Uint8List.fromList(cipherPayload);
        } else {
          final slice = await _keyf.readSlice(off, cipherPayload.length);
          plainPayload = xorNoWrap(Uint8List.fromList(cipherPayload), slice, 0);
        }
        final w = decodeWrapper(plainPayload);

        if (_offset == null) {
          if (w.command != P2PCommand.hand) {
            await _ws.close();
            return;
          }
          await _handleHand(w);
          continue;
        }

        final h = _handlers[w.command];
        if (h != null) {
          await h(w);
          continue;
        }
      }
    } finally {
      final uid = _userId;
      if (uid != null) {
        final cur = _onlineUsers[uid];
        if (identical(cur, this)) {
          _onlineUsers.remove(uid);
        }
      }
      _userId = null;
      _userModel = null;
      await _keyf.close();
    }
  }

  Map<int, Future<void> Function(P2PWrapper)> _buildHandlers() {
    return {
      P2PCommand.cryptUpdate: _handleCryptUpdate,
      P2PCommand.peerHello: _handlePeerHello,

      P2PCommand.imUserLogin: _handleImUserLogin,
      P2PCommand.imUserLogout: _handleImUserLogout,
      P2PCommand.imUserList: _handleImUserList,
      P2PCommand.imChatSend: _handleImChatSend,
      P2PCommand.imChatAck: _handleImChatAck,
      P2PCommand.imChatStatusUpdate: _handleImChatStatusUpdate,
      P2PCommand.imChatHistoryRequest: _handleImChatHistoryRequest,

      P2PCommand.imGroupCreate: _handleImGroupCreate,
      P2PCommand.imGroupDismiss: _handleImGroupDismiss,
      P2PCommand.imGroupJoin: _handleImGroupJoin,
      P2PCommand.imGroupLeave: _handleImGroupLeave,
      P2PCommand.imGroupList: _handleImGroupList,
      P2PCommand.imGroupMembers: _handleImGroupMembers,
      P2PCommand.imGroupMessageSend: _handleImGroupMessageSend,
      P2PCommand.imGroupSetAdmin: _handleImGroupSetAdmin,
      P2PCommand.imGroupRemoveMember: _handleImGroupRemoveMember,
      P2PCommand.imGroupUpdateInfo: _handleImGroupUpdateInfo,

      P2PCommand.putFile: _handlePutFile,
      P2PCommand.forcePutFile: _handlePutFile,
      P2PCommand.getFile: _handleGetFile,

      P2PCommand.getFileSegments: _handleGetFileSegments,
      P2PCommand.putFileSegments: _handlePutFileSegments,
      P2PCommand.putFileSegmentsComplete: _handlePutFileSegmentsComplete,

      P2PCommand.checkFile: _handleCheckFile,
      P2PCommand.infoFile: _handleInfoFile,
      P2PCommand.fileRename: _handleFileRename,
      P2PCommand.fileList: _handleFileList,
      P2PCommand.fileExists: _handleFileExists,
      P2PCommand.fileMkdirs: _handleFileMkdirs,

      P2PCommand.filesCommand: _handleDeprecated,
      P2PCommand.filePutReq: _handleDeprecated,
      P2PCommand.fileGetReq: _handleDeprecated,
    };
  }

  static bool _isOwner(IMGroupModel g, String operatorId) => g.ownerId == operatorId;

  static bool _isAdmin(String groupId, String userId) => _groupAdmins[groupId]?.contains(userId) ?? false;

  static bool _isOwnerOrAdmin(IMGroupModel g, String userId) => _isOwner(g, userId) || _isAdmin(g.groupId, userId);

  Future<void> _pushSystemEventToUser(String userId, IMSystemEvent e) async {
    final s = _onlineUsers[userId];
    if (s == null) return;
    await s._sendEncrypted(P2PWrapper(seq: 0, command: P2PCommand.imSystemStatus, data: encodeIMSystemEvent(e)));
  }

  static String _pairKey(String a, String b) => (a.compareTo(b) <= 0) ? "$a|$b" : "$b|$a";

  static int _nowMs() => DateTime.now().millisecondsSinceEpoch;

  static String _newMsgId() => "${_nowMs()}_${Random.secure().nextInt(1 << 30)}";

  Future<void> _handleImUserLogin(P2PWrapper w) async {
    IMUserModel user;
    try {
      user = decodeIMUserModel(w.data);
    } catch (e) {
      final err = encodeStdError(StdError("invalid user payload: $e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    if (user.userId.isEmpty) {
      final err = encodeStdError(const StdError("user_id required"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    _userId = user.userId;
    _onlineUsers[user.userId] = this;
    final out = IMUserModel(
      userId: user.userId,
      username: user.username,
      nickname: user.nickname,
      token: user.token,
      status: user.status.isEmpty ? "ONLINE" : user.status,
      ip: user.ip,
      port: user.port,
      publicKey: user.publicKey,
      avatar: user.avatar,
      signature: user.signature,
      extra: user.extra,
    );
    _userModel = out;
    await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: encodeIMUserModel(out)));
  }

  Future<void> _handleImUserLogout(P2PWrapper w) async {
    final uid = _userId;
    if (uid != null) {
      final cur = _onlineUsers[uid];
      if (identical(cur, this)) {
        _onlineUsers.remove(uid);
      }
      _userId = null;
    }
    _userModel = null;
    await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
  }

  Future<void> _handleImUserList(P2PWrapper w) async {
    final items = <IMUserModel>[];
    for (final s in _onlineUsers.values) {
      final m = s._userModel;
      if (m != null) {
        items.add(m);
      }
    }
    final out = encodeIMUserListResponse(IMUserListResponse(items));
    await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: out));
  }

  Future<void> _handleImChatSend(P2PWrapper w) async {
    IMChatModel msg;
    try {
      msg = decodeIMChatModel(w.data);
    } catch (e) {
      final err = encodeStdError(StdError("invalid chat payload: $e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    if (msg.senderId.isEmpty || msg.receiverId.isEmpty) {
      final err = encodeStdError(const StdError("sender_id and receiver_id required"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }

    final msgId = msg.msgId.isEmpty ? _newMsgId() : msg.msgId;
    final ts = msg.timestamp <= 0 ? _nowMs() : msg.timestamp;
    final normalized = IMChatModel(
      msgId: msgId,
      senderId: msg.senderId,
      receiverId: msg.receiverId,
      receiverType: msg.receiverType,
      msgType: msg.msgType,
      content: msg.content,
      timestamp: ts,
      extra: msg.extra,
      quoteMsgId: msg.quoteMsgId,
      atUsers: msg.atUsers,
      fileInfo: msg.fileInfo,
    );

    IMChatModel finalMsg;
    try {
      finalMsg = await _persistImFileIfNeeded(normalized, groupId: null);
    } catch (e) {
      final err = encodeStdError(StdError("im file store failed: $e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }

    final key = _pairKey(finalMsg.senderId, finalMsg.receiverId);
    final list = (_chatHistory[key] ??= <IMChatModel>[]);
    list.add(finalMsg);
    if (list.length > 200) {
      list.removeRange(0, list.length - 200);
    }
    _msgSenderIndex[msgId] = finalMsg.senderId;
    _msgStatusIndex[msgId] = "DELIVERED";

    final receiver = _onlineUsers[finalMsg.receiverId];
    if (receiver != null) {
      await receiver._sendEncrypted(P2PWrapper(seq: 0, command: P2PCommand.imChatReceive, data: encodeIMChatModel(finalMsg)));
    }
    final ack = IMChatAck(
      msgId: msgId,
      userId: finalMsg.senderId,
      timestamp: _nowMs(),
      ackType: "DELIVERED",
      peerId: finalMsg.receiverId,
    );
    await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: encodeIMChatAck(ack)));
  }

  Future<void> _handleImChatAck(P2PWrapper w) async {
    IMChatAck ack;
    try {
      ack = decodeIMChatAck(w.data);
    } catch (e) {
      final err = encodeStdError(StdError("invalid ack payload: $e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    if (ack.msgId.isEmpty) {
      final err = encodeStdError(const StdError("msg_id required"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    final senderId = _msgSenderIndex[ack.msgId];
    if (senderId != null) {
      final sender = _onlineUsers[senderId];
      if (sender != null) {
        await sender._sendEncrypted(P2PWrapper(seq: 0, command: P2PCommand.imChatAck, data: encodeIMChatAck(ack)));
      }
    }
    await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
  }

  Future<void> _handleImChatStatusUpdate(P2PWrapper w) async {
    IMChatAck ack;
    try {
      ack = decodeIMChatAck(w.data);
    } catch (e) {
      final err = encodeStdError(StdError("invalid status payload: $e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    if (ack.msgId.isEmpty) {
      final err = encodeStdError(const StdError("msg_id required"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    final senderId = _msgSenderIndex[ack.msgId];
    if (senderId != null) {
      final sender = _onlineUsers[senderId];
      if (sender != null) {
        await sender._sendEncrypted(P2PWrapper(seq: 0, command: P2PCommand.imChatStatusUpdate, data: encodeIMChatAck(ack)));
      }
    }
    if (ack.ackType.isNotEmpty) {
      _msgStatusIndex[ack.msgId] = ack.ackType;
    }
    await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
  }

  Future<void> _handleImChatHistoryRequest(P2PWrapper w) async {
    IMChatHistoryRequest q;
    try {
      q = decodeIMChatHistoryRequest(w.data);
    } catch (e) {
      final err = encodeStdError(StdError("invalid history payload: $e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    if (q.userId.isEmpty || q.peerId.isEmpty) {
      final err = encodeStdError(const StdError("user_id and peer_id required"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    final peerId = q.peerId;
    final groupList = _groupHistory[peerId];
    final list = groupList ?? (_chatHistory[_pairKey(q.userId, peerId)] ?? const <IMChatModel>[]);
    final limit = q.limit <= 0 ? 50 : q.limit;
    final start = list.length > limit ? (list.length - limit) : 0;
    final items = list.sublist(start).map(_applyStatusToChat).toList(growable: false);
    await _sendEncrypted(
      P2PWrapper(
        seq: w.seq,
        command: P2PCommand.imChatHistoryResponse,
        data: encodeIMChatHistoryResponse(IMChatHistoryResponse(items)),
      ),
    );
  }

  static IMChatModel _applyStatusToChat(IMChatModel m) {
    final status = _msgStatusIndex[m.msgId];
    if (status == null || status.isEmpty) return m;
    final extra = _mergeStatusExtra(m.extra, status);
    if (extra == m.extra) return m;
    return IMChatModel(
      msgId: m.msgId,
      senderId: m.senderId,
      receiverId: m.receiverId,
      receiverType: m.receiverType,
      msgType: m.msgType,
      content: m.content,
      timestamp: m.timestamp,
      extra: extra,
      quoteMsgId: m.quoteMsgId,
      atUsers: m.atUsers,
      fileInfo: m.fileInfo,
    );
  }

  static String _mergeStatusExtra(String extra, String status) {
    const key = "|status:";
    final idx = extra.indexOf(key);
    if (idx < 0) return "$extra$key$status";
    final end = extra.indexOf("|", idx + 1);
    if (end < 0) return "${extra.substring(0, idx)}$key$status";
    return "${extra.substring(0, idx)}$key$status${extra.substring(end)}";
  }

  Future<void> _handleImGroupCreate(P2PWrapper w) async {
    IMGroupModel inGroup;
    try {
      inGroup = decodeIMGroupModel(w.data);
    } catch (e) {
      final err = encodeStdError(StdError("invalid group payload: $e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    final ownerId = _userId ?? inGroup.ownerId;
    if (ownerId.isEmpty) {
      final err = encodeStdError(const StdError("owner_id required"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    final groupId = inGroup.groupId.isEmpty ? "g_${_newMsgId()}" : inGroup.groupId;
    final out = IMGroupModel(
      groupId: groupId,
      name: inGroup.name,
      ownerId: ownerId,
      avatar: inGroup.avatar,
      notice: inGroup.notice,
      extra: inGroup.extra,
      adminIds: const <String>[],
    );
    _groups[groupId] = out;
    (_groupMembers[groupId] ??= <String>{}).add(ownerId);
    _groupAdmins[groupId] = <String>{};
    await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: encodeIMGroupModel(out)));
  }

  Future<void> _handleImGroupDismiss(P2PWrapper w) async {
    IMGroupDismissRequest r;
    try {
      r = decodeIMGroupDismissRequest(w.data);
    } catch (e) {
      final err = encodeStdError(StdError("invalid group dismiss payload: $e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    if (r.groupId.isEmpty || r.operatorId.isEmpty) {
      final err = encodeStdError(const StdError("group_id and operator_id required"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    final g = _groups[r.groupId];
    if (g == null) {
      final err = encodeStdError(const StdError("group not found"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
      return;
    }
    if (!_isOwner(g, r.operatorId)) {
      final err = encodeStdError(const StdError("permission denied"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
      return;
    }
    final members = _groupMembers[r.groupId] ?? const <String>{};
    final e = IMSystemEvent(
      type: "GROUP_DISMISSED",
      groupId: r.groupId,
      operatorId: r.operatorId,
      targetId: "",
      timestamp: _nowMs(),
      message: "group dismissed",
    );
    _groups.remove(r.groupId);
    _groupMembers.remove(r.groupId);
    _groupHistory.remove(r.groupId);
    _groupAdmins.remove(r.groupId);
    for (final uid in members) {
      await _pushSystemEventToUser(uid, e);
    }
    await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
  }

  Future<void> _handleImGroupJoin(P2PWrapper w) async {
    final uid = _userId;
    if (uid == null || uid.isEmpty) {
      final err = encodeStdError(const StdError("login required"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
      return;
    }
    IMGroupModel g;
    try {
      g = decodeIMGroupModel(w.data);
    } catch (e) {
      final err = encodeStdError(StdError("invalid group payload: $e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    if (g.groupId.isEmpty) {
      final err = encodeStdError(const StdError("group_id required"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    if (_groups[g.groupId] == null) {
      final err = encodeStdError(const StdError("group not found"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
      return;
    }
    (_groupMembers[g.groupId] ??= <String>{}).add(uid);
    final e = IMSystemEvent(
      type: "GROUP_MEMBER_JOINED",
      groupId: g.groupId,
      operatorId: uid,
      targetId: uid,
      timestamp: _nowMs(),
      message: "joined group",
    );
    final members = _groupMembers[g.groupId] ?? const <String>{};
    for (final m in members) {
      await _pushSystemEventToUser(m, e);
    }
    await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
  }

  Future<void> _handleImGroupLeave(P2PWrapper w) async {
    final uid = _userId;
    if (uid == null || uid.isEmpty) {
      final err = encodeStdError(const StdError("login required"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
      return;
    }
    IMGroupModel g;
    try {
      g = decodeIMGroupModel(w.data);
    } catch (e) {
      final err = encodeStdError(StdError("invalid group payload: $e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    if (g.groupId.isEmpty) {
      final err = encodeStdError(const StdError("group_id required"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    _groupMembers[g.groupId]?.remove(uid);
    _groupAdmins[g.groupId]?.remove(uid);
    final e = IMSystemEvent(
      type: "GROUP_MEMBER_LEFT",
      groupId: g.groupId,
      operatorId: uid,
      targetId: uid,
      timestamp: _nowMs(),
      message: "left group",
    );
    final members = _groupMembers[g.groupId] ?? const <String>{};
    for (final m in members) {
      await _pushSystemEventToUser(m, e);
    }
    await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
  }

  Future<void> _handleImGroupList(P2PWrapper w) async {
    final uid = _userId;
    if (uid == null || uid.isEmpty) {
      final out = encodeIMGroupListResponse(IMGroupListResponse(_groups.values.toList(growable: false)));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: out));
      return;
    }
    final items = <IMGroupModel>[];
    for (final g in _groups.values) {
      final members = _groupMembers[g.groupId];
      if (members == null || !members.contains(uid)) continue;
      final admins = _groupAdmins[g.groupId] ?? const <String>{};
      items.add(IMGroupModel(
        groupId: g.groupId,
        name: g.name,
        ownerId: g.ownerId,
        avatar: g.avatar,
        notice: g.notice,
        extra: g.extra,
        adminIds: admins.toList(growable: false),
      ));
    }
    final out = encodeIMGroupListResponse(IMGroupListResponse(items));
    await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: out));
  }

  Future<void> _handleImGroupMembers(P2PWrapper w) async {
    IMGroupModel g;
    try {
      g = decodeIMGroupModel(w.data);
    } catch (e) {
      final err = encodeStdError(StdError("invalid group payload: $e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    if (g.groupId.isEmpty) {
      final err = encodeStdError(const StdError("group_id required"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    final members = _groupMembers[g.groupId] ?? const <String>{};
    final items = <IMUserModel>[];
    for (final uid in members) {
      final s = _onlineUsers[uid];
      final m = s?._userModel;
      if (m != null) {
        items.add(m);
      }
    }
    final out = encodeIMGroupMembersResponse(IMGroupMembersResponse(groupId: g.groupId, items: items));
    await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: out));
  }

  Future<void> _handleImGroupMessageSend(P2PWrapper w) async {
    IMChatModel msg;
    try {
      msg = decodeIMChatModel(w.data);
    } catch (e) {
      final err = encodeStdError(StdError("invalid group chat payload: $e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    if (msg.senderId.isEmpty || msg.receiverId.isEmpty) {
      final err = encodeStdError(const StdError("sender_id and receiver_id(group_id) required"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    final groupId = msg.receiverId;
    if (_groups[groupId] == null) {
      final err = encodeStdError(const StdError("group not found"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
      return;
    }
    final msgId = msg.msgId.isEmpty ? _newMsgId() : msg.msgId;
    final ts = msg.timestamp <= 0 ? _nowMs() : msg.timestamp;
    final normalized = IMChatModel(
      msgId: msgId,
      senderId: msg.senderId,
      receiverId: groupId,
      receiverType: msg.receiverType.isEmpty ? "GROUP" : msg.receiverType,
      msgType: msg.msgType,
      content: msg.content,
      timestamp: ts,
      extra: msg.extra,
      quoteMsgId: msg.quoteMsgId,
      atUsers: msg.atUsers,
      fileInfo: msg.fileInfo,
    );

    IMChatModel finalMsg;
    try {
      finalMsg = await _persistImFileIfNeeded(normalized, groupId: groupId);
    } catch (e) {
      final err = encodeStdError(StdError("im file store failed: $e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    final list = (_groupHistory[groupId] ??= <IMChatModel>[]);
    list.add(finalMsg);
    if (list.length > 200) {
      list.removeRange(0, list.length - 200);
    }
    _msgSenderIndex[msgId] = finalMsg.senderId;
    _msgStatusIndex[msgId] = "DELIVERED";

    final members = _groupMembers[groupId] ?? const <String>{};
    for (final uid in members) {
      if (uid == finalMsg.senderId) continue;
      final s = _onlineUsers[uid];
      if (s == null) continue;
      await s._sendEncrypted(P2PWrapper(seq: 0, command: P2PCommand.imGroupMessageReceive, data: encodeIMChatModel(finalMsg)));
    }

    final ack = IMChatAck(
      msgId: msgId,
      userId: finalMsg.senderId,
      timestamp: _nowMs(),
      ackType: "DELIVERED",
      peerId: groupId,
    );
    await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: encodeIMChatAck(ack)));
  }

  Future<IMChatModel> _persistImFileIfNeeded(IMChatModel msg, {required String? groupId}) async {
    final fi = msg.fileInfo;
    if (fi == null) return msg;
    if (fi.data.isEmpty) return msg;

    final receiverType = msg.receiverType.trim();
    final defaultStoreId = receiverType == "GROUP" ? _imStoreGroupU32 : _imStorePrivateU32;
    final storeId = fi.storeId == 0 ? defaultStoreId : (fi.storeId & 0xFFFFFFFF);

    String prefix;
    if (storeId == _imStorePublicU32) {
      prefix = msg.msgId;
    } else if (storeId == _imStoreGroupU32) {
      final gid = groupId ?? "";
      if (gid.isEmpty) {
        throw StateError("group_id required for IM group storage");
      }
      prefix = "$gid/${msg.msgId}";
    } else if (storeId == _imStorePrivateU32) {
      prefix = "${msg.senderId}/${msg.msgId}";
    } else {
      prefix = msg.msgId;
    }

    final name = _safeBaseName(fi.path);
    final relPath = "$prefix/$name";
    final file = _storage.getSandboxFileForWrite(storeId, relPath);
    await file.parent.create(recursive: true);
    await file.writeAsBytes(fi.data, flush: true);

    final md5 = fi.md5.isNotEmpty ? fi.md5 : crypto.md5.convert(fi.data).toString();
    final outFi = FileDataModel(storeId: storeId, length: fi.data.length, data: Uint8List(0), path: relPath, md5: md5, blockSize: fi.blockSize);
    return IMChatModel(
      msgId: msg.msgId,
      senderId: msg.senderId,
      receiverId: msg.receiverId,
      receiverType: msg.receiverType,
      msgType: msg.msgType,
      content: msg.content,
      timestamp: msg.timestamp,
      extra: msg.extra,
      quoteMsgId: msg.quoteMsgId,
      atUsers: msg.atUsers,
      fileInfo: outFi,
    );
  }

  static String _safeBaseName(String raw) {
    final s = raw.trim();
    if (s.isEmpty) return "file.bin";
    final parts = s.replaceAll("\\", "/").split("/");
    for (var i = parts.length - 1; i >= 0; i--) {
      final p = parts[i].trim();
      if (p.isEmpty) continue;
      if (p == "." || p == "..") break;
      return p;
    }
    return "file.bin";
  }

  Future<void> _handleImGroupRemoveMember(P2PWrapper w) async {
    IMGroupRemoveMemberRequest r;
    try {
      r = decodeIMGroupRemoveMemberRequest(w.data);
    } catch (e) {
      final err = encodeStdError(StdError("invalid remove_member payload: $e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    if (r.groupId.isEmpty || r.operatorId.isEmpty || r.memberId.isEmpty) {
      final err = encodeStdError(const StdError("group_id/operator_id/member_id required"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    final g = _groups[r.groupId];
    if (g == null) {
      final err = encodeStdError(const StdError("group not found"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
      return;
    }
    if (!_isOwnerOrAdmin(g, r.operatorId)) {
      final err = encodeStdError(const StdError("permission denied"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
      return;
    }
    _groupMembers[r.groupId]?.remove(r.memberId);
    _groupAdmins[r.groupId]?.remove(r.memberId);
    final e = IMSystemEvent(
      type: "GROUP_MEMBER_REMOVED",
      groupId: r.groupId,
      operatorId: r.operatorId,
      targetId: r.memberId,
      timestamp: _nowMs(),
      message: "removed from group",
    );
    final members = _groupMembers[r.groupId] ?? const <String>{};
    for (final uid in members) {
      await _pushSystemEventToUser(uid, e);
    }
    await _pushSystemEventToUser(r.memberId, e);
    await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
  }

  Future<void> _handleImGroupUpdateInfo(P2PWrapper w) async {
    IMGroupUpdateInfoRequest r;
    try {
      r = decodeIMGroupUpdateInfoRequest(w.data);
    } catch (e) {
      final err = encodeStdError(StdError("invalid update_info payload: $e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    if (r.groupId.isEmpty || r.operatorId.isEmpty) {
      final err = encodeStdError(const StdError("group_id and operator_id required"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    final g = _groups[r.groupId];
    if (g == null) {
      final err = encodeStdError(const StdError("group not found"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
      return;
    }
    if (!_isOwnerOrAdmin(g, r.operatorId)) {
      final err = encodeStdError(const StdError("permission denied"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
      return;
    }
    final admins = _groupAdmins[r.groupId] ?? const <String>{};
    final out = IMGroupModel(
      groupId: g.groupId,
      name: r.name.isEmpty ? g.name : r.name,
      ownerId: g.ownerId,
      avatar: r.avatar.isEmpty ? g.avatar : r.avatar,
      notice: r.notice.isEmpty ? g.notice : r.notice,
      extra: r.extra.isEmpty ? g.extra : r.extra,
      adminIds: admins.toList(growable: false),
    );
    _groups[r.groupId] = out;
    final e = IMSystemEvent(
      type: "GROUP_INFO_UPDATED",
      groupId: r.groupId,
      operatorId: r.operatorId,
      targetId: "",
      timestamp: _nowMs(),
      message: "group info updated",
    );
    final members = _groupMembers[r.groupId] ?? const <String>{};
    for (final uid in members) {
      await _pushSystemEventToUser(uid, e);
    }
    await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: encodeIMGroupModel(out)));
  }

  Future<void> _handleImGroupSetAdmin(P2PWrapper w) async {
    IMGroupSetAdminRequest r;
    try {
      r = decodeIMGroupSetAdminRequest(w.data);
    } catch (e) {
      final err = encodeStdError(StdError("invalid set_admin payload: $e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    if (r.groupId.isEmpty || r.operatorId.isEmpty || r.memberId.isEmpty) {
      final err = encodeStdError(const StdError("group_id/operator_id/member_id required"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
      return;
    }
    final g = _groups[r.groupId];
    if (g == null) {
      final err = encodeStdError(const StdError("group not found"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
      return;
    }
    if (!_isOwner(g, r.operatorId)) {
      final err = encodeStdError(const StdError("permission denied"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
      return;
    }
    final members = _groupMembers[r.groupId] ?? const <String>{};
    if (!members.contains(r.memberId)) {
      final err = encodeStdError(const StdError("member not in group"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
      return;
    }
    final admins = (_groupAdmins[r.groupId] ??= <String>{});
    if (r.isAdmin) {
      admins.add(r.memberId);
    } else {
      admins.remove(r.memberId);
    }
    final out = IMGroupModel(
      groupId: g.groupId,
      name: g.name,
      ownerId: g.ownerId,
      avatar: g.avatar,
      notice: g.notice,
      extra: g.extra,
      adminIds: admins.toList(growable: false),
    );
    _groups[r.groupId] = out;
    final e = IMSystemEvent(
      type: "GROUP_ROLE_CHANGED",
      groupId: r.groupId,
      operatorId: r.operatorId,
      targetId: r.memberId,
      timestamp: _nowMs(),
      message: r.isAdmin ? "set admin" : "unset admin",
    );
    for (final uid in members) {
      await _pushSystemEventToUser(uid, e);
    }
    await _pushSystemEventToUser(r.memberId, e);
    await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: encodeIMGroupModel(out)));
  }

  Future<void> _handleHand(P2PWrapper w) async {
    final hand = decodeHand(w.data);
    final selected = _keyf.keyId;
    final maxPayload = _cfg.maxFramePayload;
    final seed = Random.secure();
    final sessionId = Uint8List(16);
    for (var i = 0; i < sessionId.length; i++) {
      sessionId[i] = seed.nextInt(256);
    }
    final offset = seed.nextInt(1024);
    final ack = HandAckPlain(
      sessionId: sessionId,
      selectedKeyId: selected,
      offset: offset,
      maxFramePayload: maxPayload,
      headerPolicyId: 0,
    );
    final ackBytes = encodeHandAckPlain(ack);
    final pub = rsaPublicKeyFromSpkiDer(hand.clientPubkeySpkiDer);
    final encrypted = rsaOaepSha256Encrypt(pub, ackBytes);
    final out = encodeWrapper(P2PWrapper(seq: w.seq, command: P2PCommand.handAck, data: encrypted));
    final frame = encodeFrame(WireHeader(out.length, _cfg.magic, _cfg.version, _cfg.flagsPlain), out);
    _ws.add(frame);
    _offset = offset;
  }

  Future<void> _handleCryptUpdate(P2PWrapper w) async {
    final cu = decodeCryptUpdate(w.data);
    _offset = cu.offset;
  }

  Future<void> _handlePeerHello(P2PWrapper w) async {
    decodePeerHello(w.data);
    final ack = PeerHelloAck(nodeKey: _serverNodeKey, serverTimeMs: nowMs());
    final ackBytes = encodePeerHelloAck(ack);
    await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.peerHelloAck, data: ackBytes));
  }

  Future<void> _handleDeprecated(P2PWrapper w) async {
    final err = encodeStdError(const StdError("deprecated command"));
    await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
  }

  Future<void> _handlePutFile(P2PWrapper w) async {
    final m = decodeFileDataModel(w.data);
    try {
      final file = _storage.getSandboxFileForWrite(m.storeId, m.path);
      if (w.command == P2PCommand.putFile && await file.exists()) {
        final err = encodeStdError(const StdError("file exists"));
        await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
        return;
      }
      await file.writeAsBytes(m.data);
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
    } catch (e) {
      final err = encodeStdError(StdError("$e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    }
  }

  Future<void> _handleGetFile(P2PWrapper w) async {
    final req = decodeFileDataModel(w.data);
    try {
      final file = _storage.getAndCheckExistsSandboxFile(req.storeId, req.path);
      final content = Uint8List.fromList(await file.readAsBytes());
      final resp = FileDataModel(
        storeId: req.storeId,
        length: content.length,
        data: content,
        path: req.path,
        md5: "",
        blockSize: 0,
      );
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.okGetFile, data: encodeFileDataModel(resp)));
    } catch (e) {
      final err = encodeStdError(StdError("$e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    }
  }

  Future<void> _handleGetFileSegments(P2PWrapper w) async {
    final req = decodeFileSegmentsDataModel(w.data);
    try {
      final file = _storage.getAndCheckExistsSandboxFile(req.storeId, req.path);
      final raf = await file.open();
      try {
        await raf.setPosition(req.start);
        final block = await raf.read(req.blockSize);
        final resp = FileSegmentsDataModel(
          storeId: req.storeId,
          length: req.length != 0 ? req.length : file.lengthSync(),
          start: req.start,
          blockIndex: req.blockIndex,
          blockSize: req.blockSize,
          blockData: Uint8List.fromList(block),
          blockMd5: md5Hex(Uint8List.fromList(block)),
          path: req.path,
          md5: req.md5.isEmpty ? await _md5HexFile(file) : req.md5,
        );
        await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.okGetFileSegments, data: encodeFileSegmentsDataModel(resp)));
      } finally {
        await raf.close();
      }
    } catch (e) {
      final err = encodeStdError(StdError("$e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    }
  }

  Future<void> _handlePutFileSegments(P2PWrapper w) async {
    final req = decodeFileSegmentsDataModel(w.data);
    try {
      if (req.blockMd5.isNotEmpty) {
        final got = md5Hex(req.blockData);
        if (got.toLowerCase() != req.blockMd5.toLowerCase()) {
          final err = encodeStdError(StdError("Md5 check error -> ${req.blockMd5}"));
          await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
          return;
        }
      }
      final file = _storage.getSandboxFileForWrite(req.storeId, req.path);
      final raf = await file.open(mode: FileMode.writeOnlyAppend);
      try {
        await raf.setPosition(req.start);
        await raf.writeFrom(req.blockData);
      } finally {
        await raf.close();
      }
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
    } catch (e) {
      final err = encodeStdError(StdError("$e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    }
  }

  Future<void> _handlePutFileSegmentsComplete(P2PWrapper w) async {
    final req = decodeFileSegmentsDataModel(w.data);
    try {
      final file = _storage.getSandboxFileForWrite(req.storeId, req.path);
      final actualLen = file.existsSync() ? file.lengthSync() : 0;
      if (actualLen != req.length) {
        final err = encodeStdError(StdError("文件长度不一致 ${req.length} <> $actualLen"));
        await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
        return;
      }
      if (req.md5.isNotEmpty) {
        final actualMd5 = await _md5HexFile(file);
        if (actualMd5.toLowerCase() != req.md5.toLowerCase()) {
          final err = encodeStdError(StdError("MD5校验错误 ${req.md5} <> $actualMd5"));
          await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
          return;
        }
      }
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
    } catch (e) {
      final err = encodeStdError(StdError("$e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    }
  }

  Future<void> _handleCheckFile(P2PWrapper w) async {
    final req = decodeFileDataModel(w.data);
    try {
      final file = _storage.getAndCheckExistsSandboxFile(req.storeId, req.path);
      final actualLen = file.lengthSync();
      if (actualLen != req.length) {
        final err = encodeStdError(StdError("文件长度不一致 ${req.length} <> $actualLen"));
        await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
        return;
      }
      if (req.md5.isNotEmpty) {
        final actualMd5 = await _md5HexFile(file);
        if (actualMd5 != req.md5) {
          final err = encodeStdError(StdError("MD5校验错误 ${req.md5} <> $actualMd5"));
          await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
          return;
        }
      }
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
    } catch (e) {
      final err = encodeStdError(StdError("$e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    }
  }

  Future<void> _handleInfoFile(P2PWrapper w) async {
    final req = decodeFileDataModel(w.data);
    try {
      final file = _storage.getAndCheckExistsSandboxFile(req.storeId, req.path);
      final len = file.lengthSync();
      final md5hex = req.md5.isEmpty ? await _md5HexFile(file) : req.md5;
      const blockSize = 8 * 1024 * 1024;
      final resp = FileDataModel(
        storeId: req.storeId,
        length: len,
        data: Uint8List(0),
        path: req.path,
        md5: md5hex,
        blockSize: blockSize,
      );
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: encodeFileDataModel(resp)));
    } catch (e) {
      final err = encodeStdError(StdError("$e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    }
  }

  Future<void> _handleFileRename(P2PWrapper w) async {
    final req = decodeFileRenameRequest(w.data);
    try {
      final src = _storage.getSandboxFile(req.storeId, req.srcPath);
      final t = FileSystemEntity.typeSync(src.path, followLinks: false);
      if (t == FileSystemEntityType.notFound) {
        final err = encodeStdError(const StdError("file not found"));
        await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
        return;
      }
      final dst = _storage.getSandboxFileForWrite(req.storeId, req.dstPath);
      src.renameSync(dst.path);
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
    } catch (e) {
      final err = encodeStdError(StdError("$e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    }
  }

  Future<void> _handleFileList(P2PWrapper w) async {
    final req = decodeFileListRequest(w.data);
    var page = req.page <= 0 ? 1 : req.page;
    var pageSize = req.pageSize <= 0 ? 100 : req.pageSize;
    if (pageSize > 1000) pageSize = 1000;
    try {
      final dirFile = _storage.getSandboxFile(req.storeId, req.path);
      final dirType = FileSystemEntity.typeSync(dirFile.path, followLinks: false);
      if (dirType != FileSystemEntityType.directory) {
        final err = encodeStdError(const StdError("not a directory"));
        await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
        return;
      }
      final d = Directory(dirFile.path);
      final entries = d
          .listSync(followLinks: false)
          .where((e) => FileSystemEntity.typeSync(e.path, followLinks: false) != FileSystemEntityType.link)
          .toList(growable: false);
      entries.sort((a, b) => a.path.compareTo(b.path));
      final total = entries.length;
      final start = (page - 1) * pageSize;
      final end = min(start + pageSize, total);
      final items = <FileListEntry>[];
      if (start < total) {
        for (final e in entries.sublist(start, end)) {
          final st = e.statSync();
          final isDir = st.type == FileSystemEntityType.directory;
          final name = e.uri.pathSegments.isNotEmpty ? e.uri.pathSegments.last : e.path;
          final p = req.path.endsWith("/") ? "${req.path}$name" : "${req.path}/$name";
          items.add(FileListEntry(name: name, path: p, isDir: isDir, size: isDir ? 0 : st.size, modifiedMs: st.modified.millisecondsSinceEpoch));
        }
      } else {
        page = 1;
      }
      final resp = FileListResponse(page: page, pageSize: pageSize, total: total, items: items);
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: encodeFileListResponse(resp)));
    } catch (e) {
      final err = encodeStdError(StdError("$e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    }
  }

  Future<void> _handleFileExists(P2PWrapper w) async {
    final req = decodeFileDataModel(w.data);
    try {
      final file = _storage.getSandboxFile(req.storeId, req.path);
      final t = FileSystemEntity.typeSync(file.path, followLinks: false);
      if (t != FileSystemEntityType.notFound) {
        await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
        return;
      }
      final err = encodeStdError(const StdError("not exists"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    } catch (e) {
      final err = encodeStdError(StdError("$e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    }
  }

  Future<void> _handleFileMkdirs(P2PWrapper w) async {
    final req = decodeFileDataModel(w.data);
    try {
      final file = _storage.getSandboxFileForWrite(req.storeId, req.path);
      final d = Directory(file.path);
      d.createSync(recursive: true);
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
    } catch (e) {
      final err = encodeStdError(StdError("$e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    }
  }

  Future<void> _sendEncrypted(P2PWrapper w) async {
    final off = _offset;
    if (off == null) {
      throw StateError("not encrypted yet");
    }
    final plain = encodeWrapper(w);
    final slice = await _keyf.readSlice(off, plain.length);
    final cipher = xorNoWrap(plain, slice, 0);
    final frame = encodeFrame(WireHeader(cipher.length, _cfg.magic, _cfg.version, _cfg.flagsEncrypted), cipher);
    _ws.add(frame);
  }
}

Future<String> _md5HexFile(File file) async {
  final out = AccumulatorSink<crypto.Digest>();
  final input = crypto.md5.startChunkedConversion(out);
  final raf = await file.open();
  try {
    while (true) {
      final chunk = await raf.read(64 * 1024);
      if (chunk.isEmpty) break;
      input.add(chunk);
    }
  } finally {
    await raf.close();
  }
  input.close();
  return out.events.single.toString();
}
