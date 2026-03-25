

package javax.net.p2p.exception;

/**
 *
 * @author karl
 */
public class DataLengthLimitedException extends RuntimeException {

    public DataLengthLimitedException() {
    }

    public DataLengthLimitedException(String string) {
        super(string);
    }

	public DataLengthLimitedException(Throwable cause) {
        super(cause);
    }
}
