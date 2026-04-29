package p2pws.sdk.demo;

import com.google.protobuf.ByteString;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import p2pws.P2PControl;
import p2pws.P2PData;
import p2pws.P2PIm;
import p2pws.P2PWrapperOuterClass;
import p2pws.sdk.FrameCodec;
import p2pws.sdk.KeyFileProvider;
import p2pws.sdk.P2PWrapperCodec;
import p2pws.sdk.RsaOaep;
import p2pws.sdk.WireHeader;
import p2pws.sdk.WireFrame;
import p2pws.sdk.XorCipher;
import p2pws.sdk.im.ImCommands;
import p2pws.sdk.im.ImMemory;

public final class DemoServerHandler extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {
    private static final int CMD_GET_FILE = 7;
    private static final int CMD_OK_GET_FILE = 8;
    private static final int CMD_PUT_FILE = 14;
    private static final int CMD_FORCE_PUT_FILE = 15;
    private static final int CMD_GET_FILE_SEGMENTS = 20;
    private static final int CMD_PUT_FILE_SEGMENTS = 21;
    private static final int CMD_OK_GET_FILE_SEGMENTS = 43;
    private static final int CMD_PUT_FILE_SEGMENTS_COMPLETE = 44;
    private static final int CMD_INFO_FILE = 46;

    private static final int IM_STORE_PUBLIC = -1;
    private static final int IM_STORE_GROUP = -2;
    private static final int IM_STORE_PRIVATE = -3;

    private final KeyFileProvider provider;
    private final byte[] keyId32;
    private final long keyLen;
    private final int magic;
    private final int version;
    private final int flagsPlain;
    private final int flagsEncrypted;
    private final int maxFramePayload;
    private final StorageRegistry storage;
    private final SecureRandom rnd = new SecureRandom();

    private long offset = -1;
    private boolean encrypted = false;
    private boolean cryptUpdated = false;

    public DemoServerHandler(KeyFileProvider provider, byte[] keyId32, long keyLen, int magic, int version, int flagsPlain, int flagsEncrypted, int maxFramePayload, StorageRegistry storage) {
        this.provider = provider;
        this.keyId32 = Arrays.copyOf(keyId32, keyId32.length);
        this.keyLen = keyLen;
        this.magic = magic;
        this.version = version;
        this.flagsPlain = flagsPlain;
        this.flagsEncrypted = flagsEncrypted;
        this.maxFramePayload = maxFramePayload;
        this.storage = storage;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) {
        ByteBuf content = frame.content();
        byte[] ws = new byte[content.readableBytes()];
        content.readBytes(ws);

        WireFrame wf = FrameCodec.decode(ws);
        byte[] payload = wf.cipherPayload();
        byte[] plain = encrypted ? XorCipher.xorWithKeyFile(payload, provider, keyId32, offset) : payload;
        P2PWrapperOuterClass.P2PWrapper wrapper = P2PWrapperCodec.decode(plain);

        int cmd = wrapper.getCommand();
        if (cmd == -10001) {
            handleHand(ctx, wrapper);
            return;
        }
        if (cmd == -10010) {
            return;
        }
        switch (cmd) {
            case CMD_GET_FILE:
                handleGetFile(ctx, wrapper);
                return;
            case CMD_PUT_FILE:
                handlePutFile(ctx, wrapper, false);
                return;
            case CMD_FORCE_PUT_FILE:
                handlePutFile(ctx, wrapper, true);
                return;
            case CMD_GET_FILE_SEGMENTS:
                handleGetFileSegments(ctx, wrapper);
                return;
            case CMD_PUT_FILE_SEGMENTS:
                handlePutFileSegments(ctx, wrapper);
                return;
            case CMD_PUT_FILE_SEGMENTS_COMPLETE:
                handlePutFileSegmentsComplete(ctx, wrapper);
                return;
            case CMD_INFO_FILE:
                handleInfoFile(ctx, wrapper);
                return;
            case ImCommands.IM_USER_LOGIN:
                handleImUserLogin(ctx, wrapper);
                return;
            case ImCommands.IM_USER_LOGOUT:
                handleImUserLogout(ctx, wrapper);
                return;
            case ImCommands.IM_USER_LIST:
                handleImUserList(ctx, wrapper);
                return;
            case ImCommands.IM_CHAT_SEND:
                handleImChatSend(ctx, wrapper);
                return;
            case ImCommands.IM_CHAT_ACK:
                handleImChatAck(ctx, wrapper);
                return;
            case ImCommands.IM_CHAT_STATUS_UPDATE:
                handleImChatStatusUpdate(ctx, wrapper);
                return;
            case ImCommands.IM_CHAT_HISTORY_REQUEST:
                handleImChatHistoryRequest(ctx, wrapper);
                return;
            case ImCommands.IM_GROUP_CREATE:
                handleImGroupCreate(ctx, wrapper);
                return;
            case ImCommands.IM_GROUP_JOIN:
                handleImGroupJoin(ctx, wrapper);
                return;
            case ImCommands.IM_GROUP_LEAVE:
                handleImGroupLeave(ctx, wrapper);
                return;
            case ImCommands.IM_GROUP_LIST:
                handleImGroupList(ctx, wrapper);
                return;
            case ImCommands.IM_GROUP_MEMBERS:
                handleImGroupMembers(ctx, wrapper);
                return;
            case ImCommands.IM_GROUP_MESSAGE_SEND:
                handleImGroupMessageSend(ctx, wrapper);
                return;
            case ImCommands.IM_GROUP_DISMISS:
                handleImGroupDismiss(ctx, wrapper);
                return;
            case ImCommands.IM_GROUP_REMOVE_MEMBER:
                handleImGroupRemoveMember(ctx, wrapper);
                return;
            case ImCommands.IM_GROUP_UPDATE_INFO:
                handleImGroupUpdateInfo(ctx, wrapper);
                return;
            case ImCommands.IM_GROUP_SET_ADMIN:
                handleImGroupSetAdmin(ctx, wrapper);
                return;
            default:
                break;
        }
        if (cmd == 1) {
            P2PWrapperOuterClass.P2PWrapper resp = P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(wrapper.getCommand())
                .setData(wrapper.getData())
                .build();
            writeEncrypted(ctx, resp);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        ImMemory.unbind(ctx);
        super.channelInactive(ctx);
    }

    private void handlePutFile(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper, boolean force) {
        try {
            P2PData.FileDataModel req = P2PData.FileDataModel.parseFrom(wrapper.getData());
            int storeId = req.getStoreId();
            String relPath = req.getPath();
            if (storeId == 0 || relPath == null || relPath.isBlank()) {
                writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(wrapper.getSeq())
                    .setCommand(ImCommands.INVALID_DATA)
                    .setData(ByteString.copyFromUtf8("store_id and path required"))
                    .build());
                return;
            }
            Path file = storage.resolveForWrite(storeId, relPath);
            if (!force && Files.exists(file)) {
                writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(wrapper.getSeq())
                    .setCommand(ImCommands.STD_ERROR)
                    .setData(ByteString.copyFromUtf8("file exists"))
                    .build());
                return;
            }
            Files.createDirectories(file.getParent());
            Files.write(file, req.getData().toByteArray());
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.STD_OK)
                .setData(ByteString.EMPTY)
                .build());
        } catch (Exception e) {
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.STD_ERROR)
                .setData(ByteString.copyFromUtf8(String.valueOf(e.getMessage())))
                .build());
        }
    }

    private void handleGetFile(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            P2PData.FileDataModel req = P2PData.FileDataModel.parseFrom(wrapper.getData());
            int storeId = req.getStoreId();
            String relPath = req.getPath();
            if (storeId == 0 || relPath == null || relPath.isBlank()) {
                writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(wrapper.getSeq())
                    .setCommand(ImCommands.INVALID_DATA)
                    .setData(ByteString.copyFromUtf8("store_id and path required"))
                    .build());
                return;
            }
            Path file = storage.resolveForRead(storeId, relPath);
            byte[] content = Files.readAllBytes(file);
            P2PData.FileDataModel resp = P2PData.FileDataModel.newBuilder()
                .setStoreId(storeId)
                .setLength(content.length)
                .setData(ByteString.copyFrom(content))
                .setPath(relPath)
                .setMd5("")
                .setBlockSize(0)
                .build();
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(CMD_OK_GET_FILE)
                .setData(resp.toByteString())
                .build());
        } catch (Exception e) {
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.STD_ERROR)
                .setData(ByteString.copyFromUtf8(String.valueOf(e.getMessage())))
                .build());
        }
    }

    private void handleGetFileSegments(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            P2PData.FileSegmentsDataModel req = P2PData.FileSegmentsDataModel.parseFrom(wrapper.getData());
            int storeId = req.getStoreId();
            String relPath = req.getPath();
            int blockSize = req.getBlockSize();
            if (storeId == 0 || relPath == null || relPath.isBlank() || blockSize <= 0) {
                writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(wrapper.getSeq())
                    .setCommand(ImCommands.INVALID_DATA)
                    .setData(ByteString.copyFromUtf8("store_id/path/block_size required"))
                    .build());
                return;
            }
            Path file = storage.resolveForRead(storeId, relPath);
            long fileLen = Files.size(file);
            long totalLen = req.getLength() > 0 ? req.getLength() : fileLen;
            long start = req.getStart();
            int toRead = (int) Math.min(blockSize, Math.max(0L, fileLen - start));
            byte[] block = new byte[toRead];
            int n = 0;
            if (toRead > 0) {
                try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                    raf.seek(start);
                    n = raf.read(block);
                    if (n < 0) {
                        n = 0;
                    }
                }
            }
            byte[] actual = n == block.length ? block : Arrays.copyOf(block, n);
            String blockMd5 = md5Hex(actual);
            P2PData.FileSegmentsDataModel resp = P2PData.FileSegmentsDataModel.newBuilder()
                .setStoreId(storeId)
                .setLength(totalLen)
                .setStart(start)
                .setBlockIndex(req.getBlockIndex())
                .setBlockSize(blockSize)
                .setBlockData(ByteString.copyFrom(actual))
                .setBlockMd5(blockMd5)
                .setPath(relPath)
                .setMd5(req.getMd5())
                .build();
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(CMD_OK_GET_FILE_SEGMENTS)
                .setData(resp.toByteString())
                .build());
        } catch (Exception e) {
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.STD_ERROR)
                .setData(ByteString.copyFromUtf8(String.valueOf(e.getMessage())))
                .build());
        }
    }

    private void handlePutFileSegments(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            P2PData.FileSegmentsDataModel req = P2PData.FileSegmentsDataModel.parseFrom(wrapper.getData());
            int storeId = req.getStoreId();
            String relPath = req.getPath();
            if (storeId == 0 || relPath == null || relPath.isBlank()) {
                writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(wrapper.getSeq())
                    .setCommand(ImCommands.INVALID_DATA)
                    .setData(ByteString.copyFromUtf8("store_id and path required"))
                    .build());
                return;
            }
            byte[] block = req.getBlockData().toByteArray();
            if (!req.getBlockMd5().isEmpty()) {
                String got = md5Hex(block);
                if (!got.equalsIgnoreCase(req.getBlockMd5())) {
                    writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                        .setSeq(wrapper.getSeq())
                        .setCommand(ImCommands.INVALID_DATA)
                        .setData(ByteString.copyFromUtf8("block md5 mismatch"))
                        .build());
                    return;
                }
            }
            Path file = storage.resolveForWrite(storeId, relPath);
            Files.createDirectories(file.getParent());
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
                raf.seek(req.getStart());
                raf.write(block);
            }
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.STD_OK)
                .setData(ByteString.EMPTY)
                .build());
        } catch (Exception e) {
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.STD_ERROR)
                .setData(ByteString.copyFromUtf8(String.valueOf(e.getMessage())))
                .build());
        }
    }

    private void handlePutFileSegmentsComplete(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            P2PData.FileSegmentsDataModel req = P2PData.FileSegmentsDataModel.parseFrom(wrapper.getData());
            int storeId = req.getStoreId();
            String relPath = req.getPath();
            if (storeId == 0 || relPath == null || relPath.isBlank()) {
                writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(wrapper.getSeq())
                    .setCommand(ImCommands.INVALID_DATA)
                    .setData(ByteString.copyFromUtf8("store_id and path required"))
                    .build());
                return;
            }
            Path file = storage.resolveForRead(storeId, relPath);
            long fileLen = Files.size(file);
            if (req.getLength() > 0 && fileLen != req.getLength()) {
                writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(wrapper.getSeq())
                    .setCommand(ImCommands.INVALID_DATA)
                    .setData(ByteString.copyFromUtf8("length mismatch " + req.getLength() + " <> " + fileLen))
                    .build());
                return;
            }
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.STD_OK)
                .setData(ByteString.EMPTY)
                .build());
        } catch (Exception e) {
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.STD_ERROR)
                .setData(ByteString.copyFromUtf8(String.valueOf(e.getMessage())))
                .build());
        }
    }

    private void handleInfoFile(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            P2PData.FileDataModel req = P2PData.FileDataModel.parseFrom(wrapper.getData());
            int storeId = req.getStoreId();
            String relPath = req.getPath();
            if (storeId == 0 || relPath == null || relPath.isBlank()) {
                writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(wrapper.getSeq())
                    .setCommand(ImCommands.INVALID_DATA)
                    .setData(ByteString.copyFromUtf8("store_id and path required"))
                    .build());
                return;
            }
            Path file = storage.resolveForRead(storeId, relPath);
            long len = Files.size(file);
            String md5 = req.getMd5().isEmpty() ? md5Hex(Files.readAllBytes(file)) : req.getMd5();
            int blockSize = 8 * 1024 * 1024;
            P2PData.FileDataModel resp = P2PData.FileDataModel.newBuilder()
                .setStoreId(storeId)
                .setLength(len)
                .setData(ByteString.EMPTY)
                .setPath(relPath)
                .setMd5(md5)
                .setBlockSize(blockSize)
                .build();
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.STD_OK)
                .setData(resp.toByteString())
                .build());
        } catch (Exception e) {
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.STD_ERROR)
                .setData(ByteString.copyFromUtf8(String.valueOf(e.getMessage())))
                .build());
        }
    }

    private static String md5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(data);
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private P2PIm.IMChatModel persistImFileIfNeeded(P2PIm.IMChatModel msg, String groupId) {
        if (msg == null || !msg.hasFileInfo()) {
            return msg;
        }
        P2PData.FileDataModel fi = msg.getFileInfo();
        if (fi.getData().isEmpty()) {
            return msg;
        }
        int receiverStoreId = fi.getStoreId();
        if (receiverStoreId == 0) {
            receiverStoreId = "GROUP".equals(msg.getReceiverType()) ? IM_STORE_GROUP : IM_STORE_PRIVATE;
        }
        String prefix;
        if (receiverStoreId == IM_STORE_PUBLIC) {
            prefix = msg.getMsgId();
        } else if (receiverStoreId == IM_STORE_GROUP) {
            if (groupId == null || groupId.isEmpty()) {
                throw new IllegalArgumentException("group_id required for IM group storage");
            }
            prefix = groupId + "/" + msg.getMsgId();
        } else if (receiverStoreId == IM_STORE_PRIVATE) {
            prefix = msg.getSenderId() + "/" + msg.getMsgId();
        } else {
            prefix = msg.getMsgId();
        }
        String name = safeBaseName(fi.getPath());
        String relPath = prefix + "/" + name;
        try {
            Path file = storage.resolveForWrite(receiverStoreId, relPath);
            Files.createDirectories(file.getParent());
            byte[] bytes = fi.getData().toByteArray();
            Files.write(file, bytes);
            String md5 = fi.getMd5().isEmpty() ? md5Hex(bytes) : fi.getMd5();
            P2PData.FileDataModel outFi = P2PData.FileDataModel.newBuilder()
                .setStoreId(receiverStoreId)
                .setLength(bytes.length)
                .setData(ByteString.EMPTY)
                .setPath(relPath)
                .setMd5(md5)
                .setBlockSize(fi.getBlockSize())
                .build();
            return P2PIm.IMChatModel.newBuilder(msg).setFileInfo(outFi).build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String safeBaseName(String raw) {
        if (raw == null) return "file.bin";
        String s = raw.trim();
        if (s.isEmpty()) return "file.bin";
        String[] parts = s.replace('\\', '/').split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i] == null ? "" : parts[i].trim();
            if (p.isEmpty()) continue;
            if (p.equals(".") || p.equals("..")) break;
            return p;
        }
        return "file.bin";
    }

    private void handleImUserLogin(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            P2PIm.IMUserModel in = P2PIm.IMUserModel.parseFrom(wrapper.getData());
            String status = in.getStatus().isEmpty() ? "ONLINE" : in.getStatus();
            P2PIm.IMUserModel out = P2PIm.IMUserModel.newBuilder(in).setStatus(status).build();
            ImMemory.bind(ctx, out);
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.STD_OK)
                .setData(out.toByteString())
                .build());
        } catch (Exception e) {
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.INVALID_DATA)
                .setData(ByteString.copyFromUtf8(String.valueOf(e.getMessage())))
                .build());
        }
    }

    private void handleImUserLogout(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        ImMemory.unbind(ctx);
        writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
            .setSeq(wrapper.getSeq())
            .setCommand(ImCommands.STD_OK)
            .setData(ByteString.EMPTY)
            .build());
    }

    private void handleImUserList(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        P2PIm.IMUserListResponse resp = ImMemory.listUsers();
        writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
            .setSeq(wrapper.getSeq())
            .setCommand(ImCommands.STD_OK)
            .setData(resp.toByteString())
            .build());
    }

    private void handleImChatSend(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            P2PIm.IMChatModel in = P2PIm.IMChatModel.parseFrom(wrapper.getData());
            P2PIm.IMChatModel msg = ImMemory.normalizeChat(in);
            msg = persistImFileIfNeeded(msg, "");
            ImMemory.appendHistory(msg);

            ChannelHandlerContext receiver = ImMemory.onlineCtx(msg.getReceiverId());
            if (receiver != null) {
                writeEncrypted(receiver, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(0)
                    .setCommand(ImCommands.IM_CHAT_RECEIVE)
                    .setData(msg.toByteString())
                    .build());
            }

            P2PIm.IMChatAck ack = P2PIm.IMChatAck.newBuilder()
                .setMsgId(msg.getMsgId())
                .setUserId(msg.getSenderId())
                .setTimestamp(System.currentTimeMillis())
                .setAckType("DELIVERED")
                .setPeerId(msg.getReceiverId())
                .build();
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.STD_OK)
                .setData(ack.toByteString())
                .build());
        } catch (Exception e) {
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.INVALID_DATA)
                .setData(ByteString.copyFromUtf8(String.valueOf(e.getMessage())))
                .build());
        }
    }

    private void handleImChatAck(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            P2PIm.IMChatAck ack = P2PIm.IMChatAck.parseFrom(wrapper.getData());
            String senderId = ImMemory.msgSender(ack.getMsgId());
            ChannelHandlerContext sender = senderId == null ? null : ImMemory.onlineCtx(senderId);
            if (sender != null) {
                writeEncrypted(sender, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(0)
                    .setCommand(ImCommands.IM_CHAT_ACK)
                    .setData(ack.toByteString())
                    .build());
            }
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.STD_OK)
                .setData(ByteString.EMPTY)
                .build());
        } catch (Exception e) {
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.INVALID_DATA)
                .setData(ByteString.copyFromUtf8(String.valueOf(e.getMessage())))
                .build());
        }
    }

    private void handleImChatStatusUpdate(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            P2PIm.IMChatAck ack = P2PIm.IMChatAck.parseFrom(wrapper.getData());
            ImMemory.setStatus(ack.getMsgId(), ack.getAckType());
            String senderId = ImMemory.msgSender(ack.getMsgId());
            ChannelHandlerContext sender = senderId == null ? null : ImMemory.onlineCtx(senderId);
            if (sender != null) {
                writeEncrypted(sender, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(0)
                    .setCommand(ImCommands.IM_CHAT_STATUS_UPDATE)
                    .setData(ack.toByteString())
                    .build());
            }
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.STD_OK)
                .setData(ByteString.EMPTY)
                .build());
        } catch (Exception e) {
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.INVALID_DATA)
                .setData(ByteString.copyFromUtf8(String.valueOf(e.getMessage())))
                .build());
        }
    }

    private void handleImGroupCreate(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            String ownerId = ImMemory.userIdOf(ctx);
            if (ownerId == null || ownerId.isEmpty()) {
                writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(wrapper.getSeq())
                    .setCommand(ImCommands.INVALID_DATA)
                    .setData(ByteString.copyFromUtf8("login required"))
                    .build());
                return;
            }
            P2PIm.IMGroupModel in = P2PIm.IMGroupModel.parseFrom(wrapper.getData());
            P2PIm.IMGroupModel out = ImMemory.createGroup(ownerId, in);
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.STD_OK)
                .setData(out.toByteString())
                .build());
        } catch (Exception e) {
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.INVALID_DATA)
                .setData(ByteString.copyFromUtf8(String.valueOf(e.getMessage())))
                .build());
        }
    }

    private void handleImGroupJoin(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            String userId = ImMemory.userIdOf(ctx);
            if (userId == null || userId.isEmpty()) {
                writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(wrapper.getSeq())
                    .setCommand(ImCommands.INVALID_DATA)
                    .setData(ByteString.copyFromUtf8("login required"))
                    .build());
                return;
            }
            P2PIm.IMGroupModel in = P2PIm.IMGroupModel.parseFrom(wrapper.getData());
            if (in.getGroupId().isEmpty()) {
                writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(wrapper.getSeq())
                    .setCommand(ImCommands.INVALID_DATA)
                    .setData(ByteString.copyFromUtf8("group_id required"))
                    .build());
                return;
            }
            ImMemory.joinGroup(userId, in.getGroupId());
            P2PIm.IMSystemEvent evt = P2PIm.IMSystemEvent.newBuilder()
                .setType("GROUP_MEMBER_JOINED")
                .setGroupId(in.getGroupId())
                .setOperatorId(userId)
                .setTargetId(userId)
                .setTimestamp(System.currentTimeMillis())
                .setMessage("joined group")
                .build();
            for (String uid : ImMemory.groupMemberIds(in.getGroupId())) {
                ChannelHandlerContext peer = ImMemory.onlineCtx(uid);
                if (peer == null) continue;
                writeEncrypted(peer, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(0)
                    .setCommand(ImCommands.IM_SYSTEM_STATUS)
                    .setData(evt.toByteString())
                    .build());
            }
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.STD_OK)
                .setData(ByteString.EMPTY)
                .build());
        } catch (Exception e) {
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.INVALID_DATA)
                .setData(ByteString.copyFromUtf8(String.valueOf(e.getMessage())))
                .build());
        }
    }

    private void handleImGroupLeave(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            String userId = ImMemory.userIdOf(ctx);
            if (userId == null || userId.isEmpty()) {
                writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(wrapper.getSeq())
                    .setCommand(ImCommands.INVALID_DATA)
                    .setData(ByteString.copyFromUtf8("login required"))
                    .build());
                return;
            }
            P2PIm.IMGroupModel in = P2PIm.IMGroupModel.parseFrom(wrapper.getData());
            if (in.getGroupId().isEmpty()) {
                writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(wrapper.getSeq())
                    .setCommand(ImCommands.INVALID_DATA)
                    .setData(ByteString.copyFromUtf8("group_id required"))
                    .build());
                return;
            }
            ImMemory.leaveGroup(userId, in.getGroupId());
            P2PIm.IMSystemEvent evt = P2PIm.IMSystemEvent.newBuilder()
                .setType("GROUP_MEMBER_LEFT")
                .setGroupId(in.getGroupId())
                .setOperatorId(userId)
                .setTargetId(userId)
                .setTimestamp(System.currentTimeMillis())
                .setMessage("left group")
                .build();
            for (String uid : ImMemory.groupMemberIds(in.getGroupId())) {
                ChannelHandlerContext peer = ImMemory.onlineCtx(uid);
                if (peer == null) continue;
                writeEncrypted(peer, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(0)
                    .setCommand(ImCommands.IM_SYSTEM_STATUS)
                    .setData(evt.toByteString())
                    .build());
            }
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.STD_OK)
                .setData(ByteString.EMPTY)
                .build());
        } catch (Exception e) {
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.INVALID_DATA)
                .setData(ByteString.copyFromUtf8(String.valueOf(e.getMessage())))
                .build());
        }
    }

    private void handleImGroupList(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        String userId = ImMemory.userIdOf(ctx);
        P2PIm.IMGroupListResponse resp = ImMemory.listGroupsForUser(userId);
        writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
            .setSeq(wrapper.getSeq())
            .setCommand(ImCommands.STD_OK)
            .setData(resp.toByteString())
            .build());
    }

    private void handleImGroupMembers(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            P2PIm.IMGroupModel in = P2PIm.IMGroupModel.parseFrom(wrapper.getData());
            P2PIm.IMGroupMembersResponse resp = ImMemory.groupMembers(in.getGroupId());
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.STD_OK)
                .setData(resp.toByteString())
                .build());
        } catch (Exception e) {
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.INVALID_DATA)
                .setData(ByteString.copyFromUtf8(String.valueOf(e.getMessage())))
                .build());
        }
    }

    private void handleImGroupMessageSend(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            P2PIm.IMChatModel in = P2PIm.IMChatModel.parseFrom(wrapper.getData());
            String groupId = in.getReceiverId();
            if (groupId.isEmpty()) {
                writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(wrapper.getSeq())
                    .setCommand(ImCommands.INVALID_DATA)
                    .setData(ByteString.copyFromUtf8("receiver_id(group_id) required"))
                    .build());
                return;
            }
            java.util.Set<String> members = ImMemory.groupMemberIds(groupId);
            if (members.isEmpty() || !members.contains(in.getSenderId())) {
                writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(wrapper.getSeq())
                    .setCommand(ImCommands.INVALID_DATA)
                    .setData(ByteString.copyFromUtf8("not a member"))
                    .build());
                return;
            }

            P2PIm.IMChatModel msg = ImMemory.normalizeChat(in);
            if (msg.getReceiverType().isEmpty()) {
                msg = P2PIm.IMChatModel.newBuilder(msg).setReceiverType("GROUP").build();
            }
            msg = persistImFileIfNeeded(msg, groupId);
            ImMemory.appendGroupHistory(msg);

            for (String uid : members) {
                if (uid.equals(msg.getSenderId())) continue;
                ChannelHandlerContext receiver = ImMemory.onlineCtx(uid);
                if (receiver == null) continue;
                writeEncrypted(receiver, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(0)
                    .setCommand(ImCommands.IM_GROUP_MESSAGE_RECEIVE)
                    .setData(msg.toByteString())
                    .build());
            }

            P2PIm.IMChatAck ack = P2PIm.IMChatAck.newBuilder()
                .setMsgId(msg.getMsgId())
                .setUserId(msg.getSenderId())
                .setTimestamp(System.currentTimeMillis())
                .setAckType("DELIVERED")
                .setPeerId(groupId)
                .build();
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.STD_OK)
                .setData(ack.toByteString())
                .build());
        } catch (Exception e) {
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.INVALID_DATA)
                .setData(ByteString.copyFromUtf8(String.valueOf(e.getMessage())))
                .build());
        }
    }

    private void handleImGroupDismiss(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            P2PIm.IMGroupDismissRequest r = P2PIm.IMGroupDismissRequest.parseFrom(wrapper.getData());
            java.util.Set<String> members = new java.util.HashSet<>(ImMemory.groupMemberIds(r.getGroupId()));
            ImMemory.dismissGroup(r.getOperatorId(), r.getGroupId());
            P2PIm.IMSystemEvent evt = P2PIm.IMSystemEvent.newBuilder()
                .setType("GROUP_DISMISSED")
                .setGroupId(r.getGroupId())
                .setOperatorId(r.getOperatorId())
                .setTargetId("")
                .setTimestamp(System.currentTimeMillis())
                .setMessage("group dismissed")
                .build();
            for (String uid : members) {
                ChannelHandlerContext peer = ImMemory.onlineCtx(uid);
                if (peer == null) continue;
                writeEncrypted(peer, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(0)
                    .setCommand(ImCommands.IM_SYSTEM_STATUS)
                    .setData(evt.toByteString())
                    .build());
            }
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.STD_OK)
                .setData(ByteString.EMPTY)
                .build());
        } catch (Exception e) {
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.INVALID_DATA)
                .setData(ByteString.copyFromUtf8(String.valueOf(e.getMessage())))
                .build());
        }
    }

    private void handleImGroupRemoveMember(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            P2PIm.IMGroupRemoveMemberRequest r = P2PIm.IMGroupRemoveMemberRequest.parseFrom(wrapper.getData());
            ImMemory.removeMember(r.getOperatorId(), r.getGroupId(), r.getMemberId());
            P2PIm.IMSystemEvent evt = P2PIm.IMSystemEvent.newBuilder()
                .setType("GROUP_MEMBER_REMOVED")
                .setGroupId(r.getGroupId())
                .setOperatorId(r.getOperatorId())
                .setTargetId(r.getMemberId())
                .setTimestamp(System.currentTimeMillis())
                .setMessage("removed from group")
                .build();
            java.util.Set<String> members = ImMemory.groupMemberIds(r.getGroupId());
            for (String uid : members) {
                ChannelHandlerContext peer = ImMemory.onlineCtx(uid);
                if (peer == null) continue;
                writeEncrypted(peer, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(0)
                    .setCommand(ImCommands.IM_SYSTEM_STATUS)
                    .setData(evt.toByteString())
                    .build());
            }
            ChannelHandlerContext target = ImMemory.onlineCtx(r.getMemberId());
            if (target != null) {
                writeEncrypted(target, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(0)
                    .setCommand(ImCommands.IM_SYSTEM_STATUS)
                    .setData(evt.toByteString())
                    .build());
            }
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.STD_OK)
                .setData(ByteString.EMPTY)
                .build());
        } catch (Exception e) {
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.INVALID_DATA)
                .setData(ByteString.copyFromUtf8(String.valueOf(e.getMessage())))
                .build());
        }
    }

    private void handleImGroupUpdateInfo(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            P2PIm.IMGroupUpdateInfoRequest r = P2PIm.IMGroupUpdateInfoRequest.parseFrom(wrapper.getData());
            P2PIm.IMGroupModel out = ImMemory.updateGroup(r.getOperatorId(), r);
            P2PIm.IMSystemEvent evt = P2PIm.IMSystemEvent.newBuilder()
                .setType("GROUP_INFO_UPDATED")
                .setGroupId(out.getGroupId())
                .setOperatorId(r.getOperatorId())
                .setTargetId("")
                .setTimestamp(System.currentTimeMillis())
                .setMessage("group info updated")
                .build();
            java.util.Set<String> members = ImMemory.groupMemberIds(out.getGroupId());
            for (String uid : members) {
                ChannelHandlerContext peer = ImMemory.onlineCtx(uid);
                if (peer == null) continue;
                writeEncrypted(peer, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(0)
                    .setCommand(ImCommands.IM_SYSTEM_STATUS)
                    .setData(evt.toByteString())
                    .build());
            }
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.STD_OK)
                .setData(out.toByteString())
                .build());
        } catch (Exception e) {
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.INVALID_DATA)
                .setData(ByteString.copyFromUtf8(String.valueOf(e.getMessage())))
                .build());
        }
    }

    private void handleImGroupSetAdmin(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            P2PIm.IMGroupSetAdminRequest r = P2PIm.IMGroupSetAdminRequest.parseFrom(wrapper.getData());
            P2PIm.IMGroupModel out = ImMemory.setAdmin(r.getOperatorId(), r.getGroupId(), r.getMemberId(), r.getIsAdmin());
            P2PIm.IMSystemEvent evt = P2PIm.IMSystemEvent.newBuilder()
                .setType("GROUP_ROLE_CHANGED")
                .setGroupId(r.getGroupId())
                .setOperatorId(r.getOperatorId())
                .setTargetId(r.getMemberId())
                .setTimestamp(System.currentTimeMillis())
                .setMessage(r.getIsAdmin() ? "set admin" : "unset admin")
                .build();
            java.util.Set<String> members = ImMemory.groupMemberIds(r.getGroupId());
            for (String uid : members) {
                ChannelHandlerContext peer = ImMemory.onlineCtx(uid);
                if (peer == null) continue;
                writeEncrypted(peer, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(0)
                    .setCommand(ImCommands.IM_SYSTEM_STATUS)
                    .setData(evt.toByteString())
                    .build());
            }
            ChannelHandlerContext target = ImMemory.onlineCtx(r.getMemberId());
            if (target != null) {
                writeEncrypted(target, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(0)
                    .setCommand(ImCommands.IM_SYSTEM_STATUS)
                    .setData(evt.toByteString())
                    .build());
            }
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.STD_OK)
                .setData(out.toByteString())
                .build());
        } catch (Exception e) {
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.INVALID_DATA)
                .setData(ByteString.copyFromUtf8(String.valueOf(e.getMessage())))
                .build());
        }
    }

    private void handleImChatHistoryRequest(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            P2PIm.IMChatHistoryRequest q = P2PIm.IMChatHistoryRequest.parseFrom(wrapper.getData());
            P2PIm.IMChatHistoryResponse resp = ImMemory.history(q.getUserId(), q.getPeerId(), q.getLimit());
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.IM_CHAT_HISTORY_RESPONSE)
                .setData(resp.toByteString())
                .build());
        } catch (Exception e) {
            writeEncrypted(ctx, P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(ImCommands.INVALID_DATA)
                .setData(ByteString.copyFromUtf8(String.valueOf(e.getMessage())))
                .build());
        }
    }

    private void handleHand(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            P2PControl.Hand hand = P2PControl.Hand.parseFrom(wrapper.getData());
            boolean ok = false;
            for (ByteString kid : hand.getKeyIdsList()) {
                byte[] b = kid.toByteArray();
                if (b.length == 32 && Arrays.equals(b, keyId32)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                ctx.close();
                return;
            }

            int maxPayload = maxFramePayload;
            if (hand.getMaxFramePayload() > 0 && hand.getMaxFramePayload() < maxPayload) {
                maxPayload = hand.getMaxFramePayload();
            }
            if (keyLen <= maxPayload) {
                ctx.close();
                return;
            }
            long maxOffset = keyLen - maxPayload;
            long off = (Integer.toUnsignedLong(rnd.nextInt()) % maxOffset);
            this.offset = off;

            byte[] sessionId = new byte[16];
            rnd.nextBytes(sessionId);
            P2PControl.HandAckPlain ackPlain = P2PControl.HandAckPlain.newBuilder()
                .setSessionId(ByteString.copyFrom(sessionId))
                .setSelectedKeyId(ByteString.copyFrom(keyId32))
                .setOffset((int) off)
                .setMaxFramePayload(maxPayload)
                .setHeaderPolicyId(0)
                .build();

            PublicKey clientPub = RsaPublicKeyDecoder.fromSpkiDer(hand.getClientPubkey().toByteArray());
            byte[] encryptedAck = RsaOaep.encryptSha256(clientPub, ackPlain.toByteArray());

            P2PWrapperOuterClass.P2PWrapper resp = P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(-10002)
                .setData(ByteString.copyFrom(encryptedAck))
                .build();

            writePlain(ctx, resp);
            this.encrypted = true;

            if (!cryptUpdated) {
                cryptUpdated = true;
                ctx.executor().schedule(() -> sendCryptUpdate(ctx, wrapper.getSeq()), 100, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            ctx.close();
        }
    }

    private void sendCryptUpdate(ChannelHandlerContext ctx, int seq) {
        try {
            if (!encrypted || offset < 0) {
                return;
            }
            long maxPayload = maxFramePayload;
            long maxOffset = keyLen - maxPayload;
            if (maxOffset <= 0) {
                return;
            }
            long newOffset = (Integer.toUnsignedLong(rnd.nextInt()) % maxOffset);
            this.offset = newOffset;

            P2PControl.CryptUpdate cu = P2PControl.CryptUpdate.newBuilder()
                .setKeyId(ByteString.copyFrom(keyId32))
                .setOffset((int) newOffset)
                .setEffectiveFromSeq(0)
                .build();
            P2PWrapperOuterClass.P2PWrapper msg = P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(seq)
                .setCommand(-10010)
                .setData(cu.toByteString())
                .build();
            writeEncrypted(ctx, msg);
        } catch (Exception e) {
        }
    }

    private void writePlain(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        byte[] plain = P2PWrapperCodec.encode(wrapper);
        WireHeader h = new WireHeader(plain.length, magic, version, flagsPlain);
        byte[] ws = FrameCodec.encode(h, plain);
        ctx.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(ws)));
    }

    private void writeEncrypted(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        byte[] plain = P2PWrapperCodec.encode(wrapper);
        byte[] cipher = XorCipher.xorWithKeyFile(plain, provider, keyId32, offset);
        WireHeader h = new WireHeader(cipher.length, magic, version, flagsEncrypted);
        byte[] ws = FrameCodec.encode(h, cipher);
        ctx.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(ws)));
    }
}
