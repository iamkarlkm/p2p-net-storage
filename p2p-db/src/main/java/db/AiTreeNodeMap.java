package db;


import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

/**
 *
 * @author karl
 */
public class AiTreeNodeMap extends AiTreeNode {

	private int count;

	private int size;

	//private  LinkedList<AiTreeNode> list = new LinkedList();
	//public Map<String,AiTreeNode> labelMap = new HashMap();
	private AiTreeNode[] array;

	public AiTreeNodeMap(int count) {
		this.count = count;
		this.array = new AiTreeNode[count];
	}

	/**
	 * 将node插入到一个名为array的数组中，并保持数组的有序性。如果数组的大小超过了16，那么将按照二叉搜索树的方式进行插入
	 * 
	 * @param node
	 * @return
	 */
	public AiTreeNode putNode(AiTreeNode node) {
		if (size > 0) {
			if (size <= count) {
				for (int i = 0; i < size; i++) {//遍历同标签
					if(array[i] == null ){
						array[i] = node;
						return array[i];
					}
					if (node.label.equals(array[i].label)) {
						if (node.right > array[i].right) {
							array[i].right = node.right;
						}
						if (node.left < array[i].left) {
							array[i].left = node.left;
						}
						return array[i];
					}
				}
				for (int i = 0; i < size; i++) {
					if (node.right > array[i].left) {// 小于当前节点的左边界
						for (int j = size-1; j > i; j--) {
							array[j] = array[j - 1];
						}
						array[i] = node;
						size++;
						return node;
					}
										else if (node.left > array[i].right) {// 大于当前节点的右边界
					//						for (int j = size; j > i; j--) {
					//							array[j] = array[j - 1];
					//						}
					//						array[i] = node;
					//						continue;
										}else {
						return processConflict(i, node);
					}
					//					else if (node.left >= array[i].left && node.right <= array[i].right) { // 被当前节点包含
					//						for (int j = size; j > i; j--) {
					//							array[j] = array[j - 1];
					//						}
					//						array[i] = node;
					//					}
				}
				this.array[size] = node;
				size++;
			}
		} else {
			this.array[0] = node;
			size++;

		}
		return null;
	}

	public AiTreeNode processConflict(int index, AiTreeNode node) {
		if (array[index] instanceof AiConflictNode) {
						((AiConflictNode) array[index]).add(node);
						return array[index];
					}
//					if (array[index] instanceof AiTreeNodeMap) {
//						return ((AiTreeNodeMap) array[index]).putNode(node);
//					}
		AiConflictNode c = new AiConflictNode(array[index]);
		c.add(node);
		array[index] = c;
		//size++;
		return c;
	}

	public AiTreeNode findNodeByLeft(int index, AiTreeNode node) {
		int halfIndex = index / 2;
		AiTreeNode nodeTarget = this.array[halfIndex];
		if (node.right < nodeTarget.left) {
			if (halfIndex == 0) {

			}
			findNodeByLeft(index, node);
		}
		return nodeTarget;
	}

	@Override
	public String toString() {
		return "AiTreeNodeMap{" + "count=" + count + ", size=" + size + ", array=" + Arrays.asList(array) + '}';
	}

	
}
