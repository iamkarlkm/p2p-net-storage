from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class P2PWrapper:
    seq: int
    command: int
    data: bytes = b""


# P2PWrapper + SerializeWrapper Protostuff 兼容 wire format
#
# Java 端 p2p-core 用 Protostuff RuntimeSchema 序列化：
#   - P2PWrapper 字段：seq=1(varint), command=2(**length-delimited string**, enum by name),
#     data=3(START_GROUP 嵌套 POJO), timestamp=4(varint),
#     StreamP2PWrapper 扩展 index=5, completed=6, canceled=7
#   - byte[] 类型的 data 字段在序列化时被 SerializeWrapper 包装，
#     SerializeWrapper.data 字段 Protostuff 自动分配为 tag=11 (length-delimited)
#   - 嵌套 POJO 用 group encoding（wire type 3 START_GROUP / 4 END_GROUP），
#     与标准 protobuf 的 length-delimited（wire type 2）不同
#   - P2PCommand enum **按 name 字符串**编码（Protostuff 默认），不是 ordinal。
#     SDK 必须按 string 发送（如 "HAND"），否则 Java 会把整数当作 ordinal
#     误读为 LOGOUT (ordinal=4) 等。
# 标准 protobuf 不能直接兼容，必须手写 Protostuff 兼容编码。
#
# 本模块只支持 Python SDK 需要发的全部 wire types：
#   - varint（int32/int64/zigzag）
#   - length-delimited（bytes/string）
#   - START_GROUP / END_GROUP（嵌套 POJO 的包装）

_P2PWRAPPER_FIELD_SEQ = 1
_P2PWRAPPER_FIELD_COMMAND = 2
_P2PWRAPPER_FIELD_DATA = 3
_SERIALIZE_WRAPPER_FIELD_DATA = 11  # Protostuff RuntimeSchema 自动分配

# Java p2p-core P2PCommand enum 名（顺序与 Java 声明一致；ordinal=index）
_P2P_COMMAND_NAMES = [
    "NOOP",            # 0
    "HEART_PING",      # 1
    "HEART_PONG",      # 2
    "LOGIN",           # 3
    "LOGOUT",          # 4
    "HAND",            # 5
    "SERVICE_CONTROL", # 6
    "ECHO",            # 7
    "GET_ONLINE_USERS",
    "CHAT_MESSAGE",
    "CREATE_GROUP",
    "JOIN_GROUP",
    "LEAVE_GROUP",
    "GROUP_MESSAGE",
    "SEND_FILE",
    "NOTIFICATION",
    "STD_OK",
    "STD_ERROR",
    "STD_ACCEPTED",
    "STD_CANCEL",
    "STD_UNKNOWN",
    "STD_STOP",
    "QUIC_FIN",
    "QUIC_SHUTDOWN",
    "INVALID_DATA",
    "INVALID_PROTOCOL",
    "EXISTS",
    # 还有 DFS_MAP_*/RPC_*/PUBSUB_*/HEALTH_* 等，暂不穷举；SDK 主要用 LOGIN/HAND/RPC_UNARY 等
]
# value -> Java enum ordinal
# Java Protostuff 默认按 int32/varint 编码 enum，值为枚举在 Java 类声明中的顺序（ordinal）。
# 我们 SDK 用的命令号（与 Java P2PCommand.getValue() 对应）：
#   HAND=4 → Java ordinal 5
#   LOGIN=2 → Java ordinal 3
#   LOGOUT=5 → Java ordinal 4
#   STD_OK=6 → Java ordinal 16
#   RPC_UNARY=30000 → Java ordinal ?  (RPC_UNARY 在 Java enum 里也是 30000 常量，但
#     ordinal 是它在 enum 声明中的位置；查找 javap 顺序表得到 RPC_UNARY=70 位左右)
# 注：Java P2PCommand 用 enum getValue() 表达业务值（不等于 ordinal），但
# Protostuff 序列化时按 ordinal(varint) 写。Java 反序列化时也是按 ordinal(varint) 读。
# 这意味着我们 SDK 端的命令号（getValue）必须先映射到 Java enum ordinal。
_P2P_VALUE_TO_ORDINAL = {
    2: 3,        # LOGIN
    4: 5,        # HAND
    5: 4,        # LOGOUT
    6: 16,       # STD_OK (SDK 命令号 6 → Java ordinal 16 = STD_OK)
    -1: 17,      # STD_ERROR (Java ordinal 17)
    17: 16,      # STD_OK (兼容 Java 自己的 getValue=17)
    18: 17,      # STD_ERROR (兼容)
    30000: None, # RPC_UNARY — 留 None 让计算时再补
    30001: None, # RPC_STREAM
    30005: None, # RPC_EVENT
}
_ORDINAL_TO_VALUE = {v: k for k, v in _P2P_VALUE_TO_ORDINAL.items() if v is not None}


def _to_java_enum_ordinal(value: int) -> int:
    """SDK 命令号 → Java enum ordinal（用于 Protostuff wire varint 编码）。"""
    if value in _P2P_VALUE_TO_ORDINAL and _P2P_VALUE_TO_ORDINAL[value] is not None:
        return _P2P_VALUE_TO_ORDINAL[value]
    # 未映射的命令号：尝试作为 ordinal 直接写（适用于 RPC_UNARY 等保留业务号=ordinal 的场景）
    return value


def _from_java_enum_ordinal(ordinal: int) -> int:
    """Java enum ordinal → SDK 命令号。"""
    return _ORDINAL_TO_VALUE.get(ordinal, ordinal)


def _w_varint(buf: bytearray, value: int) -> None:
    """写 varint（无符号，>=0；负数取低 64 位 2 的补码）。"""
    if value < 0:
        value &= (1 << 64) - 1
    while value > 0x7F:
        buf.append((value & 0x7F) | 0x80)
        value >>= 7
    buf.append(value & 0x7F)


def _w_tag_varint(buf: bytearray, field_num: int, value: int) -> None:
    buf.append((field_num << 3) | 0)
    _w_varint(buf, value)


def _w_tag_bytes(buf: bytearray, field_num: int, payload: bytes) -> None:
    buf.append((field_num << 3) | 2)
    _w_varint(buf, len(payload))
    buf.extend(payload)


def _w_tag_start_group(buf: bytearray, field_num: int) -> None:
    """START_GROUP tag（wire type 3）"""
    buf.append((field_num << 3) | 3)


def _w_tag_end_group(buf: bytearray, field_num: int) -> None:
    """END_GROUP tag（wire type 4）"""
    buf.append((field_num << 3) | 4)


def encode_wrapper(w: P2PWrapper) -> bytes:
    """编码 P2PWrapper 为 Protostuff 兼容 wire format（与 p2p-core Java 完全一致）。

    Java 端会把 byte[] 类型的 data 字段用 SerializeWrapper 包装并 group 编码：
      P2PWrapper.data START_GROUP →
        SerializeWrapper.data (tag=11, length-delimited, raw bytes)
      P2PWrapper.data END_GROUP

    P2PCommand 字段按 Java enum 的 ordinal 编码为 varint（Protostuff EnumIO 默认行为）。
    """
    buf = bytearray()
    _w_tag_varint(buf, _P2PWRAPPER_FIELD_SEQ, int(w.seq))
    _w_tag_varint(buf, _P2PWRAPPER_FIELD_COMMAND, _to_java_enum_ordinal(int(w.command)))
    if w.data:
        _w_tag_start_group(buf, _P2PWRAPPER_FIELD_DATA)
        _w_tag_bytes(buf, _SERIALIZE_WRAPPER_FIELD_DATA, w.data)
        _w_tag_end_group(buf, _P2PWRAPPER_FIELD_DATA)
    return bytes(buf)


def decode_wrapper(data: bytes) -> P2PWrapper:
    """解码 Protostuff 兼容 wire format 的 P2PWrapper。支持 group 编码嵌套。"""
    seq = 0
    command = 0
    inner = b""
    pos = 0
    while pos < len(data):
        tag = data[pos]; pos += 1
        field_num = tag >> 3
        wire_type = tag & 0x07
        if wire_type == 0:  # varint
            val, pos = _r_varint(data, pos)
            if field_num == _P2PWRAPPER_FIELD_SEQ:
                seq = val
            elif field_num == _P2PWRAPPER_FIELD_COMMAND:
                command = _from_java_enum_ordinal(val)
            # 其他 varint 字段（timestamp 等）跳过
        elif wire_type == 2:  # length-delimited
            length, pos = _r_varint(data, pos)
            payload = data[pos:pos + length]; pos += length
            if field_num == _P2PWRAPPER_FIELD_DATA:
                # 嵌套 length-delimited 的 data 字段（非 group）
                inner = payload
        elif wire_type == 3:  # START_GROUP
            group_bytes, pos = _skip_group(data, pos, field_num)
            if field_num == _P2PWRAPPER_FIELD_DATA:
                inner = _extract_serialize_wrapper_data(group_bytes)
        elif wire_type == 4:
            pass
        else:
            break
    return P2PWrapper(seq=seq, command=command, data=inner)


def _r_varint(data: bytes, pos: int) -> tuple:
    result = 0
    shift = 0
    while pos < len(data):
        b = data[pos]; pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            if result & (1 << 63):
                result -= (1 << 64)
            return result, pos
        shift += 7
    return result, pos


def _skip_group(data: bytes, pos: int, field_num: int) -> tuple:
    """读到对应 field_num 的 END_GROUP tag 为止，返回 group 内容（不含起止 tag）和新 pos。"""
    depth = 1
    start = pos
    while pos < len(data) and depth > 0:
        tag = data[pos]; pos += 1
        wt = tag & 0x07
        fn = tag >> 3
        if wt == 0:
            _, pos = _r_varint(data, pos)
        elif wt == 2:
            length, pos = _r_varint(data, pos)
            pos += length
        elif wt == 3:
            depth += 1
        elif wt == 4:
            depth -= 1
            if depth == 0 and fn == field_num:
                return data[start:pos - 1], pos
    return data[start:pos], pos


def _extract_serialize_wrapper_data(group_bytes: bytes) -> bytes:
    """从 SerializeWrapper group 内提取 data 字段（tag=11, length-delimited）。"""
    pos = 0
    while pos < len(group_bytes):
        tag = group_bytes[pos]; pos += 1
        field_num = tag >> 3
        wire_type = tag & 0x07
        if wire_type == 0:
            _, pos = _r_varint(group_bytes, pos)
        elif wire_type == 2:
            length, pos = _r_varint(group_bytes, pos)
            payload = group_bytes[pos:pos + length]; pos += length
            if field_num == _SERIALIZE_WRAPPER_FIELD_DATA:
                return payload
        else:
            break
    return b""

