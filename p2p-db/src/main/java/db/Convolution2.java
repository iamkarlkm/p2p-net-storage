package db;

// import cn.hutool.core.date.StopWatch;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
// import org.apache.commons.lang3.ArrayUtils;

/**
 * 纯 Java 的卷积/图像处理实验代码。
 *
 * <p>该文件为演示/实验用途，不参与 ds 存储主流程。</p>
 */
public class Convolution2 {

	/**
	 * 进行卷积运算
	 * 
	 * @param input 输入信号
	 * @param x
	 * @param y
	 * @param kernel 卷积核
	 * @return 卷积后的输出信号
	 */
	//	public static double[] convolve(double[] input, double[] kernel) {
	//		int outSize = input.length / kernel.length;
	//		double[] output = new double[outSize];
	//
	//		int m = kernel.length;
	//
	//		for (int i = 0; i < input.length; i++) {
	//
	//			double sum = 0;
	//
	//			for (int j = 0; j < m; j++) {
	//				if (i - j < 0 || i - j >= input.length) {
	//					continue;
	//				}
	//				sum += input[i - j] * kernel[j];
	//			}
	//
	//			output[i] = sum;
	//		}
	//
	//		return output;
	//
	//	}

	//	public static int[] convolve(int[] input, int coreSize) {
	//		if (input.length < coreSize) {
	//			return input;
	//		}
	//		int outSize = input.length / coreSize;
	//		int[] output = new int[outSize];
	//		int x = 0;
	//		for (int i = 0; i < input.length; i = i + coreSize) {
	//
	//			int sum = 0;
	//
	//			for (int j = 0; j < coreSize; j++) {
	//
	//				sum += input[i + j];
	//			}
	//
	//			output[x] = sum;
	//			x++;
	//		}
	//
	//		return output;
	//
	//	}

	//	public static int convolveTo(int[] input, int coreSize) {
	//		int[] res = convolve(input, coreSize);
	//		while (res.length >= coreSize) {
	//			res = convolve(input, coreSize);
	//		}
	//		return res[0];
	//	}

	//	public static int[] convolve(byte[] input, int coreSize) {
	//		if (input.length < coreSize) {
	//			int[] output = new int[1];
	//			output[0] = input[0];
	//			return output;
	//		}
	//		int outSize = input.length / coreSize;
	//		int[] output = new int[outSize];
	//		int x = 0;
	//		for (int i = 0; i < input.length; i = i + coreSize) {
	//
	//			int sum = 0;
	//
	//			for (int j = 0; j < coreSize; j++) {
	//
	//				sum += input[i + j];
	//			}
	//
	//			output[x] = sum;
	//			x++;
	//		}
	//
	//		return output;
	//
	//	}

	//	public static int convolveTo(byte[] input, int coreSize) {
	//		int[] res = convolve(input, coreSize);
	//		while (res.length >= coreSize) {
	//			res = convolve(input, coreSize);
	//		}
	//		// return res[0];
	//		int val = 0;
	//		/*
	//		 * 2 9 4
	//		 * 7 5 3
	//		 * 6 1 8
	//		 */
	//		for (int i = 0; i < res.length; i++) {
	//			switch (i) {
	//				case 0:
	//					val += res[i] << 1;
	//					break;
	//				case 1:
	//					val += res[i] << 8;
	//					break;
	//				case 2:
	//					val += res[i] << 3;
	//					break;
	//				case 3:
	//					val += res[i] << 6;
	//					break;
	//				case 4:
	//					val += res[i] << 4;
	//					break;
	//				case 5:
	//					val += res[i] << 2;
	//					break;
	//				case 6:
	//					val += res[i] << 5;
	//					break;
	//				case 7:
	//					val += res[i];
	//					break;
	//				case 8:
	//					val += res[i] << 7;
	//					break;
	//			}
	//
	//		}
	//		return val;
	//	}

	//	public static int[] convolveBytes(byte[] input, int coreSize) {
	//		if (input.length < coreSize) {
	//			int[] output = new int[input.length];
	//			for (int i = 0; i < input.length; i = i ++) {
	//				output[i] = input[i];
	//			}
	//			return output;
	//		}
	//		int outSize = input.length / coreSize;
	//		int[] output = new int[outSize];
	//		int x = 0;
	//		for (int i = 0; i < input.length; i = i + coreSize) {
	//
	//			int sum = 0;
	//
	//			for (int j = 0; j < coreSize; j++) {
	//
	//				//sum += input[i+j]; 
	//				//		switch(i){
	//				//			 case 0:sum += input[i]<<2;break;
	//				//			 case 1:sum += input[i]<<9;break;
	//				//			 case 2:sum += input[i]<<4;break;
	//				//			 case 3:sum += input[i]<<7;break;
	//				//			 case 4:sum += input[i]<<5;break;
	//				//			 case 5:sum += input[i]<<3;break;
	//				//			 case 6:sum += input[i]<<6;break;
	//				//			 case 7:sum += input[i]<<1;break;
	//				//			 case 8:sum += input[i]<<8;break;
	//				//		 }
	//				switch (i) {
	//					case 0:
	//						sum += input[i] << 1;
	//						break;
	//					case 1:
	//						sum += input[i] << 8;
	//						break;
	//					case 2:
	//						sum += input[i] << 3;
	//						break;
	//					case 3:
	//						sum += input[i] << 6;
	//						break;
	//					case 4:
	//						sum += input[i] << 4;
	//						break;
	//					case 5:
	//						sum += input[i] << 2;
	//						break;
	//					case 6:
	//						sum += input[i] << 5;
	//						break;
	//					case 7:
	//						sum += input[i];
	//						break;
	//					case 8:
	//						sum += input[i] << 7;
	//						break;
	//				}
	//			}
	//
	//			output[x] = sum;
	//			x++;
	//		}
	//
	//		return output;
	//
	//	}

	/**
	 * 中位值
	 * 
	 * @param array
	 * @return
	 */
	public static final int getMedian(int[] array) {
		//		Arrays.sort(array);
		//		
		//		return  array[array.length*6/10];
		int sum = 0, count = 0;
		for (int s : array) {
			//if(s!=0){
			sum += s;
			count++;
			//}
		}
		if (count == 0)
			return 0;
		int val = sum / count;
		if (val < 128) {
			return 0;
		}
		return 255;
	}

	public static final int getMedian2(int[] array) {
		Arrays.sort(array);
		for (int i = 0; i < array.length; i++) {
			if (array[i] > 0) {
				return array[i + (array.length - i) / 2];
			}
		}
		return 0;
		//		int sum = 0,count = 0;
		//		for (int s : array) {
		//			if(s!=0){
		//				sum += s;count++;
		//			}
		//		}
		//		if(count==0) return 0;
		//		return sum/count;
	}

	public static int[] convolve2d(int[] input, int x, int y) {
		//System.out.println(input.length + " -> " + x + ":" + y);
		if (input.length <= 9) {
			return input;
		}

		int _x = 0;
		int core_x = 3, core_y = 3;
		int size_x = (x / 3) + (x % 3 == 0 ? 0 : 1);
		int size_y = (y / 3) + (y % 3 == 0 ? 0 : 1);
		//		int size_x = x / 3;
		//		int size_y = y / 3;
		int size = size_x * size_y;
		int[] output = new int[size];
		int[] tmp = new int[9];
		for (int i = 0; i < x; i = i + 3) {
			for (int j = 0; j < y; j = j + 3) {
				//int base = i+j*x;
				int sum = 0, index = 0;
				//System.out.println(i+":"+j);
				for (int k = 0; k < core_x; k++) {
					for (int m = 0; m < core_y; m++) {
						int vIndex = (i + k) + (j + m) * x;
						if (vIndex < input.length && input[vIndex] != 0) {
							tmp[index] = input[vIndex];
							//System.out.println(index + " -> vIndex:" + vIndex + " = " + input[vIndex]);
							/*
							 * 2 9 4
							 * 7 5 3
							 * 6 1 8
							 */
							switch (index) {
								case 0:
									sum += input[vIndex] << 1;
									break;
								case 1:
									sum += input[vIndex] << 8;
									break;
								case 2:
									sum += input[vIndex] << 3;
									break;
								case 3:
									sum += input[vIndex] << 6;
									break;
								case 4:
									sum += input[vIndex] << 4;
									break;
								case 5:
									sum += input[vIndex] << 2;
									break;
								case 6:
									sum += input[vIndex] << 5;
									break;
								case 7:
									sum += input[vIndex];
									break;
								case 8:
									sum += input[vIndex] << 7;
									break;
							}
						} else {
							tmp[index] = 0;
						}
						index++;
					}
				}
				//System.out.println(_x+"=="+sum);
				//output[_x] = sum;
				output[_x] = getMedian(tmp);
				_x++;
				if (_x > size) {
					break;
				}

			}
		}

		return output;

	}

	public static int[] convolve2x2(byte[] input,int depth, int x, int y) {

        return new int[0];
    }

	public static byte[] convolve2x2ToByte(byte[] input, int x, int y) {

		byte[] r = convolve2x2(input, x, y);
		int size_x = x / 2;
		int size_y = y / 2;
		while(r.length>4){
			size_x /=2;
			size_y /=2;
			r = convolve2x2(r, size_x, size_y);
		}
		return null;
	}
		public static byte[] convolve2x2(byte[] input, int x, int y) {
		//System.out.println(input.length + " -> " + x + ":" + y);

		int _x = 0;
		int core_x = 2, core_y = 2;
//		int size_x = (x / depth) + (x % depth == 0 ? 0 : 1);
//		int size_y = (y / depth) + (y % depth == 0 ? 0 : 1);
				int size_x = x / 2;
				int size_y = y / 2;
		int size = size_x * size_y;
		//int size = (x-1) * (y-1);
			byte[] output = new byte[size];
		//int[] tmp = new int[9];
		for (int i = 0; i < x; i = i + core_x) {
			for (int j = 0; j < y; j = j + core_y) {
				//int base = i+j*x;
				int sum = 0,sum1 = 0, sum2 = 0, index = 0;
				//System.out.println(i+":"+j);
				for (int k = 0; k < core_x; k++) {
					for (int m = 0; m < core_y; m++) {
						int vIndex = (i + k) + (j + m) * x;
						//if (vIndex < input.length && input[vIndex] != 0) {
							//tmp[index] = input[vIndex];
							//System.out.println(index + " -> vIndex:" + vIndex + " = " + input[vIndex]);
							/*
							 * 2 9 4
							 * 7 5 3
							 * 6 1 8
							 */
                        switch (index) {
                            case 0 -> sum += input[vIndex] & 0b11000000;
                            case 1 -> sum += (input[vIndex] & 0b11000000 >> 2) & 0b00110000;
                            case 2 -> sum += (input[vIndex] & 0b11000000 >> 4) & 0b00001100;
                            case 3 -> sum += (input[vIndex] & 0b11000000 >> 6) & 0b00000011;
                        }
						//} else {
							//tmp[index] = 0;
						//}
						index++;
					}
				}
				//System.out.println(_x+"=="+sum);
				output[_x] = (byte) sum;
				//output[_x] = getMedian(tmp);
				_x++;
				if (_x > size) {
					break;
				}

			}
		}

		return output;

	}

	public final static byte summary4(byte[] input, int vIndex){
//		if(input[vIndex]==input[vIndex+1]){
//			if(input[vIndex+1]==input[vIndex+2]){
//				if(input[vIndex+2]==input[vIndex+3]){
//					return input[vIndex];
//				}
//
//
//
//			}
//
//		}
		int a= input[vIndex] & 0b11000000;
		int b=  (input[vIndex+1] & 0b11000000 >> 2) & 0b00110000;
		int c=  (input[vIndex+2] & 0b11000000 >> 4) & 0b00001100;
		int d=  (input[vIndex+3] & 0b11000000 >> 6) & 0b00000011;
        return 0;
    }

	public final static byte median4__(byte[] input, int vIndex){
		if(input[vIndex]==input[vIndex+1]){
			if(input[vIndex+1]==input[vIndex+2]){
				return input[vIndex];
			}else if(input[vIndex+2]==input[vIndex+3]){
//				int a= input[vIndex] & 0b11000000;
//				a +=  (input[vIndex+1] & 0b11000000 >> 2) & 0b00110000;
//				a +=  (input[vIndex+2] & 0b11000000 >> 4) & 0b00001100;
//				a +=  (input[vIndex+3] & 0b11000000 >> 6) & 0b00000011;
//				return (byte) a;
				return (byte)0xff;
			}else{
				int a= input[vIndex] & 0xff;
				int b=  input[vIndex+2]  & 0xff;
				int c=  input[vIndex+3] & 0xff;
				int diff1 = a>b?a-b:b-a;

				int diff2 = b>c?b-c:c-b;
				if(diff2>diff1){
					return (byte) b;
				}else{
					return (byte)0xff;
				}
			}

		}else if(input[vIndex]==input[vIndex+2]){
			if(input[vIndex+2]==input[vIndex+3]){
				return input[vIndex];
			}else if(input[vIndex+1]==input[vIndex+3]){
//				int a= input[vIndex] & 0b11000000;
//				a +=  (input[vIndex+1] & 0b11000000 >> 2) & 0b00110000;
//				a +=  (input[vIndex+2] & 0b11000000 >> 4) & 0b00001100;
//				a +=  (input[vIndex+3] & 0b11000000 >> 6) & 0b00000011;
//				return (byte) a;
				return (byte)0xff;
			}else{
				int a= input[vIndex] & 0xff;
				int b=  input[vIndex+1]  & 0xff;
				int c=  input[vIndex+3] & 0xff;
				int diff1 = a>b?a-b:b-a;

				int diff2 = b>c?b-c:c-b;
				if(diff2>diff1){
					return (byte) b;
				}else{
					return (byte)0xff;
				}
			}

		}else if(input[vIndex]==input[vIndex+3]){
			if(input[vIndex+1]==input[vIndex+3]){
				return input[vIndex];
			}else if(input[vIndex+2]==input[vIndex+3]){
//				int a= input[vIndex] & 0b11000000;
//				a +=  (input[vIndex+1] & 0b11000000 >> 2) & 0b00110000;
//				a +=  (input[vIndex+2] & 0b11000000 >> 4) & 0b00001100;
//				a +=  (input[vIndex+3] & 0b11000000 >> 6) & 0b00000011;
//				return (byte) a;
				return (byte)0xff;
			}else{
				int a= input[vIndex] & 0xff;
				int b=  input[vIndex+1]  & 0xff;
				int c=  input[vIndex+2] & 0xff;
				int diff1 = a>b?a-b:b-a;

				int diff2 = b>c?b-c:c-b;
				if(diff2>diff1){
					return (byte) b;
				}else{
					return (byte)0xff;
				}
			}

		}else if(input[vIndex+1]==input[vIndex+2]){
			if(input[vIndex+1]==input[vIndex+3]){
				return input[vIndex+1];
			}else{
				int a= input[vIndex+1] & 0xff;
				int b=  input[vIndex]  & 0xff;
				int c=  input[vIndex+3] & 0xff;
				int diff1 = a>b?a-b:b-a;

				int diff2 = b>c?b-c:c-b;
				if(diff2>diff1){
					return (byte) b;
				}else{
					return (byte)0xff;
				}
			}

		}else if(input[vIndex+1]==input[vIndex+3]){
			if(input[vIndex+2]==input[vIndex+3]){
				//				int a= input[vIndex] & 0b11000000;
//				a +=  (input[vIndex+1] & 0b11000000 >> 2) & 0b00110000;
//				a +=  (input[vIndex+2] & 0b11000000 >> 4) & 0b00001100;
//				a +=  (input[vIndex+3] & 0b11000000 >> 6) & 0b00000011;
//				return (byte) a;
				return (byte)0xff;
			}else{
				int a= input[vIndex+1] & 0xff;
				int b=  input[vIndex]  & 0xff;
				int c=  input[vIndex+3] & 0xff;
				int diff1 = a>b?a-b:b-a;

				int diff2 = b>c?b-c:c-b;
				if(diff2>diff1){
					return (byte) b;
				}else{
					return (byte)0xff;
				}
			}

		}else if(input[vIndex+2]==input[vIndex+3]){

				int a= input[vIndex+2] & 0xff;
				int b=  input[vIndex+0]  & 0xff;
				int c=  input[vIndex+1] & 0xff;
				int diff1 = a>b?a-b:b-a;

				int diff2 = b>c?b-c:c-b;
				if(diff2>diff1){
					return (byte) b;
				}else{
					return (byte)0xff;
				}
		}
		int a= input[vIndex] & 0xff;
		int b=  input[vIndex+1]  & 0xff;
		int c=  input[vIndex+2] & 0xff;
		int d=  input[vIndex+3]  & 0xff;
		int one = a>b?a:b;one = one>c?one:c;one = one>d?one:d;
		int two = c>d?c:d;
		int three = one>two?one:two;
		return (byte) three;
	}

	private static final ThreadLocal<int[]> median4Array = ThreadLocal.withInitial(() -> new int[4]);
	private static final ThreadLocal<int[]> median9Array = ThreadLocal.withInitial(() -> new int[9]);
	private static final ThreadLocal<int[]> median16Array = ThreadLocal.withInitial(() -> new int[16]);

	public final static byte median9(byte[] input, int baseOffset, int depth,int x, int y){
		int[] arr = median9Array.get();
		int rowOffset = y*depth;
		int size =arr.length ;
		for (int i = 0; i < size; i++) {
			//arr[0] = input[baseOffset -rowOffset*2-2] & 0xff;
			arr[0] = input[baseOffset -rowOffset-1] & 0xff;
			arr[1] = input[baseOffset -rowOffset] & 0xff;
			arr[2] = input[baseOffset -rowOffset+1] & 0xff;
			arr[3] = input[baseOffset -1] & 0xff;
			arr[4] = input[baseOffset ] & 0xff;
			arr[5] = input[baseOffset +1] & 0xff;
			arr[6] = input[baseOffset +rowOffset-1] & 0xff;
			arr[7] = input[baseOffset +rowOffset] & 0xff;
			arr[8] = input[baseOffset +rowOffset+1] & 0xff;

		}
		bubbleSort(arr);

		int min = arr[0];
		int max = arr[8];
		if(max==min){
			return (byte) max;
		}
		int mid1 = arr[3];
		int mid2 = arr[5];
		if(mid1==mid2) return (byte) mid1;
		int diff1 = min>mid1?min-mid1:mid1-min;
		int diff2 = max>mid2?max-mid2:mid2-max;
		if(diff2>diff1){
			//min = arr[0];
			max = arr[5];
			if(max==min){
				return (byte) max;
			}
			mid1 = arr[2];
			mid2 = arr[3];
			if(mid1==mid2) return (byte) mid1;
			diff1 = min>mid1?min-mid1:mid1-min;
			diff2 = max>mid2?max-mid2:mid2-max;
			if(diff2>diff1){
				return (byte) mid1;
			}else{
				return (byte)mid2;
			}
		}else{
			min = arr[3];
			if(max==min){
				return (byte) max;
			}
			mid1 = arr[5];
			mid2 = arr[6];
			if(mid1==mid2) return (byte) mid1;
			diff1 = min>mid1?min-mid1:mid1-min;
			diff2 = max>mid2?max-mid2:mid2-max;
			if(diff2>diff1){
				return (byte) mid1;
			}else{
				return (byte)mid2;
			}
		}
	}

	// 冒泡排序的主要方法
	public static void bubbleSort(int[] arr) {
		int n = arr.length;
		boolean swapped;
		for (int i = 0; i < n - 1; i++) {
			swapped = false;
			// 这里的(n - i - 1)是最后的i个元素已经排序好，不需要再进行比较
			for (int j = 0; j < n - i - 1; j++) {
				// 相邻元素两两比较
				if (arr[j] > arr[j + 1]) {
					// 交换arr[j]和arr[j + 1]
					int temp = arr[j];
					arr[j] = arr[j + 1];
					arr[j + 1] = temp;
					swapped = true;
				}
			}
			// 如果在这一轮排序中没有交换过，说明数组已经有序，可以提前结束
			if (!swapped)
				break;
		}
	}

	public static byte[] convolve3x3(byte[] input,int depth, int x, int y) {
		System.out.println(input.length + " -> " + x + ":" + y);

		int outIndex = 0;

		
		//int size = size_x * size_y;
		int size = (x-2) * (y-2)*depth;
		byte[] output = new byte[size];
		for (int i = 0; i < x; i ++) {
			for (int j = 0; j < y; j ++) {
				int base = i*j;
				System.out.println(base);
				for(int k = 0; k < depth; k++){
					output[outIndex] = median9(input, base+k, depth, x, y);
					//output[_x] = getMedian(tmp);
					outIndex++;
				}

				if (outIndex > size) {
					break;
				}

			}
		}

		return output;

	}

	public static int[] convolve2da(int[] input, int x, int y) {
		//System.out.println(input.length + " -> " + x + ":" + y);
		if (input.length <= 9) {
			return input;
		}

		int _x = 0;
		int core_x = 3, core_y = 3;
		//		int size_x = (x / 3) + (x % 3 == 0 ? 0 : 1);
		//		int size_y = (y / 3) + (y % 3 == 0 ? 0 : 1);
		int size_x = x / 3;
		int size_y = y / 3;
		int limitX = size_x * 3;
		int limitY = size_y * 3;
		int size = size_x * size_y;
		int[] output = new int[size];
		int[] tmp = new int[9];
		for (int i = 0; i < limitX; i = i + 3) {
			for (int j = 0; j < limitY; j = j + 3) {
				//int base = i+j*x;
				int sum = 0, index = 0, mid = 0;
				//System.out.println(i+":"+j);
				for (int k = 0; k < core_x; k++) {
					for (int m = 0; m < core_y; m++) {
						int vIndex = (i + k) + (j + m) * x;
						if (vIndex < input.length && input[vIndex] != 0) {
							tmp[index] = input[vIndex];
							//System.out.println(index + " -> vIndex:" + vIndex + " = " + input[vIndex]);
							/*
							 * 2 9 4
							 * 7 5 3
							 * 6 1 8
							 */
							switch (index) {
								case 0:
									sum += input[vIndex] << 1;
									break;
								case 1:
									sum += input[vIndex] << 8;
									break;
								case 2:
									sum += input[vIndex] << 3;
									break;
								case 3:
									sum += input[vIndex] << 6;
									break;
								case 4:
									sum += input[vIndex] << 4;
									break;
								case 5:
									mid = input[vIndex];
									sum += input[vIndex] << 2;
									break;
								case 6:
									sum += input[vIndex] << 5;
									break;
								case 7:
									sum += input[vIndex];
									break;
								case 8:
									sum += input[vIndex] << 7;
									break;
							}
						} else {
							tmp[index] = 0;
							continue;
						}
						index++;
					}
				}
				//System.out.println(_x+"=="+sum);
				//output[_x] = sum;
				if (_x > size) {
					break;
				}
				//output[_x] = mid;
				output[_x] = getMedian(tmp);
				_x++;

			}
		}

		return output;

	}

	public static int[] toIntArray(byte[] input) {
		int[] output = new int[input.length];
		for (int i = 0; i < input.length; i++) {
			//System.out.println(i);
			output[i] = Byte.toUnsignedInt(input[i]);
		}
		return output;
	}

	public static byte[] toByteArray(int[] input) {
		byte[] output = new byte[input.length];
		for (int i = 0; i < input.length; i++) {
			//System.out.println(i);
			output[i] = (byte) input[i];
		}
		return output;
	}

	public static int convolve2dTo(byte[] input, int x, int y) {
		//System.out.println(input.length + " convolve2dTo -> " + x + ":" + y);
		int[] res = toIntArray(input);
		//System.out.println("res:"+res.length);
		//		int size_x = (x / 3)+(x % 3==0?0:1);
		//		int size_y = (y / 3)+(y % 3==0?0:1);
		int size_x = x;
		int size_y = y;
		while (res.length > 9) {
			//System.out.println("while -> " + size_x + ":" + size_y);
			res = convolve2d(res, size_x, size_y);
			//			size_x = (size_x / 3)+(size_x % 3==0?0:1);
			//			size_y = (size_y / 3)+(size_y % 3==0?0:1);
			size_x = (size_x / 3) + (size_x % 3 == 0 ? 0 : 1);
			size_y = (size_y / 3) + (size_y % 3 == 0 ? 0 : 1);
		}
		// return res[0];
		//System.out.println(Arrays.toString(res));
		int val = res[0];
		if (res.length > 1) {
			val = 0;
			/*
			 * 2 9 4
			 * 7 5 3
			 * 6 1 8
			 */
			for (int i = 0; i < res.length; i++) {
				switch (i) {
					case 0:
						val += res[i] << 1;
						break;
					case 1:
						val += res[i] << 8;
						break;
					case 2:
						val += res[i] << 3;
						break;
					case 3:
						val += res[i] << 6;
						break;
					case 4:
						val += res[i] << 4;
						break;
					case 5:
						val += res[i] << 2;
						break;
					case 6:
						val += res[i] << 5;
						break;
					case 7:
						val += res[i];
						break;
					case 8:
						val += res[i] << 7;
						break;
				}

			}
		}

		//System.out.println(Integer.toHexString(val));
		return val;
	}

	public static int getHighestSetBit(int n) {
		for (int i = 31; i >= 0; i--) {
			if (((n >>> i) & 1) == 1) {
				return i + 1;
			}
		}
		return 0;
	}

	public static void main(String[] args) throws Exception {

//		byte[] d = AiLoadData.loadImageWithMNIST(new File("E:\\ai\\MNIST\\train-images.idx3-ubyte"), 3604);
//		//d = toByteArray(convolve2da(toIntArray(d), 28, 28));
//		//System.out.println(r.length);
//		OpenCVImage.show(AiLoadData.loadLableWithMNIST(new File("E:\\ai\\MNIST\\train-labels.idx1-ubyte"), 3604), OpenCVImage.resizeGray(d, 28, 28, 28, 28));

		byte[] d = AiLoadData.loadImageWithMNIST(new File("E:\\ai\\MNIST\\t10k-images.idx3-ubyte"), 9);
		for (int i = 0; i < 28; i++) {
			int[] row = new int[28];
			for (int j = 0; j < 28; j++) {
				row[j] =d[i*j+j]&0xff;
				System.out.print(i+":"+j+"="+row[j]+"  ");
			}
			//System.out.println(Arrays.toString(row));
			System.out.println();
			}
		
			System.out.println(AiLoadData.loadLableWithMNIST(new File("E:\\ai\\MNIST\\train-labels.idx1-ubyte"),19));
			//d = toByteArray(convolve2da(toIntArray(d), 28, 28));
		//System.out.println(r.length);
		//OpenCVImage.show(AiLoadData.loadLableWithMNIST(new File("E:\\ai\\MNIST\\t10k-labels.idx1-ubyte"), 3604), OpenCVImage.resizeGray(d, 28, 28, 28, 28));

		//System.out.println(OpenCVImage.resizeGray(d, 28, 28, 3, 3).length);
		//System.out.println(Hex.encodeHexString(d));
		//StopWatch stopWatch = new StopWatch();
		//System.out.println(request.getSeq() + "-执行开始...");
		//stopWatch.start();
		//Convolution.convolve2dTo(AiLoadData.loadImageWithMNIST(new File("E:\\ai\\MNIST\\train-images.idx3-ubyte"), 1),
		//		AiLoadData.MNIST_IMAGE_WIDTH, AiLoadData.MNIST_IMAGE_HEIGHT);
		//stopWatch.stop();
		// 统计执行时间（毫秒）
		//System.out.println("执行时长：" + stopWatch.getLastTaskTimeMillis() + " 毫秒.");
		//OpenCVImage.show(readLableWithMNIST(new File("E:\\ai\\MNIST\\train-labels.idx1-ubyte"),1),
		//		loadImageWithMNIST(new File("E:\\ai\\MNIST\\train-images.idx3-ubyte"),1));
		//System.out.println(readLableWithMNIST(new File("E:\\ai\\MNIST\\train-labels.idx1-ubyte"),2));
		//d = OpenCVImage.resizeGray(d, 28, 28, 3, 3);
				BufferedImage img = OpenCVImage.createGrayImage(d,28,28);
//		BufferedImage img = OpenCVImage.createGrayImage(d, 3, 3);
		//BufferedImage img = OpenCVImage.createGrayImage(d,9,9);
		//BufferedImage img = OpenCVImage.createGrayImage(d,4,4);
		//img =OpenCVImage.resizeGray(img, 3, 3);
		//		System.out.println(((DataBufferByte)img.getData().getDataBuffer()).getData().length);
		ImageIO.write(img, "tiff", new File("E:\\ai\\MNIST\\9.tiff"));

	}

	public static BufferedImage resizeGray(BufferedImage original, int targetWidth, int targetHeight) {

		// 创建目标图像
		BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_BYTE_GRAY);

		// 计算水平和垂直缩放比例
		double xScale = (double) targetWidth / original.getWidth();
		double yScale = (double) targetHeight / original.getHeight();

		// 遍历目标图像的像素
		for (int y = 0; y < targetHeight; y++) {
			for (int x = 0; x < targetWidth; x++) {

				// 利用缩放比例计算原图像中的对应点        
				int srcX = (int) (x / xScale);
				int srcY = (int) (y / yScale);

				// 获取原图像的像素并赋值到目标图像
				int rgb = original.getRGB(srcX, srcY);
				resized.setRGB(x, y, rgb);

			}
		}

		return resized;
	}

	public static void main1(String[] args) throws Exception {
		//int r = 1 << getHighestSetBit(100);
		int r = Integer.highestOneBit(100) << 1;
		System.out.println(r);
		int a = Byte.toUnsignedInt((byte) 255);
		System.out.println(Integer.toHexString(a));
	}

}
