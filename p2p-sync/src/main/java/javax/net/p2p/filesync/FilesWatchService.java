
package javax.net.p2p.filesync;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.extern.slf4j.Slf4j;

/**
 * 基于 NIO WatchService 的目录监听服务。
 *
 * <p>支持 include/exclude 规则与线程池调度，用于触发后续同步处理。</p>
 */
@Slf4j
public class FilesWatchService {

	public static final ExecutorService FILE_SYNC_POOLS = new ThreadPoolExecutor(16, 32,
			60L, TimeUnit.SECONDS,
			new ArrayBlockingQueue<>(4096));

	private final WatchService watchService;

	private final ExecutorService executorService;

	private final WatchEvent.Kind<?>[] watchEvents;

	/**
	 * 第一优先级
	 */
	private String[] excludeStartsAnd;

	/**
	 * 第一优先级
	 */
	private String[] excludeEndsAnd;

	/**
	 * 第二优先级
	 */
	private String[] excludeStarts;

	/**
	 * 第三优先级
	 */
	private String[] excludeEnds;

	/**
	 * 第四优先级
	 */
	private String[] includeStartsAnd;

	/**
	 * 第四优先级
	 */
	private String[] includeEndsAnd;

	/**
	 * 第五优先级
	 */
	private String[] includeStarts;

	/**
	 * 第六优先级
	 */
	private String[] includeEnds;

	private final Path dir;

	public FilesWatchService(ExecutorService executorService, Path dir, WatchEvent.Kind<?>... watchEvents) {
		try {
			this.watchService = FileSystems.getDefault().newWatchService();
			this.executorService = executorService;
			//			this.executorService = new ThreadPoolExecutor(16, 32,
			//					60L, TimeUnit.SECONDS,
			//					new ArrayBlockingQueue<>(4096));
		} catch (IOException ex) {
			throw new RuntimeException(ex);
		}
		if (watchEvents == null) {
			this.watchEvents = new WatchEvent.Kind<?>[] { StandardWatchEventKinds.ENTRY_CREATE,
					StandardWatchEventKinds.ENTRY_MODIFY,
					StandardWatchEventKinds.ENTRY_DELETE };
		} else {
			this.watchEvents = watchEvents;
		}
		this.dir = dir;

	}

	public void startWatching() throws IOException {
		this.dir.register(watchService, watchEvents);
		//		dir.register(watchService, 
		//                StandardWatchEventKinds.ENTRY_DELETE,
		//                StandardWatchEventKinds.ENTRY_MODIFY);

		while (true) {
			WatchKey key;
			try {
				key = watchService.poll(500, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				watchService.close();
				Thread.currentThread().interrupt();
				return;
			}

			if (key == null) {
				continue;
			}
			for (WatchEvent<?> event : key.pollEvents()) {
				WatchEvent.Kind<?> kind = event.kind();

				if (kind == StandardWatchEventKinds.OVERFLOW) {
					continue;
				}
				WatchEvent<Path> ev = (WatchEvent<Path>) event;
				Path path = ev.context();
				String name = path.getFileName().toString();
				boolean endEvent = true;
				if (excludeStartsAnd != null && excludeEndsAnd != null) {
					for (String str : excludeStartsAnd) {
						if (name.startsWith(str)) {
							for (String strAnd : excludeEndsAnd) {
								if (name.endsWith(strAnd)) {
									endEvent =true;break;
								}
							}
							if(endEvent) break;
						}
					}
					if(endEvent) continue;
				}
				
				if (excludeStarts != null) {
					for (String str : excludeStarts) {
						if (path.getFileName().startsWith(str)) {
							continue;
						}
					}
				}

				if (excludeEnds != null) {
					for (String str : excludeEnds) {
						if (path.getFileName().endsWith(str)) {
							continue;
						}
					}
				}

				if (includeStarts != null) {
					for (String str : includeStarts) {
						if (name.startsWith(str)) {
							executorService.submit(new FileWatchProcessor(path));
						}
					}
				}
				System.out.println("");
			}
			executorService.submit(new EventProcessor(key));
		}
	}

	private class EventProcessor implements Runnable {
		private final WatchKey key;

		public EventProcessor(WatchKey key) {
			this.key = key;
		}

		@Override
		public void run() {
			for (WatchEvent<?> event : key.pollEvents()) {
				WatchEvent.Kind<?> kind = event.kind();

				if (kind == StandardWatchEventKinds.OVERFLOW) {
					continue;
				}

				if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
					WatchEvent<Path> ev = (WatchEvent<Path>) event;
					Path path = ev.context();
					File file = new File(path.toAbsolutePath().toString());
					//System.out.println(file.isDirectory() + ":" + file.isFile());
					if (!file.isDirectory()) {//处理文件数据变更，当间隔一定时间无事件发生，视为已安成
						System.out.println(kind.name() + ": file -> " + path);
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
							continue;
						}

					} else {
						System.out.println(kind.name() + ": dir -> " + file);

					}
				}

				if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
					WatchEvent<Path> ev = (WatchEvent<Path>) event;
					Path path = ev.context();
					File file = new File(path.toAbsolutePath().toString());
					if (file.isDirectory()) {//处理子目录创建
						System.out.println(kind.name() + ": file -> " + path);
						//						 WatchKey oldKey = UPDATE_EVENT_CACHE.getIfPresent(path);
						//						 if(oldKey!=null){
						//							 oldKey.reset();
						//						 }
					}
				}

				//				
				//
				//                WatchEvent<Path> ev = (WatchEvent<Path>) event;
				//                Path fileName = ev.context();
				//                System.out.println(kind.name() + ": " + fileName);

				// Process event (create, modify, delete)
			}

			boolean valid = key.reset();
			if (!valid) {
				System.out.println("Key has been unregistered");
			}
		}
	}

	public static void shutdown() {
		System.out.println("FILE_SYNC_POOLS release...");
		FILE_SYNC_POOLS.shutdown();
		try {
			if (!FILE_SYNC_POOLS.awaitTermination(60, TimeUnit.SECONDS)) {
				FILE_SYNC_POOLS.shutdownNow();
			}
		} catch (InterruptedException e) {
			FILE_SYNC_POOLS.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		FilesWatchService service = new FilesWatchService(FILE_SYNC_POOLS, Paths.get("D:\\data\\cms\\sync"));

		service.startWatching();

		// Graceful shutdown example
		Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown()));
	}
}
