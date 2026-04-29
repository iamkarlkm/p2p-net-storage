package javax.net.p2p.utils;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.net.p2p.model.HdfsFileBlockModel;
import lombok.extern.slf4j.Slf4j;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.util.Progressable;

@Slf4j
public class HdfsUtil {

    private final static String BASE_URL = "http://10.26.10.106:10000/webhdfs/v1/project/demo_admin";

    private final static String HDFS_ROOT = "/project/demo_admin";

    private final static String SecretId = "";

    private final static String SecretKey = "";

    private final static String HADOOP_USER = "demo_admin";

    private static FileSystem HDFS;

    private static String HDFS_BASE_URL;

    public static void main2(String[] args) throws IOException {
        //请在 Begin-End 之间添加代码，完成任务要求。
        /**
         * ******* Begin ********
         */

        File localPath = new File("/develop/input/hello.txt");
        String hdfsPath = "hdfs://localhost:9000/user/tmp/hello.txt";

        InputStream in = new BufferedInputStream(new FileInputStream(localPath));// 获取输入流对象 
        Configuration conf = new Configuration();
        //conf.addResource(new Path("/etc/hadoop/conf/core-site.xml"));
        //conf.addResource(new Path("/etc/hadoop/conf/hdfs-site.xml"));

        conf.set("hadoop.security.authentication", "tbds");
        conf.set("hadoop_security_authentication_tbds_secureid", SecretId);
        conf.set("hadoop_security_authentication_tbds_securekey", SecretKey);
        conf.set("hadoop_security_authentication_tbds_username", HADOOP_USER);

        FileSystem fs = FileSystem.get(URI.create(hdfsPath), conf);
        long fileSize = localPath.length() > 65536 ? localPath.length() / 65536 : 1;// 待上传文件大小

        FSDataOutputStream out = fs.create(new Path(hdfsPath), new Progressable() {
            //方法在每次上传了64KB字节大小的文件之后会自动调用一次 
            long fileCount = 0;

            public void progress() {
                System.out.println("总进度" + (fileCount / fileSize) * 100 + "%");
                fileCount++;
            }
        });

        IOUtils.copyBytes(in, out, 2048, true);//最后一个参数的意思是使用完之后是否关闭流
    }

    /**
     * ******* End ********
     */
    public static void main(String[] args) throws Exception {
        initFileSysytem();
        System.out.println(args.length);
        if (args.length == 2 && "cp".equals(args[0])) {
            cp(args[1], args[2]);
        } else if (args.length == 2 && "mv".equals(args[0])) {
            rename(args[1], args[2]);
        } else if (args.length == 2 && "exists".equals(args[0])) {
            System.out.println("exists " + args[0] + " -> " + exists(args[1]));
        } else if (args.length == 2 && "rm".equals(args[0])) {
            System.out.println("rm " + args[0] + " -> " + remove(args[1]));
        }
        if (args.length == 2 && "ls".equals(args[0])) {
            List<String> list = getFileList(args[1]);
            System.out.println(args[0] + " " + args[1] + " ->\n" + list);
        } else if (args.length == 3 && "get".equals(args[0])) {
            readHdfsFile(args[1], args[2]);
            System.out.println(args[0] + " " + args[1] + " " + args[2]);
        } else if (args.length == 3 && "put".equals(args[0])) {
            writeHdfsFile(args[1], args[2]);
            System.out.println(args[0] + " " + args[1] + " " + args[2]);
        }
        //		System.out.println(HdfsUtil.class.getClassLoader().getResource("core-site.xml"));
    }

    public static FileSystem getInstance() {
        if (HDFS == null) {
            synchronized (HdfsUtil.class) {
                if (HDFS == null) {
                    try {
                        Configuration conf = new Configuration();
                        //这里指定使用的是HDFS文件系统
                        System.out.println(HdfsUtil.class.getClassLoader().getResource("core-site.xml"));
                        //		conf.addResource(HdfsUtil.class.getClassLoader().getResource("core-site.xml"));
                        //		conf.addResource(HdfsUtil.class.getClassLoader().getResource("hdfs-site.xml"));
                        //conf.addResource(new Path("/etc/hadoop/conf/core-site.xml"));
                        //conf.addResource(new Path("/etc/hadoop/conf/hdfs-site.xml"));

                        //		conf.set("fs.defaultFS", "hdfs://hdfsCluster");
                        //		conf.set("hadoop.security.authentication", "tbds");
                        //		conf.set("hadoop_security_authentication_tbds_secureid", SecretId);
                        //		conf.set("hadoop_security_authentication_tbds_securekey", SecretKey);
                        //		conf.set("hadoop_security_authentication_tbds_username", HADOOP_USER);
                        //通过FileSystem的静态方法获取文件系统客户端对象
                        HDFS = FileSystem.get(conf);
                        HDFS_BASE_URL = HDFS.getConf().get("fs.defaultFS") + HDFS_ROOT;
                        System.out.println("fs.defaultFS -> " + HDFS_BASE_URL);
                        System.out.println("hadoop.security.authentication -> " + HDFS.getConf().get("hadoop.security.authentication"));
                        FileStatus[] roots = HDFS.listStatus(HDFS.getHomeDirectory());
                        for (FileStatus f : roots) {
                            System.out.println(f.getPath());
                        }
                        log.info("HDFS{}创建成功", HDFS);
                    } catch (IOException ex) {
                        log.error(ex.getMessage(), ex);
                    }
                }
            }
        }
        return HDFS;
    }

    public static void initFileSysytem() throws IOException {
        getInstance();
    }

    public static void closeFileSysytem() throws IOException {
        if (HDFS != null) {
            HDFS.close();
        }
    }

    //在hdfs的目标位置新建一个文件，得到一个输出流
    public static void writeHdfsFile(String path, String localFilePath) throws Exception {
        Path dst = new Path(HDFS_BASE_URL + path);
        //		if (HDFS.exists(dst)) {
        //			HDFS.delete(dst, true);
        //		}
        Path src = new Path(localFilePath);
        FileSystem fs = getInstance();
        fs.copyFromLocalFile(src, dst);

        //		OutputStream os = HDFS.create(file,
        //						new Progressable() {
        //							public void progress() {
        //								//out.println("...bytes written: [ " + bytesWritten + " ]");
        //							}
        //						});
        //				FileInputStream input = new FileInputStream(localFilePath);) {
        //			final BufferedOutputStream buffer = new BufferedOutputStream(os);
        //			IOUtils.copyBytes(input, os, 8192, true);
        //			buffer.flush();
    }

    public static void readHdfsFile(String path, String localFilePath) throws Exception {
        Path dst = new Path(localFilePath);
        Path src = new Path(HDFS_BASE_URL + path);

        FileSystem fs = getInstance();
        fs.copyToLocalFile(src, dst);

    }

    public static byte[] read(String path) throws Exception {
        Path src = new Path(HDFS_BASE_URL + path);
        FileSystem fs = getInstance();
        FileStatus fileStatus = fs.getFileStatus(src);
        byte[] data = new byte[(int) fileStatus.getLen()];
        FSDataInputStream in = fs.open(src);
        IOUtils.readFully(in, data, 0, (int) fileStatus.getLen());
        // 关闭输入流
        IOUtils.closeStream(in);
        //		FileStatus fileStatus = HDFS.getFileStatus(src);
        //		BlockLocation[]  blocks = HDFS.getFileBlockLocations(fileStatus, 0, fileStatus.getLen());
        //		for(BlockLocation block:blocks){
        //			block.
        //		}
        return data;
    }

    public static byte[] readBlock(String path, long start, int length) throws Exception {
        Path src = new Path(HDFS_BASE_URL + path);
        FileSystem fs = getInstance();
        byte[] data = new byte[length];
        try (FSDataInputStream in = fs.open(src);) {
            in.read(start, data, 0, length);
        }
        return data;
    }

    public static FileStatus getFileInfo(String path) throws Exception {
        Path src = new Path(HDFS_BASE_URL + path);
        FileSystem fs = getInstance();
        if (fs.exists(src)) {
            FileStatus fileStatus = fs.getFileStatus(src);
            return fileStatus;
        }
        return null;
    }

    public static boolean mkdirs(String path) throws Exception {
        Path src = new Path(HDFS_BASE_URL + path);
        FileSystem fs = getInstance();
        return fs.mkdirs(src);
    }

    public static boolean remove(String path) throws Exception {
        Path src = new Path(HDFS_BASE_URL + path);
        FileSystem fs = getInstance();
        return fs.delete(src, false);
    }

    public static boolean exists(String path) throws Exception {
        Path src = new Path(HDFS_BASE_URL + path);
        FileSystem fs = getInstance();
        return fs.exists(src);
    }

    public static boolean rename(String src, String dst) throws Exception {
        Path src1 = new Path(HDFS_BASE_URL + src);
        Path dst1 = new Path(HDFS_BASE_URL + dst);
        FileSystem fs = getInstance();
        return fs.rename(src1, dst1);

    }

    public static void write(String path, byte[] data) throws Exception {
        Path dst = new Path(HDFS_BASE_URL + path);
        //		if (HDFS.exists(dst)) {
        //			HDFS.delete(dst, true);
        //		}
        try (
            FSDataOutputStream create = HDFS.create(dst)) {
            create.write(data);
            create.flush();
        }
    }

    public static void cp(String src, String dest) throws Exception {
//		Path dst = new Path(HDFS_BASE_URL + dest);
//		try (
//				FSDataOutputStream create = HDFS.create(dst)) {
//			create.write(read(src));
//			create.flush();
//		}
        Path src0 = new Path(HDFS_BASE_URL + src);
        Path dst = new Path(HDFS_BASE_URL + dest);
        FileSystem fs = getInstance();
        Path src1 = fs.createSnapshot(src0);
        fs.rename(src1, dst);
    }

    //private static final int HDFS_BUFFERSIZE = 512 * 1024;
    private static final short HDFS_REPLICATION = 1;

    //private static final int HDFS_PACKAGE_BLOCK_SIZE = 1024 * 1024 * 1024;
    public static HdfsFileBlockModel writeBlock(String path, byte[] data) throws Exception {
        Path dst = new Path(HDFS_BASE_URL + path);
        long start = 0;
        long length = 0;
        FileSystem fs = getInstance();
        if (fs.exists(dst)) {
            try (
                FSDataOutputStream create = fs.append(dst)) {
                start = create.getPos();
                create.write(data);
                create.flush();
                length = create.getPos() - start;
            }
        } else {
            try (
                /**
                 * Create an FSDataOutputStream at the indicated Path.
                 *
                 * @param f the file name to open
                 * @param overwrite if a file with this name already exists,
                 * then if true, the file will be overwritten, and if false an
                 * error will be thrown.
                 * @param bufferSize the size of the buffer to be used.
                 * @param replication required block replication for the file.
                 * @param blockSize required block replication for the file.
                 */
                //FSDataOutputStream create = HDFS.create(dst, true, HDFS_BUFFERSIZE, HDFS_REPLICATION, HDFS_PACKAGE_BLOCK_SIZE)
                FSDataOutputStream create = HDFS.create(dst, HDFS_REPLICATION)) {

                create.write(data);
                create.flush();
            }
        }
        return new HdfsFileBlockModel(path, start, length);

    }

    public static List<String> getFileList(String path) throws IOException {
        List<String> paths = new ArrayList();
        Path folderPath = new Path(HDFS_BASE_URL + path);
        FileSystem fs = getInstance();
        if (fs.isDirectory(folderPath) && HDFS.exists(folderPath)) {
            FileStatus[] fileStatus = fs.listStatus(folderPath);

            for (int i = 0; i < fileStatus.length; i++) {
                FileStatus fileStatu = fileStatus[i];
                paths.add(fileStatu.getPath().toString());
            }
        }

        return paths;
    }

}
