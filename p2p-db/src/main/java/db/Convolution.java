package db;

import cn.hutool.core.date.StopWatch;
import com.q3lives.utils.SerializationUtil;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;

import java.awt.image.BufferedImage;
import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.opencv.core.CvType.CV_8UC1;
import static org.opencv.core.CvType.CV_8UC3;
import org.opencv.core.Size;
import static org.opencv.imgproc.Imgproc.INTER_AREA;
import static org.opencv.imgproc.Imgproc.INTER_LINEAR;
import static org.opencv.imgproc.Imgproc.INTER_MAX;
// import org.apache.commons.lang3.ArrayUtils;

public class Convolution {






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

	public final static byte canny3x3(byte[] input, int baseOffset, int depth,int x, int y){
//		int[] arr = median9Array.get();
		int rowOffset = y*depth;
//		int size =arr.length ;

		int pixel = 0;
		pixel += (input[baseOffset -rowOffset-1] & 0xff) *KERNEL[0][0];
		pixel +=  (input[baseOffset -rowOffset] & 0xff) *KERNEL[0][1];
		pixel +=  (input[baseOffset -rowOffset+1] & 0xff) *KERNEL[0][2];
		pixel +=  (input[baseOffset -1] & 0xff) *KERNEL[1][0];
		pixel +=  (input[baseOffset ] & 0xff) *KERNEL[1][1];
		pixel +=  (input[baseOffset +1] & 0xff) *KERNEL[1][2];
		pixel +=  (input[baseOffset +rowOffset-1] & 0xff) *KERNEL[2][0];
		pixel +=  (input[baseOffset +rowOffset] & 0xff) *KERNEL[2][1];
		pixel +=  (input[baseOffset +rowOffset+1] & 0xff) *KERNEL[2][2];

		return (byte) ((pixel > 255 ? 255 : (pixel < 0 ? 0 : pixel)) | 0xffffff00);



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

	// 3x3 卷积核示例，这里使用了一个边缘检测核
//	private static final int[][] KERNEL = {
//			{-1, -1, -1},
//			{-1,  8, -1},
//			{-1, -1, -1}
//	};
	
	private static final int[][] KERNEL = {
			{1, 1, 1},
			{1,  0, 1},
			{1, 1, 1}
	};

	public static byte[] convolve3x3(byte[] input,int depth, int x, int y) {
		//System.out.println(input.length + " -> " + x + ":" + y);

		int outIndex = 0;

		int size_x = x-1;
		int size_y =y-1;
		int size = (x-2) * (y-2)*depth;
		byte[] output = new byte[size];
		for (int i = 1; i < size_x; i ++) {
			//System.out.println("rows"+i);
			for (int j = 1; j < size_y; j ++) {
				int base = i*x+j;
				//System.out.println("cols"+j);
				for(int k = 0; k < depth; k++){
//					output[outIndex] = median9(input, base+k, depth, x, y);
					output[outIndex] = canny3x3(input, base+k, depth, x, y);
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




	public static AiNode convolve2d(String label, byte[] input, int depth, int x, int y) {
		//System.out.println(input.length + " convolve2dTo -> " + x + ":" + y);
		byte[] res = input;
		//System.out.println("res:"+res.length);
		//		int size_x = (x / 3)+(x % 3==0?0:1);
		//		int size_y = (y / 3)+(y % 3==0?0:1);
		int size_x = x;
		int size_y = y;
		AiNode node = new AiNode(label, res);
		int level = 0;
		while (res.length > 9) {
			//System.out.println("while -> " + size_x + ":" + size_y);
			res = convolve3x3(res,  depth,size_x, size_y);
			//			size_x = (size_x / 3)+(size_x % 3==0?0:1);
			//			size_y = (size_y / 3)+(size_y % 3==0?0:1);
			node = new AiNode(level,label, res,node);
			//node = sub;
			size_x -= 2;
			size_y -= 2;
			//node.level = level;
			level++;
			//System.out.println(node);
		}

		//System.out.println(level);
		//System.out.println(Arrays.toString(res));
		int val = 0;
		if (res.length > 0) {
			/*
			 * 2 9 4
			 * 7 5 3
			 * 6 1 8
			 */
			for (int i = 0; i < res.length; i++) {
				switch (i) {
					case 0 -> val += (res[i] & 0b10000000) << 1;
					case 1 -> val += (res[i] & 0b10000000);
					case 2 -> val += (res[i] & 0b10000000) >> 1;
					case 3 -> val += (res[i] & 0b10000000) >> 2;
					case 4 -> val += (res[i] & 0b10000000) >> 3;
					case 5 -> val += (res[i] & 0b10000000) >> 4;
					case 6 -> val += (res[i] & 0b10000000) >> 5;
					case 7 -> val += (res[i] & 0b10000000) >> 6;
					case 8 -> val += (res[i] & 0b10000000) >> 7;
				}

			}
		}
		BigInteger key = new BigInteger(Integer.toString(val));
		//System.out.println(Integer.toHexString(val));
		return  new AiNode(level,label, res,node);
	}

	/**
	 * 获取Mat内部对象的边界范围,规范化为正方形
	 * @param mat 灰度二值化图像数据
	 * @return
	 */
	public static Mat rectObject(Mat mat) {
		Rect rect = Imgproc.boundingRect(mat);
		//System.out.println(rect);
		if(rect.width != rect.height){
			if(rect.width > rect.height){
				int diff = (rect.width - rect.height)/2;
//                int diff_half = diff/2;
//                int rest = diff%2;
				int y = rect.y -diff ;
//                if(rest==0){
//                    y -= diff_half;
//                }else{
//                    y -= (diff_half+rest);
//                }
				if(y<0||(y+rect.width)>mat.rows()){
					Mat mat2 = new Mat(rect.width, rect.width, mat.type());
					Mat dst =new Mat(mat, rect);
					byte[] data = new byte[dst.cols()* dst.rows()* dst.channels()];
					mat.get(0, 0, data);
					mat2.put( diff,0, data);
					return mat2;
				}
				rect.y = y;
				rect.height = rect.width;

			}else{
				int diff = (rect.height - rect.width)/2;
//                int diff_half = diff/2;
//                int rest = diff%2;
				int x = rect.x -diff ;
//                if(rest==0){
//                    x -= diff_half;
//                }else{
//                    x -= (diff_half+rest);
//                }
				if(x<0||(x+rect.height)>mat.cols()){
					Mat mat2 = new Mat(rect.height, rect.height, mat.type());
					Mat dst =new Mat(mat, rect);
					byte[] data = new byte[dst.cols()* dst.rows()* dst.channels()];
					mat.get(0, 0, data);
					//mat2.put(0, (diff_half+rest), data);
					mat2.put(0, diff, data);
					return mat2;
				}
				rect.x = x;
				rect.width = rect.height;
			}
		}
		//int x = rect.x;
		//int y = rect.y;
		//System.out.println(x + ":" + y);
		//System.out.println(rect);
		//System.out.println(mat.size());
		return new Mat(mat, rect);
	}
	
	/**
	 * 获取Mat内部对象的边界范围,规范化为正方形
	 * @param mat 灰度二值化图像数据
	 * @return
	 */
	public static Mat maxPool3x3(Mat mat) {
		byte[] input = new byte[mat.cols()* mat.rows()* mat.channels()];
		mat.get(0, 0, input);
		
		Mat out = new Mat(mat.cols()/3, mat.rows()/3, mat.type());
		byte[] output = new byte[out.cols()* out.rows()* out.channels()];
		int depth = mat.channels();
		int outIndex = 0;
		int x = mat.cols();
		int y = mat.rows();
		int size_x = mat.cols()-1;
		int size_y =mat.rows()-1;
		int size = output.length;
		for (int i = 1; i < size_x; i +=3) {
			//System.out.println("rows"+i);
			for (int j = 1; j < size_y; j +=3) {
				int base = i*x+j;
				//System.out.println("cols"+j);
				for(int k = 0; k < depth; k++){
//					output[outIndex] = median9(input, base+k, depth, x, y);
//					output[outIndex] = canny3x3(input, base+k, depth, x, y);
					output[outIndex] =  max3x3_threshold(input, base+k, depth, x, y,128,255);
					//output[_x] = getMedian(tmp);
					outIndex++;
				}

				if (outIndex > size) {
					break;
				}

			}
		}
		out.put( 0,0, output);
		
		return out;
	}
	
	/**
	 * 获取Mat内部对象的边界范围,规范化为正方形
	 * @param mat 灰度二值化图像数据
	 * @return
	 */
	public static Mat maxPool2x2(Mat mat) {
		byte[] input = new byte[mat.cols()* mat.rows()* mat.channels()];
		mat.get(0, 0, input);
		
		Mat out = new Mat(mat.cols()/2, mat.rows()/2, mat.type());
		byte[] output = new byte[out.cols()* out.rows()* out.channels()];
		int depth = mat.channels();
		int outIndex = 0;
		int x = mat.cols();
		int y = mat.rows();
		int size_x = mat.cols()-1;
		int size_y =mat.rows()-1;
		int size = output.length;
		for (int i = 1; i < size_x; i +=3) {
			//System.out.println("rows"+i);
			for (int j = 1; j < size_y; j +=3) {
				int base = i*x+j;
				//System.out.println("cols"+j);
				for(int k = 0; k < depth; k++){
//					output[outIndex] = median9(input, base+k, depth, x, y);
//					output[outIndex] = canny3x3(input, base+k, depth, x, y);
					output[outIndex] =  max3x3_threshold(input, base+k, depth, x, y,128,255);
					//output[_x] = getMedian(tmp);
					outIndex++;
				}

				if (outIndex > size) {
					break;
				}

			}
		}
		out.put( 0,0, output);
		
		return out;
	}
	
	public static byte[] maxPool_(byte[] input,int depth, int x, int y) {
		//System.out.println(input.length + " -> " + x + ":" + y);

		int outIndex = 0;

		int size_x = x-1;
		int size_y =y-1;
		int size = (x-2) * (y-2)*depth;
		byte[] output = new byte[size];
		for (int i = 1; i < size_x; i ++) {
			//System.out.println("rows"+i);
			for (int j = 1; j < size_y; j ++) {
				int base = i*x+j;
				//System.out.println("cols"+j);
				for(int k = 0; k < depth; k++){
//					output[outIndex] = median9(input, base+k, depth, x, y);
					output[outIndex] = max3x3_threshold(input, base+k, depth, x, y,128,255);
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
	
	public final static byte max3x3(byte[] input, int baseOffset, int depth,int x, int y){
//		int[] arr = median9Array.get();
		int rowOffset = y*depth;
//		int size =arr.length ;

		int pixel = (input[baseOffset -rowOffset-1] & 0xff);
		int pixel_next = (input[baseOffset -rowOffset] & 0xff);
		if(pixel < pixel_next ){
			pixel = pixel_next;
		}
		pixel_next = (input[baseOffset -rowOffset+1] & 0xff);
		if(pixel < pixel_next ){
			pixel = pixel_next;
		}
		pixel_next = (input[baseOffset -1] & 0xff);
		if(pixel < pixel_next ){
			pixel = pixel_next;
		}
		pixel_next = (input[baseOffset] & 0xff);
		if(pixel < pixel_next ){
			pixel = pixel_next;
		}
		pixel_next = (input[baseOffset +1] & 0xff);
		if(pixel < pixel_next ){
			pixel = pixel_next;
		}
		pixel_next = (input[baseOffset +rowOffset-1] & 0xff);
		if(pixel < pixel_next ){
			pixel = pixel_next;
		}
		pixel_next = (input[baseOffset +rowOffset] & 0xff);
		if(pixel < pixel_next ){
			pixel = pixel_next;
		}
		pixel_next = (input[baseOffset +rowOffset+1] & 0xff);
		if(pixel < pixel_next ){
			pixel = pixel_next;
		}
		
//		return (byte) ((pixel > 255 ? 255 : (pixel < 0 ? 0 : pixel)) | 0xffffff00);

		return (byte) pixel;

	}
	
	public final static byte max3x3_threshold(byte[] input, int baseOffset, int depth,int x, int y,int thresh,int maxval){
//		int[] arr = median9Array.get();
		int rowOffset = y*depth;
//		int size =arr.length ;

		if((input[baseOffset -rowOffset] & 0xff)>thresh ){
			return (byte) maxval;
		}
		if((input[baseOffset -rowOffset+1] & 0xff)>thresh ){
			return (byte) maxval;
		}
		if((input[baseOffset -1] & 0xff)>thresh ){
			return (byte) maxval;
		}
		if((input[baseOffset] & 0xff)>thresh ){
			return (byte) maxval;
		}
		if((input[baseOffset +1] & 0xff)>thresh ){
			return (byte) maxval;
		}
		if((input[baseOffset +rowOffset-1] & 0xff)>thresh ){
			return (byte) maxval;
		}
		if((input[baseOffset +rowOffset] & 0xff)>thresh ){
			return (byte) maxval;
		}
		if((input[baseOffset +rowOffset+1] & 0xff)>thresh ){
			return (byte) maxval;
		}
		
//		return (byte) ((pixel > 255 ? 255 : (pixel < 0 ? 0 : pixel)) | 0xffffff00);

		return (byte) 0;

	}

	public static ImgNode summary2d(int index,String label, byte[] input, int depth, int x, int y) {
		//System.out.println(input.length + " convolve2dTo -> " + x + ":" + y);
		//System.out.println(Img.toString(input,x,y));
		int type = depth == 1 ? CV_8UC1 : CV_8UC3;
		Mat mat = new Mat(y, x, type);
		int count = mat.put(0, 0, input);
//		System.out.println(Img.toMatString(mat));
//		System.out.println(label+" count:"+count);
		if(mat.channels()==3){
			Mat result = new Mat();
			Imgproc.cvtColor(mat, result, Imgproc.COLOR_RGB2GRAY);
			mat = result;
		}
		if(index==418||2532==index){
			//System.out.println(Img.toMatString(mat));
		}
//		Mat dst2 = mat;
		Mat guassian = new Mat();
//		Mat guassian = new Mat(mat.cols(),mat.rows(),CV_8UC1);
//		Imgproc.GaussianBlur(mat,guassian, new Size(3, 3), 1.0);
//		Mat dst2 = guassian;
		Mat dst2 = new Mat();
//		Imgproc.threshold(guassian, dst2, 100, 255, Imgproc.THRESH_BINARY);
		Imgproc.threshold(mat, dst2, 128, 255, Imgproc.THRESH_BINARY);
//		Imgproc.threshold(mat, dst2, 80, 255, Imgproc.THRESH_BINARY);
		Mat dst3 = new Mat();
		// 调用Canny算法进行边缘检测
//		Imgproc.Canny(dst2, dst3, 50, 100, 3, false);
//		Imgproc.Canny(mat, dst3, 50, 100, 3, false);
//		Mat mat2 = dst3;
//		Mat mat2 = mat;
//		dst2 = ImageSkewDetection.detectAndCorrectSkew(dst2);
//dst3 = ImageSkewDetection.detectAndCorrectSkew(dst3);
//		Mat mat2 =  ImageSkewDetection.detectAndCorrectSkew(mat);
		//if(dst3==null){
			//System.out.println(index);
			//System.out.println(Img.toMatString(dst2));
		//	dst3=dst2;
		//}
//		Mat mat2 = rectObject(dst3);
//		Mat mat2 = rectObject(dst2);
//		Mat mat2 = rectObject(mat);
Mat mat2 = dst2;
		//System.out.println(Img.toMatString(mat2));
		int size = mat2.cols();
//		if(size!=27){
//			size = 27;
//			Mat dst = new Mat(27,27,CV_8UC1);
//			//System.out.println(mat2.cols()+":"+mat2.rows());
//
//			Imgproc.resize(mat2, dst, dst.size(), 0, 0, org.opencv.imgproc.Imgproc.INTER_AREA);//INTER_MAX INTER_LINEAR org.opencv.imgproc.Imgproc.INTER_MAX
//			mat2 = dst;
//		}
//		int size_x = size&0xfffffff0;
//		if(index==418||2532==index){
//			//System.out.println(Img.toMatString(mat2));
//		}
//		if(size_x==0){
////			if(index==418){
//			System.out.println(size_x+":"+index+":"+label+":"+Img.toMatString(mat2));
//		}
		ImgNode root = null;
//		if(size>size_x){
//			Mat dst = new Mat(size_x,size_x,CV_8UC1);
//			//System.out.println("size_x:"+size_x);
//
//			Imgproc.resize(mat2, dst, dst.size(), 0, 0, INTER_AREA);
//			root = new ImgNode(label, dst,size_x,root);
//			root.index = index;
//		}
		//System.out.println(size_x);
		Mat dst = mat2;
		//int size_x = dst.cols();
		size = dst.cols();
		while (size > 3) {
//			size_x = size_x>>1;
//			//System.out.println("size_x:"+size_x);
//			dst = new Mat(size_x,size_x,CV_8UC1);
//			
//			//System.out.println(size);
//			Imgproc.resize(mat2, dst, dst.size(), 0, 0, INTER_AREA);
//			root = new ImgNode(label, dst,size,root);
			
			dst = maxPool3x3(dst);
			size = dst.cols();
			if(size<3) break;
			root = new ImgNode(label, dst,size,root);
			
			root.index = index;

		}
//		if(root==null){
//			System.out.println(index+"***************");
//			System.out.println(Img.toMatString(dst2));
//			System.out.println(Img.toMatString(mat2));
//		}

		return  root;
	}
	
	public static ImgNode summary2d2(int index,String label, byte[] input, int depth, int x, int y) {
		//System.out.println(input.length + " convolve2dTo -> " + x + ":" + y);
		//System.out.println(Img.toString(input,x,y));
		int type = depth == 1 ? CV_8UC1 : CV_8UC3;
		Mat mat = new Mat(y, x, type);
		int count = mat.put(0, 0, input);
//		System.out.println(Img.toMatString(mat));
//		System.out.println(label+" count:"+count);
		if(mat.channels()==3){
			Mat result = new Mat();
			Imgproc.cvtColor(mat, result, Imgproc.COLOR_RGB2GRAY);
			mat = result;
		}
		if(index==418||2532==index){
			//System.out.println(Img.toMatString(mat));
		}
		Mat guassian = new Mat();
//		Mat guassian = new Mat(mat.cols(),mat.rows(),CV_8UC1);
//		Imgproc.GaussianBlur(mat,guassian, new Size(3, 3), 1.0);
		Mat dst2 = new Mat();
//		Imgproc.threshold(guassian, dst2, 100, 255, Imgproc.THRESH_BINARY);
		Imgproc.threshold(mat, dst2, 128, 255, Imgproc.THRESH_BINARY);
//		Imgproc.threshold(mat, dst2, 80, 255, Imgproc.THRESH_BINARY);
		Mat dst3 = new Mat();
		// 调用Canny算法进行边缘检测
//		Imgproc.Canny(dst2, dst3, 50, 100, 3, false);
//		Imgproc.Canny(mat, dst3, 50, 100, 3, false);
//		Mat mat2 = dst3;
//		Mat mat2 = mat;
//		dst3 = ImageSkewDetection.detectAndCorrectSkew(dst2);
//		Mat mat2 =  ImageSkewDetection.detectAndCorrectSkew(mat);
		//if(dst3==null){
			//System.out.println(index);
			//System.out.println(Img.toMatString(dst2));
		//	dst3=dst2;
		//}
//		Mat mat2 = rectObject(dst3);
		Mat mat2 = rectObject(dst2);
//		Mat mat2 = rectObject(mat);
		//System.out.println(Img.toMatString(mat2));
		int size = mat2.cols();
		if(size<16){
			size = 16;
			Mat dst = new Mat(16,16,CV_8UC1);
			//System.out.println(mat2.cols()+":"+mat2.rows());

			Imgproc.resize(mat2, dst, dst.size(), 0, 0, INTER_LINEAR);
		}
		int size_x = size&0xfffffff0;
		if(index==418||2532==index){
			//System.out.println(Img.toMatString(mat2));
		}
		if(size_x==0){
//			if(index==418){
			System.out.println(size_x+":"+index+":"+label+":"+Img.toMatString(mat2));
		}
		ImgNode root = null;
		if(size>size_x){
			Mat dst = new Mat(size_x,size_x,CV_8UC1);
			//System.out.println("size_x:"+size_x);

			Imgproc.resize(mat2, dst, dst.size(), 0, 0, INTER_AREA);
			root = new ImgNode(label, dst,size_x,root);
			root.index = index;
		}
		//System.out.println(size_x);
		while (size_x > 4) {
			size_x = size_x>>1;
			//System.out.println("size_x:"+size_x);
			Mat dst = new Mat(size_x,size_x,CV_8UC1);
			Imgproc.resize(mat2, dst, dst.size(), 0, 0, INTER_AREA);
			root = new ImgNode(label, dst,size_x,root);
			root.index = index;

		}
//		if(root==null){
//			System.out.println(index+"***************");
//			System.out.println(Img.toMatString(dst2));
//			System.out.println(Img.toMatString(mat2));
//		}

		return  root;
	}

	public static int getHighestSetBit(int n) {
		for (int i = 31; i >= 0; i--) {
			if (((n >>> i) & 1) == 1) {
				return i + 1;
			}
		}
		return 0;
	}
	
	public static void main11(String[] args) throws Exception {
		AiNode root = SerializationUtil.deserialize(AiNode.class, Files.readAllBytes(Paths.get("E:\\ai\\MNIST\\AiNode.model")));
		File dir = new File("E:\\ai\\MNIST");
		AiLoadData loadData = new AiLoadData(dir);
		byte[] d = loadData.loadTestImage(9);
		String label = loadData.loadTestLable(9);
		
//		byte[] d = loadData.loadTrainImage(9000);
//		String label = loadData.loadTrainLable(9000);
		
		StopWatch stopWatch = new StopWatch();
		System.out.println( "-执行开始...");
		stopWatch.start();
		AiNode test = convolve2d(label, d, 1,28, 28);
//		AiNode test = convolve2d(null, d, 1,28, 28);
		
		AiNode r = root.predict( test,5);

		stopWatch.stop();
		 //统计执行时间（毫秒）
		System.out.println("执行时长：" + stopWatch.getLastTaskTimeNanos() + " 纳秒.");
		System.out.println(label+":"+test);
		//System.out.println("test parent="+test.parent);
		System.out.println(r);
	}


	public static int null_count = 0;

	public static int old_not_null_count = 0;

	public static int node_key_count = 0;

	public static int old_key_count = 0;

	public static void main(String[] args) throws Exception {
		Loader.load(opencv_java.class);
		//System.out.println(new File("E:\\ai\\MNIST\\train-images.idx3-ubyte").exists());
		File dir = new File("E:\\ai\\MNIST");
		AiLoadData loadData = new AiLoadData(dir);
//		Map<Short, String> modelShort = new HashMap();
//		Map<Integer, String> modelInt = new HashMap();
//		Map<Long, String> model = new HashMap();
		//AiNode root = null;
		ImgNode root = null;
		StopWatch stopWatch = new StopWatch();
		System.out.println( "-执行开始...");
		stopWatch.start();
		//train
//		for (int i = 0; i < 3; i++) {
			for (int i = 0; i < 60000; i++) {
			byte[] d = loadData.loadTrainImage(i);
			
			String lable = loadData.loadTrainLable(i);
//			int keyInt = convolve2dToInt(d, 1,28, 28);
//			modelInt.put(keyInt, lable);
//			short keyShort = convolve2dToShort(d, 1,28, 28);
//			modelShort.put(keyShort, lable);
//			long key = convolve2dToLong(d, 1,28, 28);
//			model.put(key, lable);
//			if(root!=null){
//				root.addNode(convolve2d(lable, d, 1,28, 28));
//			}else{
//				root = convolve2d(lable, d, 1,28, 28);
//			}
				if(root!=null){
					root.addNode(summary2d(i,lable, d, 1,28, 28));
				}else{
					root = summary2d(i,lable, d, 1,28, 28);
				}
		}

//		for (int i = 0; i < 10000; i++) {
//			byte[] d = loadData.loadTestImage(i);
//
//			String lable = loadData.loadTestLable(i);
//			if(root!=null){
//				root.addNode(summary2d(i,lable, d, 1,28, 28));
//			}else{
//				root = summary2d(i,lable, d, 1,28, 28);
//			}
//		}

		stopWatch.stop();
		 //统计执行时间（毫秒）
		System.out.println("执行时长：" + stopWatch.getLastTaskTimeMillis() + " 毫秒.");
		System.out.println("root="+root);
		System.out.println("root branches="+root.branches.size());
//byte[] bytes = SerializationUtil.serialize(root);
//Files.write(Paths.get("E:\\ai\\MNIST\\AiNode.model"), bytes);

//root = SerializationUtil.deserialize(AiNode.class, Files.readAllBytes(Paths.get("E:\\ai\\MNIST\\AiNode.model")));
		int count= 0;
		int countErrEq= 0;
		int countErrInt= 0;
		int countErr= 0;
//		AiNode test = null;
		ImgNode test = null;
		System.out.println( "-执行开始...");
		stopWatch.start();
		//test
//		for (int i = 0; i < 100; i++) {
			for (int i = 0; i < 10000; i++) {

			byte[] d = loadData.loadTestImage(i);
			String label = loadData.loadTestLable(i);
			test = summary2d(i,label, d, 1,28, 28);
			//System.out.println(test);
			//System.out.println("test parent="+test.parent);
			ImgNode r = root.predict( test,9,1f);

			//System.out.println(r);
			//System.out.println("r parent="+r.parent);
			if(r==null){
				countErr++;
//				r = root.predict( test,8);
//				if(r==null){
//					r = root.predict( test,16);
//					if(r==null){
//						countErr++;
//					}
//				}
			}
			if(r!=null ){
				if(test.label.equals(r.label)){
					count++;
				}else{
					countErrEq++;
					//System.out.println(label+":"+r.label);
				}
			}
//			else {
//				countErr++;
////				System.out.println(i+":"+test);
////				System.out.println(r);
//			}
//			int keyInt = convolve2dToInt(d, 1,28, 28);
//			String lablePredictInt = modelInt.get(keyInt);
//			if(lablePredictInt==null) {
//				countErrInt++;
//			}

//			short keyShort = convolve2dToShort(d, 1,28, 28);
//			String lablePredictShort = modelShort.get(keyShort);
//			if(lablePredictShort==null) {
//				countErrShort++;
//			}
//
//			long key = convolve2dToLong(d, 1,28, 28);
//			String lablePredict = model.get(key);
//			if(lablePredict==null) {
//					countErr++;
//			}else if(lable.equals(lablePredict)) {
//				count++;
//			}

		}
		stopWatch.stop();
		//统计执行时间（毫秒）
		System.out.println("执行时长：" + stopWatch.getLastTaskTimeMillis() + " 毫秒.");
		System.out.println("count="+count);
		System.out.println("countErr="+countErr);
		System.out.println("countErrEq="+countErrEq);
		System.out.println("null_count="+null_count);
		System.out.println("old_not_null_count="+old_not_null_count);
		System.out.println("node_key_count="+node_key_count);
		System.out.println("old_key_count="+old_key_count);
		//
		byte[] d = loadData.loadTrainImage(418);
		String label = loadData.loadTrainLable(418);
//		byte[] d = loadData.loadTestImage(2);
//		String label = loadData.loadTestLable(2);
		test = summary2d(2,label, d, 1,28, 28);
		//System.out.println(test);
		//System.out.println("test parent="+test.parent);
		ImgNode r = root.predict( test,5,0.9f);

		System.out.println(label+" (index 418) ->"+r);

		d = loadData.loadTrainImage(2532);
		label = loadData.loadTrainLable(2532);
//		byte[] d = loadData.loadTestImage(2);
//		String label = loadData.loadTestLable(2);
		test = summary2d(2,label, d, 1,28, 28);
//		System.out.println(test);
		//System.out.println("test parent="+test.parent);
		 r = root.predict( test,5,0.9f);

		System.out.println(label+" (index 2532) ->"+r);
		//d = toByteArray(convolve2da(toIntArray(d), 28, 28));
		//System.out.println(r.length);
		//OpenCVImage.show(AiLoadData.loadLableWithMNIST(new File("E:\\ai\\MNIST\\train-labels.idx1-ubyte"), 2), OpenCVImage.resizeGray(d, 28, 28, 28, 28));
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
		//		BufferedImage img = OpenCVImage.createGrayImage(d,28,28);
		//BufferedImage img = OpenCVImage.createGrayImage(d, 3, 3);
		//BufferedImage img = OpenCVImage.createGrayImage(d,9,9);
		//BufferedImage img = OpenCVImage.createGrayImage(d,4,4);
		//img =OpenCVImage.resizeGray(img, 3, 3);
		//		System.out.println(((DataBufferByte)img.getData().getDataBuffer()).getData().length);
		//ImageIO.write(img, "tiff", new File("E:\\ai\\MNIST\\2-5-6.tiff"));

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
