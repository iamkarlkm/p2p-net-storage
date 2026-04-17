import "dart:async";
import "dart:io";

import "../lib/p2p_ws_sdk.dart";

Future<void> main(List<String> args) async {
  if (args.isEmpty) {
    throw StateError("usage: dart run bin/p2pd.dart <peer_cfg.yaml> [connect_hint_target_node_id64]");
  }
  final cfgPath = args[0];
  final hintTargetId64 = (args.length >= 2) ? int.tryParse(args[1]) : null;

  final cfg = await PeerNodeConfig.fromYamlFile(cfgPath);
  final node = await PeerNode.fromConfig(cfg);
  await node.start();
  if (hintTargetId64 != null) {
    await node.connectHint(hintTargetId64);
  }

  final stop = Completer<void>();
  ProcessSignal.sigint.watch().listen((_) async {
    await node.stop();
    stop.complete();
  });
  await stop.future;
}

