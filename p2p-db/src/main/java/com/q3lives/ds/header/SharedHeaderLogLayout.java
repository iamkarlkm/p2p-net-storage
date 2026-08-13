package com.q3lives.ds.header;

public final class SharedHeaderLogLayout {

    public static final int PAGE_SIZE = 4096;
    public static final int PAGE_HEADER_SIZE = 32;
    public static final int SLOT_CAPACITY_MIN = 64;
    public static final int SLOT_SIZE_DEFAULT = 64;
    public static final int SLOT_SIZE_LARGE = 256;
    public static final int SLOT_SIZE_XL = 512;

    public static final int LOG_MAGIC = 0x48444C47; // "HDLG"
    public static final int LOG_VERSION = 1;
    public static final int LOG_FILE_HEADER_SIZE = 64;
    public static final int OFF_LOG_MAGIC = 0;
    public static final int OFF_LOG_VERSION = 4;
    public static final int OFF_LOG_CREATE_EPOCH = 8;
    public static final int OFF_LOG_NEXT_STORE_ID = 16;
    public static final int OFF_LOG_FLAGS = 24;

    public static final int OFF_PAGE_MAGIC = 0;
    public static final int OFF_PAGE_CRC32 = 4;
    public static final int OFF_PAGE_SEQ = 8;
    public static final int OFF_PAGE_SLOT_COUNT = 16;
    public static final int OFF_PAGE_FLAGS = 20;

    public static final int SLOT_MAGIC = 0x534C545F; // "SLT_"
    public static final int SLOT_HEADER_SIZE = 24;
    public static final int OFF_SLOT_STORE_ID = 0;
    public static final int OFF_SLOT_SEQ = 8;
    public static final int OFF_SLOT_LEN = 16;
    public static final int OFF_SLOT_FLAGS = 18;
    public static final int OFF_SLOT_CRC16 = 20;

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
