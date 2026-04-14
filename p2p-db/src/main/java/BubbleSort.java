
import java.util.Arrays;

/**
 * 简单的冒泡排序示例（包含全量排序与子数组排序）。
 *
 * <p>该文件为演示/测试用途，不参与 ds 存储主流程。</p>
 */
public class BubbleSort {
    public static void main(String[] args) {
        int[] arr = { 5, 2, 8, 7, 1 };
        int[] res = bubbleSort(arr,1,3);
        System.out.println(Arrays.toString(res));
    }
    
    public static void bubbleSort(int[] arr) {
        int n = arr.length;
        for (int i = 0; i < n - 1; i++) {
            for (int j = 0; j < n - i - 1; j++) {
                if (arr[j] > arr[j + 1]) {
                    int temp = arr[j];
                    arr[j] = arr[j + 1];
                    arr[j + 1] = temp;
                }
            }
        }
    }
	
	public static int[] bubbleSort(int[] arrIn,int start,int count) {
		int[] arr = Arrays.copyOfRange(arrIn, start, start+count);
        int n = count;
        for (int i = 0; i < n - 1; i++) {
            for (int j = 0; j < n - i - 1; j++) {
                if (arr[j] > arr[j + 1]) {
                    int temp = arr[j];
                    arr[j] = arr[j + 1];
                    arr[j + 1] = temp;
                }
            }
        }
		
		return arr;
    }
}
