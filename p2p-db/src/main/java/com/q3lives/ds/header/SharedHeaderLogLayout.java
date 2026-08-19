package com.q3lives.ds.header;

public final class SharedHeaderLogLayout {

    public static final int PAGE_SIZE = 4096;
    public static final int PAGE_HEADER_SIZE = 32;
    public static final int SLOT_CAPACITY_MIN = 64;
    public static final int SLOT_SIZE_DEFAULT = 64;
    public static final int SLOT_SIZE_LARGE = 256;
    public static final int SLOT_SIZE_XL = 512;

    public static final int SLOT_TIER_64 = 0;
    public static final int SLOT_TIER_256 = 1;
    public static final int SLOT_TIER_512 = 2;
    public static final int SLOT_FLAG_TIER_MASK = 0x3;

    public static final int LOG_MAGIC = 0x48444C47; // "HDLG"
    public static final int LOG_VERSION = 1;
    public static final int LOG_FILE_HEADER_SIZE = 64;
    public static final int OFF_LOG_MAGIC = 0;
    public static final int OFF_LOG_VERSION = 4;
    public static final int OFF_LOG_CREATE_EPOCH = 8;
    public static final int OFF_LOG_NEXT_STORE_ID = 16;
    public static final int OFF_LOG_FLAGS = 24;
    public static final int OFF_LOG_SESSION_HIGH = 32;
    public static final int OFF_LOG_SESSION_LOW = 40;

    public static final int OFF_PAGE_MAGIC = 0;
    public static final int OFF_PAGE_CRC32 = 4;
    public static final int OFF_PAGE_SEQ = 8;
    public static final int OFF_PAGE_SLOT_COUNT = 16;
    public static final int OFF_PAGE_FLAGS = 20;

    public static final int SLOT_MAGIC = 0x534C545F; // "SLT_"
    public static final int SLOT_HEADER_SIZE = 24;
    public static final int OFF_SLOT_STORE_ID = 4;
    public static final int OFF_SLOT_SEQ = 12;
    public static final int OFF_SLOT_LEN = 20;
    public static final int OFF_SLOT_FLAGS = 22;

    public static int tierForDirtyEnd(int dirtyEndBytes) {
        if (dirtyEndBytes <= 0) return SLOT_TIER_64;
        if (dirtyEndBytes <= SLOT_SIZE_DEFAULT) return SLOT_TIER_64;
        if (dirtyEndBytes <= SLOT_SIZE_LARGE) return SLOT_TIER_256;
        return SLOT_TIER_512;
    }

    public static int payloadSizeForTier(int tier) {
        switch (tier) {
            case SLOT_TIER_64: return SLOT_SIZE_DEFAULT;
            case SLOT_TIER_256: return SLOT_SIZE_LARGE;
            default: return SLOT_SIZE_XL;
        }
    }

    public static int maxSlotsForPage(int slotPayloadSize) {
        if (slotPayloadSize <= 0) return 0;
        int perSlot = SLOT_HEADER_SIZE + slotPayloadSize;
        int usable = PAGE_SIZE - PAGE_HEADER_SIZE;
        if (perSlot > usable) return 1;
        return usable / perSlot;
    }

    private SharedHeaderLogLayout() {
    }
}
