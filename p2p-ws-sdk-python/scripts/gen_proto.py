from __future__ import annotations

from pathlib import Path
import sys


def main() -> int:
    repo = Path(__file__).resolve().parents[2]
    proto_dir = repo / "p2p-ws-protocol" / "proto"
    out_dir = repo / "p2p-ws-sdk-python" / "src" / "p2p_ws_sdk" / "gen"
    out_dir.mkdir(parents=True, exist_ok=True)

    try:
        from grpc_tools import protoc  # type: ignore
    except Exception:
        raise SystemExit("grpcio-tools is required: pip install grpcio-tools")

    args = [
        "protoc",
        f"-I{proto_dir}",
        f"--python_out={out_dir}",
        str(proto_dir / "p2p_wrapper.proto"),
        str(proto_dir / "p2p_control.proto"),
    ]
    rc = protoc.main(args)
    if rc != 0:
        raise SystemExit(f"protoc failed: rc={rc}")
    print("ok=1")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

