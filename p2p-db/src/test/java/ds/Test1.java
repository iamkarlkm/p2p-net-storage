package ds;

import com.q3lives.ds.index.record.DsDataIndex;
import com.q3lives.ds.index.record.DsDataIndexNode;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedList;

/**
 * 测试工具类。
 * 用于测试基本的文件读写和数据操作。
 * @author karl
 */
public class Test1 {

    public static void main(String[] args) throws Exception {
        // Create a new DsBlockIndex object
        DsDataIndex index = new DsDataIndex(new File("index.dat"));

        System.out.println("1111111111111");
//        // Create a new DsBlockIndexNode object
        DsDataIndexNode node = new DsDataIndexNode((short) 2, (short)50, 5000, 50000);
//        // Add the node to the index
        long id = index.add( node);
//        // Get the node from the index
        DsDataIndexNode node2 = index.get(id);
//        // Print the node
        LinkedList s;
        ArrayList a;

        //FileChannelImpl ff;
        System.out.println(node2.hash);
        System.out.println("22222222222");
    }
}
