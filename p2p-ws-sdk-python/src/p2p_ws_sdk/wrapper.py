from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class P2PWrapper:
    seq: int
    command: int
    data: bytes = b""


def encode_wrapper(w: P2PWrapper) -> bytes:
    try:
        from p2p_ws_sdk.gen import p2p_wrapper_pb2  # type: ignore
    except Exception as e:
        raise RuntimeError("generated protobuf is required; run: python p2p-ws-sdk-python/scripts/gen_proto.py") from e

    msg = p2p_wrapper_pb2.P2PWrapper(seq=int(w.seq), command=int(w.command), data=w.data)
    return msg.SerializeToString()


def decode_wrapper(data: bytes) -> P2PWrapper:
    try:
        from p2p_ws_sdk.gen import p2p_wrapper_pb2  # type: ignore
    except Exception as e:
        raise RuntimeError("generated protobuf is required; run: python p2p-ws-sdk-python/scripts/gen_proto.py") from e

    msg = p2p_wrapper_pb2.P2PWrapper()
    msg.ParseFromString(data)
    return P2PWrapper(seq=int(msg.seq), command=int(msg.command), data=bytes(msg.data))

