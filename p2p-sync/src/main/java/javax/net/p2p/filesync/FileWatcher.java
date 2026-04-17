
package javax.net.p2p.filesync;

import org.apache.commons.io.monitor.FileAlterationObserver;
import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;

import java.io.File;

/**
 * 基于 commons-io 的文件监听示例入口。
 *
 * <p>该类为演示/实验用途。</p>
 */
public class FileWatcher {
    public static void main(String[] args) throws Exception {
        File directory = new File("D:\\data\\cms\\sync");
        FileAlterationObserver observer = new FileAlterationObserver(directory);

        FileAlterationListenerAdaptor listener = new FileAlterationListenerAdaptor() {
            @Override
            public void onFileCreate(File file) {
                System.out.println("Created: " + file);
            }

            @Override
            public void onFileChange(File file) {
                System.out.println("Changed: " + file);
            }

            @Override
            public void onFileDelete(File file) {
                System.out.println("Deleted: " + file);
            }
        };

        observer.addListener(listener);
        observer.initialize();
        try {
            while (true) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
