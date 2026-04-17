

package javax.net.p2p.filesync;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;

/**
 * 文件变更处理器：在收到文件事件后尝试以共享锁探测文件是否仍在写入中。
 *
 * <p>用于过滤“文件正在被写入”导致的连续变更事件。</p>
 */
@Slf4j
public class FileWatchProcessor implements Runnable {
		private final Path path;

		public FileWatchProcessor(Path path) {
			this.path = path;
		}

		@Override
		public void run() {
			
					File file = new File(path.toAbsolutePath().toString());
					//System.out.println(file.isDirectory() + ":" + file.isFile());
					if (!file.isDirectory()) {//处理文件数据变更，当间隔一定时间无事件发生，视为已安成
						//System.out.println(kind.name() + ": file -> " + path);
						try (FileInputStream fis = new FileInputStream(file);
								FileChannel fileChannel = fis.getChannel()) {

							// 获取文件长度
							long fileLength = fileChannel.size();

							// 尝试获取独占锁
							FileLock lock = fileChannel.lock(0, fileLength, true);
							try {
								// 释放锁
								if (lock.isValid()) {
									lock.release();
								}
								
							} catch (Exception ex) {
								log.error(ex.getMessage());
							} 
						} catch (IOException e) {
							log.error("忽略更改事件：文件正在被其他线程占用/写入 -> {}", file);
						}
						
					} 

				

			
		}
	}
