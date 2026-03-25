
package ds;

/**
 * 数据读取不足异常。
 * <p>
 * 当读取的数据量少于预期时抛出。
 * </p>
 */
public class DsDataReadingLessThanException extends RuntimeException implements DsException{

    @Override
    public int getCode() {
        return 2;
    }

}
