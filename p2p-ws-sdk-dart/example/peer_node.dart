import "dart:async";
import "dart:io";

import "../lib/p2p_ws_sdk.dart";

Future<void> main(List<String> args) async {
  final cfgPath = args.isNotEmpty ? args[0] : "${Directory.current.path}/../p2p-ws-protocol/examples/peer1.yaml";
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
