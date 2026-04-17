class P2PCommand {
  static const int stdError = -1;
  static const int invalidData = -2;
  static const int stdOk = 6;

  static const int hand = -10001;
  static const int handAck = -10002;
  static const int cryptUpdate = -10010;
  static const int headerUpdate = -10011;

  static const int centerHello = -11001;
  static const int centerHelloAck = -11002;
  static const int centerGetNode = -11010;
  static const int centerGetNodeAck = -11011;
  static const int centerRelayData = -11012;
  static const int centerConnectHint = -11030;
  static const int centerIncomingHint = -11031;

  static const int peerHello = -12001;
  static const int peerHelloAck = -12002;

  static const int getFile = 7;
  static const int okGetFile = 8;
  static const int putFile = 14;
  static const int forcePutFile = 15;
  static const int filesCommand = 19;
  static const int getFileSegments = 20;
  static const int putFileSegments = 21;
  static const int checkFile = 22;
  static const int okGetFileSegments = 43;
  static const int putFileSegmentsComplete = 44;
  static const int infoFile = 46;
  static const int fileRename = 50;
  static const int fileList = 51;
  static const int fileExists = 52;
  static const int fileMkdirs = 53;

  static const int filePutReq = 1001;
  static const int filePutResp = 1002;
  static const int fileGetReq = 1003;
  static const int fileGetResp = 1004;
}
