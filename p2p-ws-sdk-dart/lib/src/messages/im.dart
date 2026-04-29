import "dart:typed_data";

import "../proto_lite.dart";
import "./data.dart";

class IMUserModel {
  final String userId;
  final String username;
  final String nickname;
  final String token;
  final String status;
  final String ip;
  final int port;
  final String publicKey;
  final String avatar;
  final String signature;
  final String extra;

  const IMUserModel({
    required this.userId,
    required this.username,
    required this.nickname,
    required this.token,
    required this.status,
    required this.ip,
    required this.port,
    required this.publicKey,
    required this.avatar,
    required this.signature,
    required this.extra,
  });
}

Uint8List encodeIMUserModel(IMUserModel m) {
  final w = ProtoWriter();
  w.writeString(1, m.userId);
  w.writeString(2, m.username);
  w.writeString(3, m.nickname);
  w.writeString(4, m.token);
  w.writeString(5, m.status);
  w.writeString(6, m.ip);
  w.writeUint32(7, m.port);
  w.writeString(8, m.publicKey);
  w.writeString(9, m.avatar);
  w.writeString(10, m.signature);
  w.writeString(11, m.extra);
  return w.takeBytes();
}

IMUserModel decodeIMUserModel(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var userId = "";
  var username = "";
  var nickname = "";
  var token = "";
  var status = "";
  var ip = "";
  var port = 0;
  var publicKey = "";
  var avatar = "";
  var signature = "";
  var extra = "";
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        userId = r.readString();
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        username = r.readString();
        break;
      case 3:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        nickname = r.readString();
        break;
      case 4:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        token = r.readString();
        break;
      case 5:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        status = r.readString();
        break;
      case 6:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        ip = r.readString();
        break;
      case 7:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        port = r.readVarint();
        break;
      case 8:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        publicKey = r.readString();
        break;
      case 9:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        avatar = r.readString();
        break;
      case 10:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        signature = r.readString();
        break;
      case 11:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        extra = r.readString();
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return IMUserModel(
    userId: userId,
    username: username,
    nickname: nickname,
    token: token,
    status: status,
    ip: ip,
    port: port,
    publicKey: publicKey,
    avatar: avatar,
    signature: signature,
    extra: extra,
  );
}

class IMChatModel {
  final String msgId;
  final String senderId;
  final String receiverId;
  final String receiverType;
  final String msgType;
  final String content;
  final int timestamp;
  final String extra;
  final String quoteMsgId;
  final List<String> atUsers;
  final FileDataModel? fileInfo;

  const IMChatModel({
    required this.msgId,
    required this.senderId,
    required this.receiverId,
    required this.receiverType,
    required this.msgType,
    required this.content,
    required this.timestamp,
    required this.extra,
    required this.quoteMsgId,
    required this.atUsers,
    this.fileInfo,
  });
}

Uint8List encodeIMChatModel(IMChatModel m) {
  final w = ProtoWriter();
  w.writeString(1, m.msgId);
  w.writeString(2, m.senderId);
  w.writeString(3, m.receiverId);
  w.writeString(4, m.receiverType);
  w.writeString(5, m.msgType);
  w.writeString(6, m.content);
  w.writeUint64(7, m.timestamp);
  w.writeString(8, m.extra);
  w.writeString(9, m.quoteMsgId);
  for (final u in m.atUsers) {
    w.writeString(10, u);
  }
  final fileInfo = m.fileInfo;
  if (fileInfo != null) {
    w.writeEmbedded(11, encodeFileDataModel(fileInfo));
  }
  return w.takeBytes();
}

IMChatModel decodeIMChatModel(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var msgId = "";
  var senderId = "";
  var receiverId = "";
  var receiverType = "";
  var msgType = "";
  var content = "";
  var timestamp = 0;
  var extra = "";
  var quoteMsgId = "";
  final atUsers = <String>[];
  FileDataModel? fileInfo;
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        msgId = r.readString();
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        senderId = r.readString();
        break;
      case 3:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        receiverId = r.readString();
        break;
      case 4:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        receiverType = r.readString();
        break;
      case 5:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        msgType = r.readString();
        break;
      case 6:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        content = r.readString();
        break;
      case 7:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        timestamp = r.readVarint64();
        break;
      case 8:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        extra = r.readString();
        break;
      case 9:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        quoteMsgId = r.readString();
        break;
      case 10:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        atUsers.add(r.readString());
        break;
      case 11:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        fileInfo = decodeFileDataModel(Uint8List.fromList(r.readBytes()));
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return IMChatModel(
    msgId: msgId,
    senderId: senderId,
    receiverId: receiverId,
    receiverType: receiverType,
    msgType: msgType,
    content: content,
    timestamp: timestamp,
    extra: extra,
    quoteMsgId: quoteMsgId,
    atUsers: atUsers,
    fileInfo: fileInfo,
  );
}

class IMChatAck {
  final String msgId;
  final String userId;
  final int timestamp;
  final String ackType;
  final String peerId;

  const IMChatAck({
    required this.msgId,
    required this.userId,
    required this.timestamp,
    required this.ackType,
    required this.peerId,
  });
}

Uint8List encodeIMChatAck(IMChatAck a) {
  final w = ProtoWriter();
  w.writeString(1, a.msgId);
  w.writeString(2, a.userId);
  w.writeUint64(3, a.timestamp);
  w.writeString(4, a.ackType);
  w.writeString(5, a.peerId);
  return w.takeBytes();
}

IMChatAck decodeIMChatAck(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var msgId = "";
  var userId = "";
  var timestamp = 0;
  var ackType = "";
  var peerId = "";
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        msgId = r.readString();
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        userId = r.readString();
        break;
      case 3:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        timestamp = r.readVarint64();
        break;
      case 4:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        ackType = r.readString();
        break;
      case 5:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        peerId = r.readString();
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return IMChatAck(msgId: msgId, userId: userId, timestamp: timestamp, ackType: ackType, peerId: peerId);
}

class IMChatHistoryRequest {
  final String userId;
  final String peerId;
  final int limit;

  const IMChatHistoryRequest({required this.userId, required this.peerId, required this.limit});
}

Uint8List encodeIMChatHistoryRequest(IMChatHistoryRequest q) {
  final w = ProtoWriter();
  w.writeString(1, q.userId);
  w.writeString(2, q.peerId);
  w.writeUint32(3, q.limit);
  return w.takeBytes();
}

IMChatHistoryRequest decodeIMChatHistoryRequest(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var userId = "";
  var peerId = "";
  var limit = 0;
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        userId = r.readString();
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        peerId = r.readString();
        break;
      case 3:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        limit = r.readVarint();
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return IMChatHistoryRequest(userId: userId, peerId: peerId, limit: limit);
}

class IMChatHistoryResponse {
  final List<IMChatModel> items;

  const IMChatHistoryResponse(this.items);
}

Uint8List encodeIMChatHistoryResponse(IMChatHistoryResponse r) {
  final w = ProtoWriter();
  for (final m in r.items) {
    w.writeEmbedded(1, encodeIMChatModel(m));
  }
  return w.takeBytes();
}

IMChatHistoryResponse decodeIMChatHistoryResponse(Uint8List bytes) {
  final r = ProtoReader(bytes);
  final items = <IMChatModel>[];
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        items.add(decodeIMChatModel(r.readBytes()));
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return IMChatHistoryResponse(items);
}

class IMUserListResponse {
  final List<IMUserModel> items;

  const IMUserListResponse(this.items);
}

Uint8List encodeIMUserListResponse(IMUserListResponse r) {
  final w = ProtoWriter();
  for (final u in r.items) {
    w.writeEmbedded(1, encodeIMUserModel(u));
  }
  return w.takeBytes();
}

IMUserListResponse decodeIMUserListResponse(Uint8List bytes) {
  final r = ProtoReader(bytes);
  final items = <IMUserModel>[];
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        items.add(decodeIMUserModel(Uint8List.fromList(r.readBytes())));
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return IMUserListResponse(items);
}

class IMGroupModel {
  final String groupId;
  final String name;
  final String ownerId;
  final String avatar;
  final String notice;
  final String extra;
  final List<String> adminIds;

  const IMGroupModel({
    required this.groupId,
    required this.name,
    required this.ownerId,
    required this.avatar,
    required this.notice,
    required this.extra,
    required this.adminIds,
  });
}

Uint8List encodeIMGroupModel(IMGroupModel g) {
  final w = ProtoWriter();
  w.writeString(1, g.groupId);
  w.writeString(2, g.name);
  w.writeString(3, g.ownerId);
  w.writeString(4, g.avatar);
  w.writeString(5, g.notice);
  w.writeString(6, g.extra);
  for (final id in g.adminIds) {
    w.writeString(7, id);
  }
  return w.takeBytes();
}

IMGroupModel decodeIMGroupModel(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var groupId = "";
  var name = "";
  var ownerId = "";
  var avatar = "";
  var notice = "";
  var extra = "";
  final adminIds = <String>[];
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        groupId = r.readString();
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        name = r.readString();
        break;
      case 3:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        ownerId = r.readString();
        break;
      case 4:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        avatar = r.readString();
        break;
      case 5:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        notice = r.readString();
        break;
      case 6:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        extra = r.readString();
        break;
      case 7:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        adminIds.add(r.readString());
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return IMGroupModel(
    groupId: groupId,
    name: name,
    ownerId: ownerId,
    avatar: avatar,
    notice: notice,
    extra: extra,
    adminIds: adminIds,
  );
}

class IMGroupDismissRequest {
  final String groupId;
  final String operatorId;

  const IMGroupDismissRequest({required this.groupId, required this.operatorId});
}

Uint8List encodeIMGroupDismissRequest(IMGroupDismissRequest r) {
  final w = ProtoWriter();
  w.writeString(1, r.groupId);
  w.writeString(2, r.operatorId);
  return w.takeBytes();
}

IMGroupDismissRequest decodeIMGroupDismissRequest(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var groupId = "";
  var operatorId = "";
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        groupId = r.readString();
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        operatorId = r.readString();
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return IMGroupDismissRequest(groupId: groupId, operatorId: operatorId);
}

class IMGroupRemoveMemberRequest {
  final String groupId;
  final String operatorId;
  final String memberId;

  const IMGroupRemoveMemberRequest({required this.groupId, required this.operatorId, required this.memberId});
}

Uint8List encodeIMGroupRemoveMemberRequest(IMGroupRemoveMemberRequest r) {
  final w = ProtoWriter();
  w.writeString(1, r.groupId);
  w.writeString(2, r.operatorId);
  w.writeString(3, r.memberId);
  return w.takeBytes();
}

IMGroupRemoveMemberRequest decodeIMGroupRemoveMemberRequest(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var groupId = "";
  var operatorId = "";
  var memberId = "";
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        groupId = r.readString();
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        operatorId = r.readString();
        break;
      case 3:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        memberId = r.readString();
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return IMGroupRemoveMemberRequest(groupId: groupId, operatorId: operatorId, memberId: memberId);
}

class IMGroupUpdateInfoRequest {
  final String groupId;
  final String operatorId;
  final String name;
  final String avatar;
  final String notice;
  final String extra;

  const IMGroupUpdateInfoRequest({
    required this.groupId,
    required this.operatorId,
    required this.name,
    required this.avatar,
    required this.notice,
    required this.extra,
  });
}

Uint8List encodeIMGroupUpdateInfoRequest(IMGroupUpdateInfoRequest r) {
  final w = ProtoWriter();
  w.writeString(1, r.groupId);
  w.writeString(2, r.operatorId);
  w.writeString(3, r.name);
  w.writeString(4, r.avatar);
  w.writeString(5, r.notice);
  w.writeString(6, r.extra);
  return w.takeBytes();
}

IMGroupUpdateInfoRequest decodeIMGroupUpdateInfoRequest(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var groupId = "";
  var operatorId = "";
  var name = "";
  var avatar = "";
  var notice = "";
  var extra = "";
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        groupId = r.readString();
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        operatorId = r.readString();
        break;
      case 3:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        name = r.readString();
        break;
      case 4:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        avatar = r.readString();
        break;
      case 5:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        notice = r.readString();
        break;
      case 6:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        extra = r.readString();
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return IMGroupUpdateInfoRequest(
    groupId: groupId,
    operatorId: operatorId,
    name: name,
    avatar: avatar,
    notice: notice,
    extra: extra,
  );
}

class IMGroupSetAdminRequest {
  final String groupId;
  final String operatorId;
  final String memberId;
  final bool isAdmin;

  const IMGroupSetAdminRequest({
    required this.groupId,
    required this.operatorId,
    required this.memberId,
    required this.isAdmin,
  });
}

Uint8List encodeIMGroupSetAdminRequest(IMGroupSetAdminRequest r) {
  final w = ProtoWriter();
  w.writeString(1, r.groupId);
  w.writeString(2, r.operatorId);
  w.writeString(3, r.memberId);
  w.writeBool(4, r.isAdmin);
  return w.takeBytes();
}

IMGroupSetAdminRequest decodeIMGroupSetAdminRequest(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var groupId = "";
  var operatorId = "";
  var memberId = "";
  var isAdmin = false;
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        groupId = r.readString();
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        operatorId = r.readString();
        break;
      case 3:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        memberId = r.readString();
        break;
      case 4:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        isAdmin = r.readVarint() != 0;
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return IMGroupSetAdminRequest(groupId: groupId, operatorId: operatorId, memberId: memberId, isAdmin: isAdmin);
}

class IMSystemEvent {
  final String type;
  final String groupId;
  final String operatorId;
  final String targetId;
  final int timestamp;
  final String message;

  const IMSystemEvent({
    required this.type,
    required this.groupId,
    required this.operatorId,
    required this.targetId,
    required this.timestamp,
    required this.message,
  });
}

Uint8List encodeIMSystemEvent(IMSystemEvent e) {
  final w = ProtoWriter();
  w.writeString(1, e.type);
  w.writeString(2, e.groupId);
  w.writeString(3, e.operatorId);
  w.writeString(4, e.targetId);
  w.writeUint64(5, e.timestamp);
  w.writeString(6, e.message);
  return w.takeBytes();
}

IMSystemEvent decodeIMSystemEvent(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var type = "";
  var groupId = "";
  var operatorId = "";
  var targetId = "";
  var timestamp = 0;
  var message = "";
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        type = r.readString();
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        groupId = r.readString();
        break;
      case 3:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        operatorId = r.readString();
        break;
      case 4:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        targetId = r.readString();
        break;
      case 5:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        timestamp = r.readVarint64();
        break;
      case 6:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        message = r.readString();
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return IMSystemEvent(
    type: type,
    groupId: groupId,
    operatorId: operatorId,
    targetId: targetId,
    timestamp: timestamp,
    message: message,
  );
}

class IMGroupMembersResponse {
  final String groupId;
  final List<IMUserModel> items;

  const IMGroupMembersResponse({required this.groupId, required this.items});
}

class IMGroupListRequest {
  final String userId;

  const IMGroupListRequest({required this.userId});
}

Uint8List encodeIMGroupListRequest(IMGroupListRequest r) {
  final w = ProtoWriter();
  w.writeString(1, r.userId);
  return w.takeBytes();
}

IMGroupListRequest decodeIMGroupListRequest(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var userId = "";
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        userId = r.readString();
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return IMGroupListRequest(userId: userId);
}

Uint8List encodeIMGroupMembersResponse(IMGroupMembersResponse r) {
  final w = ProtoWriter();
  w.writeString(1, r.groupId);
  for (final u in r.items) {
    w.writeEmbedded(2, encodeIMUserModel(u));
  }
  return w.takeBytes();
}

IMGroupMembersResponse decodeIMGroupMembersResponse(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var groupId = "";
  final items = <IMUserModel>[];
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        groupId = r.readString();
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        items.add(decodeIMUserModel(Uint8List.fromList(r.readBytes())));
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return IMGroupMembersResponse(groupId: groupId, items: items);
}

class IMGroupListResponse {
  final List<IMGroupModel> items;

  const IMGroupListResponse(this.items);
}

Uint8List encodeIMGroupListResponse(IMGroupListResponse r) {
  final w = ProtoWriter();
  for (final g in r.items) {
    w.writeEmbedded(1, encodeIMGroupModel(g));
  }
  return w.takeBytes();
}

IMGroupListResponse decodeIMGroupListResponse(Uint8List bytes) {
  final r = ProtoReader(bytes);
  final items = <IMGroupModel>[];
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        items.add(decodeIMGroupModel(r.readBytes()));
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return IMGroupListResponse(items);
}
