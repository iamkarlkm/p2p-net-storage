package db;


import java.util.HashMap;
import java.util.Map;


/**
 *
 * @author karl
 */
public class AiTreeNode {
	
	public String label;
	public int left;
	public int right;
	public Map<String,AiTreeNode> context = new HashMap();

	@Override
	public String toString() {
		return  "label=" + label + ", left=" + left + ", right=" + right;
	}

	public AiTreeNode() {
	}
	
	

	public AiTreeNode(String label, int val) {
		this.label = label;
		this.left = this.right = val;
	}

	public AiTreeNode(String label, int left, int right) {
		this.label = label;
		this.left = left;
		this.right = right;
	}

	public static void main(String[] args) {
		System.out.println(64&0xfffffff8);
		System.out.println(Integer.toBinaryString(64));
		System.out.println(0b00110100);
	}

	
}
