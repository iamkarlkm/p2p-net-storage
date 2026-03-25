

package javax.net.p2p.exception;

/**
 *
 * @author karl
 */
public class RequestTimeoutException extends RuntimeException {

    public RequestTimeoutException() {
    }

    public RequestTimeoutException(String string) {
        super(string);
    }
	
	public RequestTimeoutException(Throwable cause) {
        super(cause);
    }

}
