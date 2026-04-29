package p2pws.sdk.im;

import io.netty.channel.ChannelHandlerContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import p2pws.P2PIm;

public final class ImMemory {
    private ImMemory() {}

    private static final int HISTORY_LIMIT = 200;

    private static final ConcurrentHashMap<String, ChannelHandlerContext> ONLINE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ChannelHandlerContext, String> CTX_TO_USER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, P2PIm.IMUserModel> USER_MODELS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Deque<P2PIm.IMChatModel>> HISTORY = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, P2PIm.IMGroupModel> GROUPS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, java.util.Set<String>> GROUP_MEMBERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, java.util.Set<String>> GROUP_ADMINS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Deque<P2PIm.IMChatModel>> GROUP_HISTORY = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> MSG_SENDER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> MSG_STATUS = new ConcurrentHashMap<>();

    public static void bind(ChannelHandlerContext ctx, P2PIm.IMUserModel user) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(user, "user");
        if (user.getUserId().isEmpty()) {
            throw new IllegalArgumentException("user_id required");
        }
        String prev = CTX_TO_USER.put(ctx, user.getUserId());
        if (prev != null && !prev.equals(user.getUserId())) {
            ONLINE.remove(prev, ctx);
            USER_MODELS.remove(prev);
        }
        ONLINE.put(user.getUserId(), ctx);
        USER_MODELS.put(user.getUserId(), user);
    }

    public static void unbind(ChannelHandlerContext ctx) {
        if (ctx == null) return;
        String uid = CTX_TO_USER.remove(ctx);
        if (uid == null) return;
        ONLINE.remove(uid, ctx);
        USER_MODELS.remove(uid);
    }

    public static String userIdOf(ChannelHandlerContext ctx) {
        if (ctx == null) return null;
        return CTX_TO_USER.get(ctx);
    }

    public static ChannelHandlerContext onlineCtx(String userId) {
        if (userId == null) return null;
        return ONLINE.get(userId);
    }

    public static P2PIm.IMUserListResponse listUsers() {
        List<P2PIm.IMUserModel> items = new ArrayList<>();
        for (P2PIm.IMUserModel m : USER_MODELS.values()) {
            items.add(m);
        }
        return P2PIm.IMUserListResponse.newBuilder().addAllItems(items).build();
    }

    public static P2PIm.IMChatModel normalizeChat(P2PIm.IMChatModel in) {
        if (in == null) {
            throw new IllegalArgumentException("chat payload required");
        }
        if (in.getSenderId().isEmpty() || in.getReceiverId().isEmpty()) {
            throw new IllegalArgumentException("sender_id and receiver_id required");
        }
        String msgId = in.getMsgId().isEmpty() ? UUID.randomUUID().toString() : in.getMsgId();
        long ts = in.getTimestamp() <= 0 ? System.currentTimeMillis() : in.getTimestamp();
        return P2PIm.IMChatModel.newBuilder(in)
            .setMsgId(msgId)
            .setTimestamp(ts)
            .build();
    }

    public static void appendHistory(P2PIm.IMChatModel msg) {
        String key = pairKey(msg.getSenderId(), msg.getReceiverId());
        Deque<P2PIm.IMChatModel> q = HISTORY.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(msg);
            while (q.size() > HISTORY_LIMIT) {
                q.removeFirst();
            }
        }
        MSG_SENDER.put(msg.getMsgId(), msg.getSenderId());
        MSG_STATUS.put(msg.getMsgId(), "DELIVERED");
    }

    public static P2PIm.IMChatHistoryResponse history(String userId, String peerId, int limit) {
        if (userId == null || userId.isEmpty() || peerId == null || peerId.isEmpty()) {
            throw new IllegalArgumentException("user_id and peer_id required");
        }
        int lim = limit <= 0 ? 50 : Math.min(limit, HISTORY_LIMIT);
        if (GROUPS.containsKey(peerId)) {
            return tailHistory(GROUP_HISTORY.get(peerId), lim);
        }
        String key = pairKey(userId, peerId);
        return tailHistory(HISTORY.get(key), lim);
    }

    public static String msgSender(String msgId) {
        if (msgId == null) return null;
        return MSG_SENDER.get(msgId);
    }

    public static P2PIm.IMGroupModel createGroup(String ownerId, P2PIm.IMGroupModel in) {
        if (ownerId == null || ownerId.isEmpty()) {
            throw new IllegalArgumentException("owner_id required");
        }
        if (in == null) {
            throw new IllegalArgumentException("group payload required");
        }
        String groupId = in.getGroupId().isEmpty() ? ("g_" + UUID.randomUUID().toString()) : in.getGroupId();
        java.util.Set<String> admins = GROUP_ADMINS.computeIfAbsent(groupId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        admins.clear();
        P2PIm.IMGroupModel out = P2PIm.IMGroupModel.newBuilder(in)
            .setGroupId(groupId)
            .setOwnerId(in.getOwnerId().isEmpty() ? ownerId : in.getOwnerId())
            .clearAdminIds()
            .addAllAdminIds(admins)
            .build();
        GROUPS.put(groupId, out);
        java.util.Set<String> members = GROUP_MEMBERS.computeIfAbsent(groupId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        members.add(ownerId);
        return out;
    }

    public static void joinGroup(String userId, String groupId) {
        if (userId == null || userId.isEmpty() || groupId == null || groupId.isEmpty()) {
            throw new IllegalArgumentException("user_id and group_id required");
        }
        if (!GROUPS.containsKey(groupId)) {
            throw new IllegalArgumentException("group not found");
        }
        java.util.Set<String> members = GROUP_MEMBERS.computeIfAbsent(groupId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        members.add(userId);
    }

    public static void leaveGroup(String userId, String groupId) {
        if (userId == null || userId.isEmpty() || groupId == null || groupId.isEmpty()) {
            throw new IllegalArgumentException("user_id and group_id required");
        }
        java.util.Set<String> members = GROUP_MEMBERS.get(groupId);
        if (members == null) return;
        members.remove(userId);
        java.util.Set<String> admins = GROUP_ADMINS.get(groupId);
        if (admins != null) {
            admins.remove(userId);
            syncGroupAdminIds(groupId, admins);
        }
    }

    public static P2PIm.IMGroupListResponse listGroups() {
        List<P2PIm.IMGroupModel> items = new ArrayList<>();
        for (P2PIm.IMGroupModel g : GROUPS.values()) {
            items.add(g);
        }
        return P2PIm.IMGroupListResponse.newBuilder().addAllItems(items).build();
    }

    public static P2PIm.IMGroupListResponse listGroupsForUser(String userId) {
        if (userId == null || userId.isEmpty()) {
            return listGroups();
        }
        List<P2PIm.IMGroupModel> items = new ArrayList<>();
        for (P2PIm.IMGroupModel g : GROUPS.values()) {
            java.util.Set<String> members = GROUP_MEMBERS.get(g.getGroupId());
            if (members == null || !members.contains(userId)) continue;
            items.add(g);
        }
        return P2PIm.IMGroupListResponse.newBuilder().addAllItems(items).build();
    }

    public static P2PIm.IMGroupMembersResponse groupMembers(String groupId) {
        if (groupId == null || groupId.isEmpty()) {
            throw new IllegalArgumentException("group_id required");
        }
        if (!GROUPS.containsKey(groupId)) {
            throw new IllegalArgumentException("group not found");
        }
        java.util.Set<String> members = GROUP_MEMBERS.get(groupId);
        List<P2PIm.IMUserModel> items = new ArrayList<>();
        if (members != null) {
            for (String uid : members) {
                P2PIm.IMUserModel m = USER_MODELS.get(uid);
                if (m != null) {
                    items.add(m);
                }
            }
        }
        return P2PIm.IMGroupMembersResponse.newBuilder().setGroupId(groupId).addAllItems(items).build();
    }

    public static java.util.Set<String> groupMemberIds(String groupId) {
        java.util.Set<String> m = GROUP_MEMBERS.get(groupId);
        if (m == null) return java.util.Set.of();
        return m;
    }

    public static void appendGroupHistory(P2PIm.IMChatModel msg) {
        String groupId = msg.getReceiverId();
        if (groupId.isEmpty() || !GROUPS.containsKey(groupId)) {
            throw new IllegalArgumentException("group not found");
        }
        Deque<P2PIm.IMChatModel> q = GROUP_HISTORY.computeIfAbsent(groupId, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(msg);
            while (q.size() > HISTORY_LIMIT) {
                q.removeFirst();
            }
        }
        MSG_SENDER.put(msg.getMsgId(), msg.getSenderId());
        MSG_STATUS.put(msg.getMsgId(), "DELIVERED");
    }

    public static void setStatus(String msgId, String ackType) {
        if (msgId == null || msgId.isEmpty()) return;
        if (ackType == null || ackType.isEmpty()) return;
        MSG_STATUS.put(msgId, ackType);
    }

    public static void dismissGroup(String operatorId, String groupId) {
        if (operatorId == null || operatorId.isEmpty() || groupId == null || groupId.isEmpty()) {
            throw new IllegalArgumentException("operator_id and group_id required");
        }
        P2PIm.IMGroupModel g = GROUPS.get(groupId);
        if (g == null) {
            throw new IllegalArgumentException("group not found");
        }
        if (!operatorId.equals(g.getOwnerId())) {
            throw new IllegalArgumentException("permission denied");
        }
        GROUPS.remove(groupId);
        GROUP_MEMBERS.remove(groupId);
        GROUP_ADMINS.remove(groupId);
        GROUP_HISTORY.remove(groupId);
    }

    public static void removeMember(String operatorId, String groupId, String memberId) {
        if (operatorId == null || operatorId.isEmpty() || groupId == null || groupId.isEmpty() || memberId == null || memberId.isEmpty()) {
            throw new IllegalArgumentException("operator_id/group_id/member_id required");
        }
        P2PIm.IMGroupModel g = GROUPS.get(groupId);
        if (g == null) {
            throw new IllegalArgumentException("group not found");
        }
        java.util.Set<String> admins = GROUP_ADMINS.get(groupId);
        boolean isOwner = operatorId.equals(g.getOwnerId());
        boolean isAdmin = admins != null && admins.contains(operatorId);
        if (!isOwner && !isAdmin) {
            throw new IllegalArgumentException("permission denied");
        }
        if (memberId.equals(g.getOwnerId())) {
            throw new IllegalArgumentException("cannot remove owner");
        }
        java.util.Set<String> members = GROUP_MEMBERS.get(groupId);
        if (members == null) return;
        members.remove(memberId);
        if (admins != null) {
            admins.remove(memberId);
            syncGroupAdminIds(groupId, admins);
        }
    }

    public static P2PIm.IMGroupModel updateGroup(String operatorId, P2PIm.IMGroupUpdateInfoRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("payload required");
        }
        String groupId = req.getGroupId();
        if (operatorId == null || operatorId.isEmpty() || groupId == null || groupId.isEmpty()) {
            throw new IllegalArgumentException("operator_id and group_id required");
        }
        P2PIm.IMGroupModel g = GROUPS.get(groupId);
        if (g == null) {
            throw new IllegalArgumentException("group not found");
        }
        java.util.Set<String> admins = GROUP_ADMINS.get(groupId);
        boolean isOwner = operatorId.equals(g.getOwnerId());
        boolean isAdmin = admins != null && admins.contains(operatorId);
        if (!isOwner && !isAdmin) {
            throw new IllegalArgumentException("permission denied");
        }
        P2PIm.IMGroupModel.Builder b = P2PIm.IMGroupModel.newBuilder(g);
        if (!req.getName().isEmpty()) b.setName(req.getName());
        if (!req.getAvatar().isEmpty()) b.setAvatar(req.getAvatar());
        if (!req.getNotice().isEmpty()) b.setNotice(req.getNotice());
        if (!req.getExtra().isEmpty()) b.setExtra(req.getExtra());
        b.clearAdminIds();
        if (admins != null) {
            b.addAllAdminIds(admins);
        }
        P2PIm.IMGroupModel out = b.build();
        GROUPS.put(groupId, out);
        return out;
    }

    public static P2PIm.IMGroupModel setAdmin(String operatorId, String groupId, String memberId, boolean isAdmin) {
        if (operatorId == null || operatorId.isEmpty() || groupId == null || groupId.isEmpty() || memberId == null || memberId.isEmpty()) {
            throw new IllegalArgumentException("operator_id/group_id/member_id required");
        }
        P2PIm.IMGroupModel g = GROUPS.get(groupId);
        if (g == null) {
            throw new IllegalArgumentException("group not found");
        }
        if (!operatorId.equals(g.getOwnerId())) {
            throw new IllegalArgumentException("permission denied");
        }
        java.util.Set<String> members = GROUP_MEMBERS.get(groupId);
        if (members == null || !members.contains(memberId)) {
            throw new IllegalArgumentException("member not in group");
        }
        java.util.Set<String> admins = GROUP_ADMINS.computeIfAbsent(groupId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        if (isAdmin) {
            admins.add(memberId);
        } else {
            admins.remove(memberId);
        }
        return syncGroupAdminIds(groupId, admins);
    }

    private static P2PIm.IMGroupModel syncGroupAdminIds(String groupId, java.util.Set<String> admins) {
        P2PIm.IMGroupModel g = GROUPS.get(groupId);
        if (g == null) {
            throw new IllegalArgumentException("group not found");
        }
        P2PIm.IMGroupModel out = P2PIm.IMGroupModel.newBuilder(g).clearAdminIds().addAllAdminIds(admins).build();
        GROUPS.put(groupId, out);
        return out;
    }

    private static P2PIm.IMChatHistoryResponse tailHistory(Deque<P2PIm.IMChatModel> q, int lim) {
        if (q == null) {
            return P2PIm.IMChatHistoryResponse.newBuilder().build();
        }
        List<P2PIm.IMChatModel> items = new ArrayList<>(lim);
        synchronized (q) {
            int skipped = Math.max(0, q.size() - lim);
            int i = 0;
            for (P2PIm.IMChatModel m : q) {
                if (i++ < skipped) continue;
                String st = MSG_STATUS.get(m.getMsgId());
                if (st != null && !st.isEmpty()) {
                    String extra = mergeStatusExtra(m.getExtra(), st);
                    if (!extra.equals(m.getExtra())) {
                        items.add(P2PIm.IMChatModel.newBuilder(m).setExtra(extra).build());
                        continue;
                    }
                }
                items.add(m);
            }
        }
        return P2PIm.IMChatHistoryResponse.newBuilder().addAllItems(items).build();
    }

    private static String mergeStatusExtra(String extra, String status) {
        String key = "|status:";
        int idx = extra.indexOf(key);
        if (idx < 0) return extra + key + status;
        int end = extra.indexOf("|", idx + 1);
        if (end < 0) return extra.substring(0, idx) + key + status;
        return extra.substring(0, idx) + key + status + extra.substring(end);
    }

    private static String pairKey(String a, String b) {
        if (a.compareTo(b) <= 0) return a + "|" + b;
        return b + "|" + a;
    }
}
