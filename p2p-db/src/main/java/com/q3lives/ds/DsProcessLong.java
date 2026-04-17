package ds;

/**
 * 处理 long 类型数据的函数式接口。
 */
public interface DsProcessLong {

    /**
     * 处理基本类型 long
     * @param value 输入的值
     * @param index 当前元素的索引
     * @return complete 如果返回 true，表示停止后续处理（中断遍历）；返回 false 表示继续处理。
     */
    boolean process(long value,int index);


}
