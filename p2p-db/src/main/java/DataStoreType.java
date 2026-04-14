/**
 * 存储系统内部使用的数据类型枚举（用于标识不同数据结构/元数据分类）。
 *
 * <p>注意：该文件位于 default package（无 package 声明），主要用于实验/演示。</p>
 */
public enum DataStoreType {
    MFT(1),
    Mirror(2),
    Replication(3),
    Log(4),
    Root(5),
    ExecEntry(6),
    BadBlock(7),
    FreeBlock(8),
    Quota(9),
    SizeStat(10),
    Extent(11),
    Bitmap(12),
    Names(13),
    SysNames(14),
    Comments(15),
    ACL(16),
    Checksum(17),
    Redundancy(18),
    String(19),
    Bin(20),
    Byte(21),
    Bit(22),
    Int(23),
    Float(24),
    Decimal(25),
    TimeSeries(26),
    Date(27),
    Datetime(28),
    Timestamp(29),
    Time(30),
    ExtendDatetime(31),
    ExtendDate(32),
    ExtendTime(33),
    DecimalTimes(34),
    Catalog(35),
    Schema(36),
    Database(37),
    Table(38),
    Column(39),
    Row(40),
    Function(41),
    Trigger(42),
    Procedure(43),
    CellTable(44),
    DatabaseACL(45),
    TableACL(46),
    ColumnACL(47),
    RowACL(48),
    FunctionACL(49),
    TriggerACL(50),
    ProcedureACL(51),
    Classify(52),
    Users(53),
    Groups(54),
    Roles(55),
    Cache(56),
    Temp(57),
    Cluster(58);

    private int value;

    DataStoreType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
	
	@Override
    public String toString() {
        return "$" + super.name();
    }
}
