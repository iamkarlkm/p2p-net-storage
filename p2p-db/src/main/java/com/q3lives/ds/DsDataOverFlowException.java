
package ds;

/**
 * 数据溢出异常。
 * <p>
 * 当尝试写入的数据大小超过可用容量或限制时抛出。
 * </p>
 */
public class DsDataOverFlowException extends RuntimeException implements DsException{

    @Override
    public int getCode() {
        return 1;
    }

}
