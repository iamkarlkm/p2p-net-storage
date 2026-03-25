package db;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 *
 * @author karl
 */
public class AiConflictNode extends AiTreeNode{
	
	public List<AiTreeNode> list = new ArrayList();

	public AiConflictNode(AiTreeNode node) {
		super.left = node.left;
		super.right = node.right;
		list.add(node);
	}
	
	public AiConflictNode add(AiTreeNode node) {
		if (node.right > this.right) {
							this.right = node.right;
						}
						if (node.left < this.left) {
							this.left = node.left;
						}
						list.add(node);
						return this;
	}

	@Override
	public String toString() {
		return "AiConflictNode{" + "list=" + list + ", left=" + left + ", right=" + right+"}";
	}
	
}
