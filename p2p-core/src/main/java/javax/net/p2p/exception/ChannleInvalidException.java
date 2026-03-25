

package javax.net.p2p.exception;

/**
 *
 * @author karl
 */
public class ChannleInvalidException extends RuntimeException {

    public ChannleInvalidException() {
    }

    public ChannleInvalidException(String string) {
        super(string);
    }
	
	public ChannleInvalidException(Throwable cause) {
        super(cause);
    }

}
