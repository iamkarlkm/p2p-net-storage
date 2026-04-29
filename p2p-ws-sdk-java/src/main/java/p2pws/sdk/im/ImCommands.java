package p2pws.sdk.im;

public final class ImCommands {
    private ImCommands() {}

    public static final int STD_ERROR = -1;
    public static final int INVALID_DATA = -2;
    public static final int STD_OK = 6;

    public static final int IM_USER_LOGIN = 10000;
    public static final int IM_USER_LOGOUT = 10001;
    public static final int IM_USER_LIST = 10002;

    public static final int IM_CHAT_SEND = 11000;
    public static final int IM_CHAT_RECEIVE = 11001;
    public static final int IM_CHAT_ACK = 11002;
    public static final int IM_CHAT_STATUS_UPDATE = 11003;
    public static final int IM_CHAT_HISTORY_REQUEST = 11004;
    public static final int IM_CHAT_HISTORY_RESPONSE = 11005;

    public static final int IM_GROUP_CREATE = 12000;
    public static final int IM_GROUP_DISMISS = 12001;
    public static final int IM_GROUP_JOIN = 12002;
    public static final int IM_GROUP_LEAVE = 12003;
    public static final int IM_GROUP_LIST = 12004;
    public static final int IM_GROUP_MEMBERS = 12005;
    public static final int IM_GROUP_MESSAGE_SEND = 12006;
    public static final int IM_GROUP_MESSAGE_RECEIVE = 12007;
    public static final int IM_GROUP_SET_ADMIN = 12008;
    public static final int IM_GROUP_REMOVE_MEMBER = 12009;
    public static final int IM_GROUP_UPDATE_INFO = 12010;

    public static final int IM_SYSTEM_STATUS = 13000;
}
