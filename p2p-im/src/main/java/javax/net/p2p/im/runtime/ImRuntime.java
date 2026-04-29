package javax.net.p2p.im.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.p2p.model.IMChatModel;
import javax.net.p2p.model.IMGroupModel;
import javax.net.p2p.model.IMUserModel;

public final class ImRuntime {
    private ImRuntime() {}

    private static final int HISTORY_LIMIT = 200;

    private static final ConcurrentHashMap<String, IMUserModel> ONLINE_USERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Deque<IMChatModel>> CHAT_HISTORY = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, IMGroupModel> GROUPS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, java.util.Set<String>> GROUP_MEMBERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, java.util.Set<String>> GROUP_ADMINS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Deque<IMChatModel>> GROUP_HISTORY = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> MSG_SENDER_INDEX = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> MSG_STATUS_INDEX = new ConcurrentHashMap<>();

    public static IMUserModel login(IMUserModel user) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new IllegalArgumentException("userId required");
        }
        if (user.getStatus() == null || user.getStatus().isEmpty()) {
            user.setStatus("ONLINE");
        }
        ONLINE_USERS.put(user.getUserId(), user);
        return user;
    }

    public static void logout(String userId) {
        if (userId == null || userId.isEmpty()) return;
        ONLINE_USERS.remove(userId);
    }

    public static List<IMUserModel> listUsers() {
        return new ArrayList<>(ONLINE_USERS.values());
    }

    public static IMUserModel getOnlineUser(String userId) {
        if (userId == null) return null;
        return ONLINE_USERS.get(userId);
    }

    public static IMChatModel normalizeChat(IMChatModel in) {
        if (in == null) {
            throw new IllegalArgumentException("chat message required");
        }
        if (in.getSenderId() == null || in.getSenderId().isEmpty() || in.getReceiverId() == null || in.getReceiverId().isEmpty()) {
            throw new IllegalArgumentException("senderId and receiverId required");
        }
        if (in.getMsgId() == null || in.getMsgId().isEmpty()) {
            in.setMsgId(UUID.randomUUID().toString());
        }
        if (in.getTimestamp() <= 0) {
            in.setTimestamp(System.currentTimeMillis());
        }
        return in;
    }

    public static void appendChat(IMChatModel msg) {
        String key = pairKey(msg.getSenderId(), msg.getReceiverId());
        Deque<IMChatModel> q = CHAT_HISTORY.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(msg);
            while (q.size() > HISTORY_LIMIT) {
                q.removeFirst();
            }
        }
        MSG_SENDER_INDEX.put(msg.getMsgId(), msg.getSenderId());
        MSG_STATUS_INDEX.put(msg.getMsgId(), "DELIVERED");
    }

    public static List<IMChatModel> history(String userId, String peerId, int limit) {
        if (userId == null || userId.isEmpty() || peerId == null || peerId.isEmpty()) {
            throw new IllegalArgumentException("userId and peerId required");
        }
        int lim = limit <= 0 ? 50 : Math.min(limit, HISTORY_LIMIT);
        IMGroupModel g = GROUPS.get(peerId);
        if (g != null) {
            Deque<IMChatModel> q = GROUP_HISTORY.get(peerId);
            List<IMChatModel> out = tail(q, lim);
            applyStatusInPlace(out);
            return out;
        }
        String key = pairKey(userId, peerId);
        Deque<IMChatModel> q = CHAT_HISTORY.get(key);
        List<IMChatModel> out = tail(q, lim);
        applyStatusInPlace(out);
        return out;
    }

    public static String msgSender(String msgId) {
        if (msgId == null) return null;
        return MSG_SENDER_INDEX.get(msgId);
    }

    public static void clearAll() {
        ONLINE_USERS.clear();
        CHAT_HISTORY.clear();
        GROUPS.clear();
        GROUP_MEMBERS.clear();
        GROUP_ADMINS.clear();
        GROUP_HISTORY.clear();
        MSG_SENDER_INDEX.clear();
        MSG_STATUS_INDEX.clear();
    }

    public static IMGroupModel createGroup(IMGroupModel in) {
        if (in == null || in.getName() == null || in.getName().isEmpty()) {
            throw new IllegalArgumentException("group name required");
        }
        if (in.getGroupId() == null || in.getGroupId().isEmpty()) {
            in.setGroupId("group_" + System.currentTimeMillis());
        }
        if (in.getOwnerId() == null || in.getOwnerId().isEmpty()) {
            throw new IllegalArgumentException("ownerId required");
        }
        in.setCreateTime(System.currentTimeMillis());
        in.setStatus(in.getStatus() == null || in.getStatus().isEmpty() ? "ACTIVE" : in.getStatus());
        GROUPS.put(in.getGroupId(), in);
        java.util.Set<String> members = GROUP_MEMBERS.computeIfAbsent(in.getGroupId(), k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        members.add(in.getOwnerId());
        java.util.Set<String> admins = GROUP_ADMINS.computeIfAbsent(in.getGroupId(), k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        admins.clear();
        syncGroupMembersField(in, members);
        syncGroupAdminsField(in, admins);
        return in;
    }

    public static IMGroupModel joinGroup(String userId, String groupId) {
        if (userId == null || userId.isEmpty() || groupId == null || groupId.isEmpty()) {
            throw new IllegalArgumentException("userId and groupId required");
        }
        IMGroupModel g = GROUPS.get(groupId);
        if (g == null) {
            throw new IllegalArgumentException("group not found");
        }
        java.util.Set<String> members = GROUP_MEMBERS.computeIfAbsent(groupId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        members.add(userId);
        syncGroupMembersField(g, members);
        java.util.Set<String> admins = GROUP_ADMINS.computeIfAbsent(groupId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        syncGroupAdminsField(g, admins);
        return g;
    }

    public static IMGroupModel leaveGroup(String userId, String groupId) {
        if (userId == null || userId.isEmpty() || groupId == null || groupId.isEmpty()) {
            throw new IllegalArgumentException("userId and groupId required");
        }
        IMGroupModel g = GROUPS.get(groupId);
        if (g == null) {
            throw new IllegalArgumentException("group not found");
        }
        java.util.Set<String> members = GROUP_MEMBERS.get(groupId);
        if (members != null) {
            members.remove(userId);
            syncGroupMembersField(g, members);
        }
        java.util.Set<String> admins = GROUP_ADMINS.get(groupId);
        if (admins != null) {
            admins.remove(userId);
            syncGroupAdminsField(g, admins);
        }
        return g;
    }

    public static List<IMGroupModel> listGroups() {
        return new ArrayList<>(GROUPS.values());
    }

    public static List<IMGroupModel> listGroupsForUser(String userId) {
        if (userId == null || userId.isEmpty()) {
            return listGroups();
        }
        ArrayList<IMGroupModel> out = new ArrayList<>();
        for (IMGroupModel g : GROUPS.values()) {
            if (g == null || g.getGroupId() == null) continue;
            java.util.Set<String> members = GROUP_MEMBERS.get(g.getGroupId());
            if (members == null || !members.contains(userId)) continue;
            out.add(g);
        }
        return out;
    }

    public static IMGroupModel getGroup(String groupId) {
        if (groupId == null) return null;
        return GROUPS.get(groupId);
    }

    public static IMGroupModel getGroupWithMembers(String groupId) {
        IMGroupModel g = getGroup(groupId);
        if (g == null) return null;
        java.util.Set<String> members = GROUP_MEMBERS.get(groupId);
        if (members != null) {
            syncGroupMembersField(g, members);
        }
        return g;
    }

    public static IMChatModel normalizeGroupChat(IMChatModel in) {
        IMChatModel msg = normalizeChat(in);
        if (msg.getReceiverId() == null || msg.getReceiverId().isEmpty()) {
            throw new IllegalArgumentException("groupId required");
        }
        java.util.Set<String> members = GROUP_MEMBERS.get(msg.getReceiverId());
        if (members == null || !members.contains(msg.getSenderId())) {
            throw new IllegalArgumentException("not a member");
        }
        msg.setReceiverType(msg.getReceiverType() == null || msg.getReceiverType().isEmpty() ? "GROUP" : msg.getReceiverType());
        return msg;
    }

    public static void appendGroupChat(IMChatModel msg) {
        String groupId = msg.getReceiverId();
        if (GROUPS.get(groupId) == null) {
            throw new IllegalArgumentException("group not found");
        }
        Deque<IMChatModel> q = GROUP_HISTORY.computeIfAbsent(groupId, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(msg);
            while (q.size() > HISTORY_LIMIT) {
                q.removeFirst();
            }
        }
        MSG_SENDER_INDEX.put(msg.getMsgId(), msg.getSenderId());
        MSG_STATUS_INDEX.put(msg.getMsgId(), "DELIVERED");
    }

    public static void setMsgStatus(String msgId, String ackType) {
        if (msgId == null || msgId.isEmpty() || ackType == null || ackType.isEmpty()) return;
        MSG_STATUS_INDEX.put(msgId, ackType);
    }

    public static void dismissGroup(String operatorId, String groupId) {
        if (operatorId == null || operatorId.isEmpty() || groupId == null || groupId.isEmpty()) {
            throw new IllegalArgumentException("operatorId and groupId required");
        }
        IMGroupModel g = GROUPS.get(groupId);
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
            throw new IllegalArgumentException("operatorId/groupId/memberId required");
        }
        IMGroupModel g = GROUPS.get(groupId);
        if (g == null) {
            throw new IllegalArgumentException("group not found");
        }
        boolean isOwner = operatorId.equals(g.getOwnerId());
        java.util.Set<String> admins = GROUP_ADMINS.get(groupId);
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
        syncGroupMembersField(g, members);
        if (admins != null) {
            admins.remove(memberId);
            syncGroupAdminsField(g, admins);
        }
    }

    public static IMGroupModel updateGroupInfo(String operatorId, IMGroupModel patch) {
        if (patch == null || patch.getGroupId() == null || patch.getGroupId().isEmpty()) {
            throw new IllegalArgumentException("groupId required");
        }
        if (operatorId == null || operatorId.isEmpty()) {
            throw new IllegalArgumentException("operatorId required");
        }
        IMGroupModel g = GROUPS.get(patch.getGroupId());
        if (g == null) {
            throw new IllegalArgumentException("group not found");
        }
        boolean isOwner = operatorId.equals(g.getOwnerId());
        java.util.Set<String> admins = GROUP_ADMINS.get(g.getGroupId());
        boolean isAdmin = admins != null && admins.contains(operatorId);
        if (!isOwner && !isAdmin) {
            throw new IllegalArgumentException("permission denied");
        }
        if (patch.getName() != null && !patch.getName().isEmpty()) g.setName(patch.getName());
        if (patch.getAvatar() != null && !patch.getAvatar().isEmpty()) g.setAvatar(patch.getAvatar());
        if (patch.getAnnouncement() != null && !patch.getAnnouncement().isEmpty()) g.setAnnouncement(patch.getAnnouncement());
        GROUPS.put(g.getGroupId(), g);
        if (admins != null) {
            syncGroupAdminsField(g, admins);
        }
        return g;
    }

    public static IMGroupModel setAdmin(String operatorId, String groupId, String memberId, boolean isAdmin) {
        if (operatorId == null || operatorId.isEmpty() || groupId == null || groupId.isEmpty() || memberId == null || memberId.isEmpty()) {
            throw new IllegalArgumentException("operatorId/groupId/memberId required");
        }
        IMGroupModel g = GROUPS.get(groupId);
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
        syncGroupAdminsField(g, admins);
        return g;
    }

    private static List<IMChatModel> tail(Deque<IMChatModel> q, int lim) {
        if (q == null) {
            return List.of();
        }
        ArrayList<IMChatModel> out = new ArrayList<>(lim);
        synchronized (q) {
            int skipped = Math.max(0, q.size() - lim);
            int i = 0;
            for (IMChatModel m : q) {
                if (i++ < skipped) continue;
                out.add(m);
            }
        }
        return out;
    }

    private static void syncGroupMembersField(IMGroupModel g, java.util.Set<String> members) {
        if (g == null || members == null) return;
        g.setMembers(new java.util.ArrayList<>(members));
        g.setMemberCount(members.size());
    }

    private static void syncGroupAdminsField(IMGroupModel g, java.util.Set<String> admins) {
        if (g == null || admins == null) return;
        g.setAdmins(new java.util.ArrayList<>(admins));
    }

    private static void applyStatusInPlace(List<IMChatModel> items) {
        for (IMChatModel m : items) {
            if (m == null || m.getMsgId() == null) continue;
            String st = MSG_STATUS_INDEX.get(m.getMsgId());
            if (st == null || st.isEmpty()) continue;
            String extra = m.getExtra() == null ? "" : m.getExtra();
            String merged = mergeStatusExtra(extra, st);
            m.setExtra(merged);
        }
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
