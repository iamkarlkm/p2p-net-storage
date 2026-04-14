package db;

/**
 * 线段检测/拟合过程中使用的数据结构（保存偏差与点集）。
 *
 * <p>该文件为实验用途。</p>
 */
public class LineNode {

    //deviation from the line
    int left;
    int right;

    int total;
    int[] points;

}
