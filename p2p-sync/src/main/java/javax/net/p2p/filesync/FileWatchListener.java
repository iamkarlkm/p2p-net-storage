

package javax.net.p2p.filesync;

import java.io.File;

/**
 *
 * @author karl
 */
public interface FileWatchListener {
	
	void process(File file);

}
