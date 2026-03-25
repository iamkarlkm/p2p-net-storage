package db;

import java.util.Arrays;
import java.util.Scanner;

/**
 * 实现：在有序数组中插入一个元素,保持数组仍然有序
 * 对新数组排序---1）找要插入的位置
 * 2）将该位置后面的数据，都往后挪一位
 * 3）把新数据插到该位置
 */
public class ArrayOrderedInsert {
	public static void main(String[] args) {
		int[] arr = new int[] { 5, 8, 19, 20, 23 };
		System.out.println("原数组为：arr=" + Arrays.toString(arr));
//		Scanner sc = new Scanner(System.in);
//		System.out.println("请输入插入的数据");
//		int number = sc.nextInt(); //要插入的数据
		//orderedArrayReplace(arr, 8, 25);
		//orderedArrayReplace(arr, 19, 7);
		orderedArrayReplace(arr, 19, 18);
		System.out.println(Arrays.toString(arr));

	}

	public static void orderedArrayReplace(int[] arr, int oldNum, int newNum) {
		int oldIndex = binarySearch(arr, oldNum);
		if (oldNum == newNum) {
			return;
		}

		int left = 0, right = 0;
		if (oldIndex == 0) {
			left = arr[oldIndex];
		} else {
			left = arr[oldIndex - 1];
		}
		if (oldIndex == arr.length - 1) {
			right = arr[oldIndex];
		} else {
			right = arr[oldIndex + 1];
		}
		if (left > newNum && newNum > right) {
			arr[oldIndex] = newNum;
			return;
		}
		if (left == newNum || newNum == right) {
			arr[oldIndex] = newNum;
			return;
		}
		if (right < newNum) {
			//2、将后面的数据向后挪,处理index后面的数据
			for (int i = oldIndex; i < arr.length; i++) {
				if (i == arr.length - 1) {
					arr[i] = newNum;
					return;
				} else {
					arr[i] = arr[i + 1];
				}
				if (arr[i] == newNum) {
					return;
				} else if (arr[i] > newNum) {
					arr[i] = newNum;
					return;
				}

			}

		} else {

			for (int i = oldIndex; i >= 0; i--) { //要倒着赋值
				if (i == 0) {
					arr[i] = newNum;
					return;
				} else {
					arr[i] = arr[i - 1];
				}
				if (arr[i] == newNum) {
					return;
				} else if (arr[i] < newNum) {
					arr[i] = newNum;
					return;
				}

			}

		}
	}

	// 二分查找oldElement的索引
	public static int binarySearch(int[] arr, int target) {
		int low = 0;
		int high = arr.length - 1;
		while (low <= high) {
			int mid = low + (high - low) / 2;
			if (arr[mid] < target) {
				low = mid + 1;
			} else if (arr[mid] > target) {
				high = mid - 1;
			} else {
				return mid;
			}
		}
		//	System.out.println(low+":"+high);
		return -1;
	}
}