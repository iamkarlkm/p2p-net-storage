package db;


import java.util.Arrays;



/**
 * 有序数组替换/插入的示例代码（基于二分查找定位并通过移位维持有序）。
 *
 * <p>该文件为演示/实验用途，不参与 ds 存储主流程。</p>
 */
public class ArrayReplace {
	
	public static void main(String[] args) {
		int[] arr = new int[] { 5, 8, 19, 21, 23 };
		//int[] arr = {1, 3, 5, 7, 9};
		System.out.println("原数组为：arr=" + Arrays.toString(arr));
	int[] newArr = ArrayReplace.replaceElement(arr, 8, 20); 
		System.out.println(Arrays.toString(newArr));

	}

  

public static int[] replaceElement(int[] arr, int oldElement, int newElement){

  int oldIndex = binarySearch(arr, oldElement);

  if(newElement < oldElement){
    // 新元素较小,左移位插入
   int insertIndex = 0;
  for(int i=0; i<arr.length; i++){
    if(arr[i] > newElement){
      insertIndex = i;
      break; 
    }
  }
    for(int i=oldIndex; i>0; i--){
      arr[i] = arr[i-1];
    } 
    arr[insertIndex] = newElement;

  } else {
    // 新元素较大,右移位删除再插入
    for(int i = oldIndex; i < arr.length-1; i++){
      arr[i] = arr[i+1];
    }
    arr[arr.length-1] = newElement; 
  }

  return arr;
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
  
  // 对index位置进行插入排序,保持有序
  public static void insertSort(int[] arr, int index) {
    int value = arr[index];
    int i = index - 1;
    while (i >= 0 && arr[i] > value) {
      arr[i+1] = arr[i];
      i--;
    }
    arr[i+1] = value;
  }
}
