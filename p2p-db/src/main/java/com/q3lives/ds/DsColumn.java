
package ds;

/**
 * 数据列定义类。
 * <p>
 * 用于描述数据单元中的列结构。
 * </p>
 * @author karl
 */
public class DsColumn {
    
    /** 单元索引 */
    public int unitIndex;
    
    /** 数据偏移量 */
    public int dataOffset;
    
    /** 数据类型 */
    public int dataType;
    
    /** 数据大小 */
    public int dataSize;

    public DsColumn(int unitIndex, int dataOffset, int dataType, int dataSize) {
        this.unitIndex = unitIndex;
        this.dataOffset = dataOffset;
        this.dataType = dataType;
        this.dataSize = dataSize;
    }
    
    public DsColumn(int unitIndex, int dataOffset, int dataType) {
        this.unitIndex = unitIndex;
        this.dataOffset = dataOffset;
        this.dataType = dataType;
    }
    
    public DsColumn(int dataOffset, int dataType) {
        this.unitIndex = 0;
        this.dataOffset = dataOffset;
        this.dataType = dataType;
    }
    
}
