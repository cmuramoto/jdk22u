
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class HugeTLBFSUtil {
	static long hps = -1;
	static long freep = -1;

	public static long freePages() {
		if (freep < 0) {
			loadInfo();
		}
		return freep;
	}

	public static long hugePageSize() {
		if (hps < 0) {
			loadInfo();
		}
		return hps;
	}

	static void loadInfo() {
		try (var reader = Files.newBufferedReader(Paths.get("/proc/meminfo"))) {
			reader
					.lines()
					.forEach(l -> {
						if (l.startsWith("HugePages_Free")) {
							var ix = l.indexOf(":");
							freep = Integer.parseInt(l.substring(ix + 1).trim());
						} else if (l.startsWith("Hugepagesize")) {
							var ix = l.indexOf(":");
							var sz = l.substring(ix + 1).trim();
							if (sz.contains("kB")) {
								hps = Long.parseLong(sz.replace("kB", "").trim()) * 1024L;
							} else {
								hps = 0;
								System.out.printf("Unexpected format for pagesize :%s%n", sz);
							}
						}
					});

			System.out.printf("/proc/meminfo => HugePageSize: %d. FreePages: %d%n", hps, freep);
		} catch (Throwable e) {
			hps = 0;
			freep = 0;
			System.out.printf("Error reading /proc/meminfo: %s", e.getMessage());
		}
	}

	public static boolean supports(long ps, long pages) {
		if (ps != hugePageSize()) {
			System.err.printf("Unsupported page size. Expected: %d, Detected: %d%n", ps, hps);
			return false;
		}

		if (pages > freePages()) {
			System.err.printf("Not enough free pages. Required: %d, Available: %d%n", pages, freep);
			return false;
		}

		System.out.printf("Correct page size: %d.%nEnough free pages:%n  Required: %d.%n  Available: %d%n",
				ps, pages, freep);

		return true;
	}

	static boolean usesHugeBlocks(File file, long size) throws Throwable {
		long blksize;

		try (FileInputStream fis = new FileInputStream(file)) {
			// needs --add-opens java.base/sun.nio.ch=ALL-UNNAMED
			var clazz = Class.forName("sun.nio.ch.UnixFileDispatcherImpl");

			var ctor = clazz.getDeclaredConstructor();
			ctor.setAccessible(true);

			var method = clazz.getDeclaredMethod("blockSize", FileDescriptor.class);
			method.setAccessible(true);

			var instance = ctor.newInstance();

			blksize = (long) method.invoke(instance, fis.getFD());
		} catch (Exception e) {
			try {
				blksize = Files.getFileStore(file.toPath()).getBlockSize();
			} catch (IOException ioe) {
				blksize = -1;
			}
		}

		System.out.printf("  BlockSize: %d.%nProceed: %s%n", blksize, blksize == size);

		return blksize == size;
	}

	static boolean usesHugeBlocks(Path path, long size) throws Throwable {
		return usesHugeBlocks(path.toFile(), size);
	}

}