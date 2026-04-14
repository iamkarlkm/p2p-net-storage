import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.util.Arrays;
import java.util.List;

/**
 * Dokan (Windows) 的 JNA 绑定接口。
 *
 * <p>提供 DokanMain 入口与所需的 Options/Operations 结构体定义。</p>
 */
public interface DokanLibrary extends StdCallLibrary {
    DokanLibrary INSTANCE = Native.load("dokan2.dll", DokanLibrary.class);

    int DokanMain(DOKAN_OPTIONS options, DOKAN_OPERATIONS operations);

    // 定义 Dokan 选项结构体
    class DOKAN_OPTIONS  extends Structure {
        public int Version;
        public int ThreadCount;
        public String MountPoint;
        public int Options;
        public int Timeout;
        public int AllocationUnitSize;
        public int SectorSize;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("Version", "ThreadCount", "MountPoint", "Options", "Timeout", "AllocationUnitSize", "SectorSize");
        }
    }

    // 定义 Dokan 操作结构体
    class DOKAN_OPERATIONS extends Structure {
        public DokanCreateFile DokanCreateFile;
        public DokanCleanup DokanCleanup;
        public DokanCloseFile DokanCloseFile;
        public DokanReadFile DokanReadFile;
        public DokanWriteFile DokanWriteFile;
        public DokanFlushFileBuffers DokanFlushFileBuffers;
        public DokanGetFileInformation DokanGetFileInformation;
        public DokanFindFiles DokanFindFiles;
        public DokanFindFilesWithPattern DokanFindFilesWithPattern;
        public DokanSetFileAttributes DokanSetFileAttributes;
        public DokanSetFileTime DokanSetFileTime;
        public DokanDeleteFile DokanDeleteFile;
        public DokanDeleteDirectory DokanDeleteDirectory;
        public DokanMoveFile DokanMoveFile;
        public DokanSetEndOfFile DokanSetEndOfFile;
        public DokanSetAllocationSize DokanSetAllocationSize;
        public DokanLockFile DokanLockFile;
        public DokanUnlockFile DokanUnlockFile;
        public DokanGetDiskFreeSpace DokanGetDiskFreeSpace;
        public DokanGetVolumeInformation DokanGetVolumeInformation;
        public DokanGetFileSecurity DokanGetFileSecurity;
        public DokanSetFileSecurity DokanSetFileSecurity;
        public DokanMounted DokanMounted;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList(
                    "DokanCreateFile", "DokanCleanup", "DokanCloseFile", "DokanReadFile", "DokanWriteFile",
                    "DokanFlushFileBuffers", "DokanGetFileInformation", "DokanFindFiles", "DokanFindFilesWithPattern",
                    "DokanSetFileAttributes", "DokanSetFileTime", "DokanDeleteFile", "DokanDeleteDirectory",
                    "DokanMoveFile", "DokanSetEndOfFile", "DokanSetAllocationSize", "DokanLockFile",
                    "DokanUnlockFile", "DokanGetDiskFreeSpace", "DokanGetVolumeInformation",
                    "DokanGetFileSecurity", "DokanSetFileSecurity", "DokanMounted"
            );
        }

        // 定义各种回调接口
        public interface DokanCreateFile extends StdCallCallback {
            int invoke(String fileName, int access, int share, int createDisposition, int flagsAndAttributes, int securityContext, Pointer DokanFileInfo);
        }

        public interface DokanCleanup extends StdCallCallback {
            int invoke(String fileName, Pointer DokanFileInfo);
        }

        public interface DokanCloseFile extends StdCallCallback {
            int invoke(String fileName, Pointer DokanFileInfo);
        }

        public interface DokanReadFile extends StdCallCallback {
            int invoke(String fileName, byte[] buffer, IntByReference bytesRead, long offset, Pointer DokanFileInfo);
        }

        public interface DokanWriteFile extends StdCallCallback {
            int invoke(String fileName, byte[] buffer, IntByReference bytesWritten, long offset, Pointer DokanFileInfo);
        }

        public interface DokanFlushFileBuffers extends StdCallCallback {
            int invoke(String fileName, Pointer DokanFileInfo);
        }

        public interface DokanGetFileInformation extends StdCallCallback {
            int invoke(String fileName, Pointer lpFileInformation, Pointer DokanFileInfo);
        }

        public interface DokanFindFiles extends StdCallCallback {
            int invoke(String fileName, Pointer lpFindData, Pointer DokanFileInfo);
        }

        public interface DokanFindFilesWithPattern extends StdCallCallback {
            int invoke(String fileName, String searchPattern, Pointer lpFindData, Pointer DokanFileInfo);
        }

        public interface DokanSetFileAttributes extends StdCallCallback {
            int invoke(String fileName, int attributes, Pointer DokanFileInfo);
        }

        public interface DokanSetFileTime extends StdCallCallback {
            int invoke(String fileName, long createTime, long lastAccessTime, long lastWriteTime, Pointer DokanFileInfo);
        }

        public interface DokanDeleteFile extends StdCallCallback {
            int invoke(String fileName, Pointer DokanFileInfo);
        }

        public interface DokanDeleteDirectory extends StdCallCallback {
            int invoke(String fileName, Pointer DokanFileInfo);
        }

        public interface DokanMoveFile extends StdCallCallback {
            int invoke(String oldName, String newName, boolean replace, Pointer DokanFileInfo);
        }

        public interface DokanSetEndOfFile extends StdCallCallback {
            int invoke(String fileName, long length, Pointer DokanFileInfo);
        }

        public interface DokanSetAllocationSize extends StdCallCallback {
            int invoke(String fileName, long length, Pointer DokanFileInfo);
        }

        public interface DokanLockFile extends StdCallCallback {
            int invoke(String fileName, long offset, long length, Pointer DokanFileInfo);
        }

        public interface DokanUnlockFile extends StdCallCallback {
            int invoke(String fileName, long offset, long length, Pointer DokanFileInfo);
        }

        public interface DokanGetDiskFreeSpace extends StdCallCallback {
            int invoke(LongByReference freeBytesAvailable, LongByReference totalNumberOfBytes, LongByReference totalNumberOfFreeBytes, Pointer DokanFileInfo);
        }

        public interface DokanGetVolumeInformation extends StdCallCallback {
            int invoke(Pointer volumeNameBuffer, int volumeNameSize, IntByReference volumeSerialNumber, IntByReference maximumComponentLength, IntByReference fileSystemFlags, Pointer fileSystemNameBuffer, int fileSystemNameSize, Pointer DokanFileInfo);
        }

        public interface DokanGetFileSecurity extends StdCallCallback {
            int invoke(String fileName, int securityInformation, Pointer securityDescriptor, int securityDescriptorLength, IntByReference lengthNeeded, Pointer DokanFileInfo);
        }

        public interface DokanSetFileSecurity extends StdCallCallback {
            int invoke(String fileName, int securityInformation, Pointer securityDescriptor, int securityDescriptorLength, Pointer DokanFileInfo);
        }

        public interface DokanMounted extends StdCallCallback {
            int invoke(Pointer DokanFileInfo);
        }
    }
}
