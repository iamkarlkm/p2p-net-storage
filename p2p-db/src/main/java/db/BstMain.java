package db;

/**
 * 二叉搜索树节点（示例代码）。
 */
class TreeNode {
    int val;
    TreeNode left;
    TreeNode right;

    TreeNode(int val) {
        this.val = val;
        this.left = null;
        this.right = null;
    }
}

/**
 * 二叉搜索树实现（仅包含 insert 示例）。
 */
class BinarySearchTree {
    TreeNode root;

    public void insert(int val) {
        root = insertRec(root, val);
    }

    private TreeNode insertRec(TreeNode root, int val) {
        if (root == null) {
            root = new TreeNode(val);
            return root;
        }

        if (val < root.val) {
            root.left = insertRec(root.left, val);
        } else if (val > root.val) {
            root.right = insertRec(root.right, val);
        }

        return root;
    }
}

/**
 * 二叉搜索树构建示例入口。
 *
 * <p>该文件为演示/实验用途，不参与 ds 存储主流程。</p>
 */
public class BstMain {
    public static void main(String[] args) {
        int[] arr = {10, 5, 15, 3, 7, 12, 18};
        int n = arr.length;

        if (n <= 16) {
            BinarySearchTree bst = new BinarySearchTree();
            for (int i = 0; i < n; i++) {
                bst.insert(arr[i]);
            }
        } else {
            // 如果数组大小超过16，按照二叉搜索树的方式进行插入
            // 这里可以添加相应的逻辑，例如将数组分割成多个子数组，然后分别插入到不同的二叉搜索树中
        }
    }
}
