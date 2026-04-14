
/**
 * 基于 Dokan 的只读文件系统最小示例。
 *
 * <p>该文件为演示/实验用途，不参与 ds 存储主流程。</p>
 */
public class SimpleReadOnlyFileSystem {
    public static void main(String[] args) {
        DokanLibrary.DOKAN_OPTIONS options = new DokanLibrary.DOKAN_OPTIONS();
        options.Version = 0x0111;
        options.ThreadCount = 1;
        options.MountPoint = "Z:";
        options.Options = 0;

        DokanLibrary.DOKAN_OPERATIONS operations = new DokanLibrary.DOKAN_OPERATIONS();

        // 实现 DokanCreateFile 回调
        operations.DokanCreateFile = (fileName, access, share, createDisposition, flagsAndAttributes, securityContext, dokanFileInfo) -> {
            if ((access & 0x80000000) != 0) { // 检查是否有写权限
                return -1; // 拒绝写操作
            }
            return 0;
        };

        // 实现 DokanReadFile 回调
        operations.DokanReadFile = (fileName, buffer, bytesRead, offset, dokanFileInfo) -> {
            bytesRead.setValue(0);
            return 0;
        };

        // 实现 DokanGetDiskFreeSpace 回调
        operations.DokanGetDiskFreeSpace = (freeBytesAvailable, totalNumberOfBytes, totalNumberOfFreeBytes, dokanFileInfo) -> {
            freeBytesAvailable.setValue(1024 * 1024 * 1024);
            totalNumberOfBytes.setValue(1024 * 1024 * 1024);
            totalNumberOfFreeBytes.setValue(1024 * 1024 * 1024);
            return 0;
        };

        // 启动 Dokan
        int result = DokanLibrary.INSTANCE.DokanMain(options, operations);
        System.out.println("挂载结果: " + result);
    }
}
