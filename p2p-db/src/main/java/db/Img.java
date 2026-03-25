/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package db;

import ij.ImagePlus;
import ij.process.ImageProcessor;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.WritableRaster;
import java.io.File;

/**
 * @author karl
 */
public class Img {

    public static void main(String[] args) throws Exception {
        // 获取BufferedImage的WritableRaster
        //int width = 200;
        //int height = 200;

        // 创建一个BufferedImage对象
//        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
//        Graphics2D g2d = image.createGraphics();
//        g2d.setColor(Color.WHITE);
//		g2d.fillRect(0, 0, 200, 200);
//        // 使用g2d进行画图
//		g2d.setColor(Color.RED);
////        g2d.setColor(Color.BLACK);
//        g2d.fillRect(50, 50, 100, 100); // 画一个红色的矩形
//
//        // 释放图形上下文的系统资源
//        g2d.dispose();

        // 加载图像
        BufferedImage image = ImageIO.read(new File("E:\\ai\\MNIST\\9.tiff"));
//			BufferedImage image = ImageIO.read(new File("E:\\ai\\MNIST\\mm.png"));
        System.out.println("image type:" + image.getType());
        int width = image.getWidth();
        int height = image.getHeight();
        // 接下来可以获取图像的RGB数据
        WritableRaster raster = image.getRaster();

        image.copyData(raster);

        DataBufferByte dataBuffer = (DataBufferByte) raster.getDataBuffer();

        ImagePlus imagePlus = new ImagePlus("红色的矩形", image);

        ImageProcessor ip = imagePlus.getProcessor();
        //ip.setColor(Color.WHITE);
        //ip.drawLine(0,0,28,0);
        byte[] data = dataBuffer.getData();
        //System.out.println(ip.getClass());
        //ij.process.ColorProcessor
        //System.out.println(((Object[])ip.getPixels())[0]);
//				byte[] data = (byte[]) ip.getPixels();
        System.out.println(toString(data, width, height));
        //OpenCVImage.showGray ("data",data,width,height);
        Loader.load(opencv_java.class);
//				Mat mat = Imgcodecs.imread("E:\\ai\\MNIST\\mm.png",Imgcodecs.IMREAD_GRAYSCALE);
//        Mat mat = Imgcodecs.imread("E:\\ai\\MNIST\\9.tiff", Imgcodecs.IMREAD_GRAYSCALE);
        Mat mat = Imgcodecs.imread("E:\\ai\\MNIST\\9.tiff");
        Mat dst = new Mat();
        // 灰度化
        Imgproc.cvtColor(mat, dst, Imgproc.COLOR_BGR2GRAY);
        // 显示灰度图像
//        HighGui.imshow("gray", dst);
//        HighGui.waitKey(0);
//        HighGui.destroyWindow("gray");
        //dst = new Mat();
        // 调用Canny算法进行边缘检测
        //Imgproc.Canny(mat, dst, 50, 100, 3, false);
        /**
         * let src = cv.imread('canvasInput');
         * let dst = new cv.Mat();
         * // You can try more different parameters
         * cv.cvtColor(src,dst, cv.COLOR_BGR2GRAY); // 先要转换为灰度图片
         * let dst2 = new cv.Mat();
         * cv.threshold(dst , dst2, 120, 255, cv.THRESH_BINARY);
         * cv.imshow('canvasOutput', dst2);
         * src.delete();
         * dst.delete();
         */
        Mat dst2 = new Mat();
        Imgproc.threshold(dst, dst2, 128, 255, Imgproc.THRESH_BINARY);
//        Imgproc.threshold(dst, dst2, 177, 255, Imgproc.THRESH_BINARY);
        // 显示结果图像
        //HighGui.imshow("9", dst);
        //HighGui.waitKey(0);
       // HighGui.destroyWindow("9");
//Mat mat = Imgcodecs.imread("E:\\ai\\MNIST\\9.tiff");
        System.out.println("mat.channels()=" + mat.channels());
        //mat.
        //mat.get(0, 0, data);
//		int[] pixels = (int[]) ip.getPixels();
//            for (int i = 0; i < pixels.length; i++) {
//				if(pixels[i]!=0){
//				System.out.println(i);
//			}
//			}
        //BufferedImage out = OpenCVImage.createGrayImage(data, image.getWidth(), image.getHeight());
        // 保存或显示结果图像
        //ImageIO.write(out, "png", new File("E:\\ai\\MNIST\\mm.png"));
        System.out.println(data.length + ":" + (width * height));
        System.out.println(mat);
        int topX = 0, leftX = 0, rightX = width, bottomX = width;
        int topY = 0, leftY = 0, rightY = height, bottomY = height;

        boolean top = false, left = false, right = false, bottom = false;
//		StringBuilder sb = new StringBuilder("  ");
//		StringBuilder sb1 = new StringBuilder("  ");
//
//		for(int i = 0;i<width;i++){
//			sb.append(String.format("%4d", i));
//			sb1.append("   *");
//		}
//		System.out.println(sb);
//		System.out.println(sb1);
        byte[] test = new byte[width * height];
        int index = 0;
        for (int i = 0; i < height; i++) {
            int[] row = new int[width];
            int[] row2 = new int[width];
            for (int j = 0; j < width; j++) {

                byte[] p = new byte[1];
                dst2.get(i, j, p);
//						int R1 = p[2] & 0xff;
//                    int G1 = p[2] & 0xff;
//                    int B1 = p[0] & 0xff;
//				int gray1 =	(R1*38 + G1*75 + B1*15) >> 7;
//						row[j] =gray1&0xff;

                int rgb = image.getRGB(j, i);
                int R = (rgb >> 16) & 0xff;
                int G = (rgb >> 8) & 0xff;
                int B = rgb & 0xff;
                int gray = (R * 38 + G * 75 + B * 15) >> 7;
                test[index] = p[0];
                index++;
                row2[j] = gray & 0xff;
            }


        }
		index = 0;
        for (int y = 0; y < height; y++) {

            for (int x = 0; x < width; x++) {
                if ((data[index] & 0xff) > 0) {
                        topX = x;
                        topY = y;
                        top = true;
                    System.out.println(topX + ":" + topY+" top pixel->"+(data[index] & 0xff));
                        break;
                }
			index++;

            }

            if(top) break;
        }
        index = 0;
        for (int x = 0; x < width; x++) {

            for (int y = 0; y<height; y++) {

                if ((data[index+y*width] & 0xff) > 0) {
                    leftX = x;
                    leftY = y;
                    left = true;
                    System.out.println(leftX + ":" + leftY+" left pixel->"+(data[index+y*width] & 0xff));
                    break;
                }
            }
            if(left) break;
            index++;
        }
        System.out.println(toString(test, width, height));
        //OpenCVImage.showGray ("test",test,width,height);
		index = height * width - 1;
        for (int y = height - 1; y >= 0; y--) {
            for (int x = width - 1; x >= 0; x--) {
                if ((data[index] & 0xff) > 0) {
                        bottomX = x;
                        bottomY = y;
                        bottom = true;
                    System.out.println(bottomX + ":" + bottomY+" bottom pixel->"+(data[index] & 0xff));
                        break;
                }
				index--;
            }
			if(bottom) break;
        }

        index = height * width - 1;
		for (int x = width-1; x >= 0; x--) {

		    for (int y = 0; y<height; y++) {

				if ((data[index-y*width] & 0xff) > 0) {
					rightX = x;
					rightY = height-y-1;
					right = true;
                    System.out.println(rightX + ":" + rightY+" right pixel->"+(data[index-y*width] & 0xff));
					break;
				}
			}
			if(right) break;
            index--;
		}

        Mat rect = rectObject(dst);
        System.out.println(toMatString(rect));
//        System.out.println(leftX + ":" + leftY);
//        System.out.println(rightX + ":" + rightY);
//        System.out.println(bottom+":"+bottomX + ":" + bottomY);
        //		 ImagePlus imagePlus = IJ.openImage("mountains.jpeg");

//		 ip.setColor(Color.WHITE);
//			 ip.drawLine(0,topY,width,topY);
//			 //ip.drawLine(0,0,width,height);
//			 ip.drawLine(0,bottomY,width,bottomY);
//			 ip.drawLine(leftX,0,leftX,height);
//			 ip.drawLine(rightX,0,rightX,height);


        //ip.scale(width/2, height/2);
        //ip.exp();
        //		 imagePlus.updateImage();
//		imagePlus.updateAndDraw();
//		 imagePlus.updateAndRepaintWindow();
//        imagePlus.show();
        //Canny c = new Canny();
        //c.setup(null, imagePlus);
        //c.canny(ip, 7, 3.0, 30, 100);

        //ImageConverter ic = new ImageConverter(imagePlus);
        //ic.convertToHSB();

    }

    /**
     * 获取Mat内部对象的边界范围,规范化为正方形
     * @param mat 灰度二值化图像数据
     * @return
     */
    public static Mat rectObject(Mat mat) {
        Rect rect = Imgproc.boundingRect(mat);
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
                if(y<0){
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
                if(x<0){
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

        return new Mat(mat, rect);
    }

    public static String toString(int[] a, int width, int height, int elementWidth) {
        if (a == null)
            return "null";
        int iMax = a.length - 1;
//        if (iMax == -1)
//            return "[]";
        if (iMax == -1)
            return "[]";
        String format = "%" + elementWidth + "d";
        String format1 = "%" + (elementWidth + 1) + "d";
        String format2 = "%" + elementWidth + "s";
        StringBuilder b = new StringBuilder();
        StringBuilder sb = new StringBuilder("  ");
        StringBuilder sb1 = new StringBuilder("  ");

        for (int i = 0; i < width; i++) {
            sb.append(String.format(format1, i));
            sb1.append(String.format(format2, "*"));
        }
        b.append(sb).append('\n');
        b.append(sb1).append('\n');
        String formatY = "%" + Integer.valueOf(height).toString().length() + "d";
        int index = 0;
        for (int y = 0; y < height; y++) {
            b.append(String.format(formatY, y)).append('*');
            for (int x = 0; x < width; x++) {
                b.append(String.format(format, a[index]));
                index++;
            }
            b.append('\n');
        }

        return b.toString();
    }

    public static String toMatString(Mat mat) {
        byte[] data = new byte[mat.cols()* mat.rows()* mat.channels()];
        mat.get(0, 0, data);
        return toString(data, mat.cols(), mat.rows());
    }


    public static String toString(byte[] a, int width, int height) {
        if (a == null)
            return "null";
        int iMax = a.length - 1;
//        if (iMax == -1)
//            return "[]";
        if (iMax == -1)
            return "[]";
        String format = "%4d";
        StringBuilder b = new StringBuilder();
        StringBuilder sb = new StringBuilder("   ");
        StringBuilder sb1 = new StringBuilder("   ");

        for (int i = 0; i < width; i++) {
            sb.append(String.format("%4d", i));
            sb1.append("   *");
        }
        b.append(sb).append('\n');
        b.append(sb1).append('\n');
        String formatY = "%" + Integer.valueOf(height).toString().length() + "d";
        int index = 0;
        for (int y = 0; y < height; y++) {
            b.append(String.format(formatY, y)).append('*');
            for (int x = 0; x < width; x++) {
                b.append(String.format(format, a[index] & 0xff));
                index++;
            }
            b.append('\n');
        }

        return b.toString();
    }

    public static String toBinaryString(byte[] a, int width, int height) {
        if (a == null)
            return "null";
        if(a.length*8!=width*height){
            throw new RuntimeException("byte[] length*8 not match width*height ->"+a.length*8+":"+width*height);
        }
        int iMax = a.length - 1;
//        if (iMax == -1)
//            return "[]";
        if (iMax == -1)
            return "[]";
        String format = "%4d";
        StringBuilder b = new StringBuilder();
        StringBuilder sb = new StringBuilder("   ");
        StringBuilder sb1 = new StringBuilder("   ");

        for (int i = 0; i < width; i++) {
            sb.append(String.format("%4d", i));
            sb1.append("   *");
        }
        b.append(sb).append('\n');
        b.append(sb1).append('\n');
        String formatY = "%" + Integer.valueOf(height).toString().length() + "d";
        int index = 0;
        int w = width/8;
        for (int y = 0; y < height; y++) {
            b.append(String.format(formatY, y)).append('*');
            for (int x = 0; x < w; x++) {
                for(int i=0;i<8;i++){
                    b.append(String.format(format, (a[index] >> (7-i)) & 0x1));
                }
               // b.append(String.format(format, a[index] & 0xff));
                index++;
            }
            b.append('\n');
        }

        return b.toString();
    }

    public static String toString(int[] a, String format) {
        if (a == null)
            return "null";
        int iMax = a.length - 1;
//        if (iMax == -1)
//            return "[]";
        if (iMax == -1)
            return "[]";

        StringBuilder b = new StringBuilder();
        //b.append('[');
        for (int i = 0; ; i++) {
            b.append(String.format(format, a[i]));
            if (i == iMax)
                return b.toString();
//			return b.append(']').toString();
//            b.append(", ");
        }
    }

    /**
     * 对于彩色转灰度，有一个很著名的心理学公式：
     * <p>
     *                           Gray = R*0.299 + G*0.587 + B*0.114
     * <p>
     * Gray = (R*19595 + G*38469 + B*7472) >> 16
     * <p>
     * 　　2至20位精度的系数：
     * <p>
     *                           Gray = (R*1 + G*2 + B*1) >> 2
     * <p>
     *                           Gray = (R*2 + G*5 + B*1) >> 3
     * <p>
     *                           Gray = (R*4 + G*10 + B*2) >> 4
     * <p>
     *                           Gray = (R*9 + G*19 + B*4) >> 5
     * <p>
     *                           Gray = (R*19 + G*37 + B*8) >> 6
     * <p>
     *                           Gray = (R*38 + G*75 + B*15) >> 7
     * <p>
     *                           Gray = (R*76 + G*150 + B*30) >> 8
     * <p>
     *                           Gray = (R*153 + G*300 + B*59) >> 9
     * <p>
     *                           Gray = (R*306 + G*601 + B*117) >> 10
     * <p>
     *                           Gray = (R*612 + G*1202 + B*234) >> 11
     * <p>
     *                           Gray = (R*1224 + G*2405 + B*467) >> 12
     * <p>
     *                           Gray = (R*2449 + G*4809 + B*934) >> 13
     * <p>
     *                           Gray = (R*4898 + G*9618 + B*1868) >> 14
     * <p>
     *                           Gray = (R*9797 + G*19235 + B*3736) >> 15
     * <p>
     *                           Gray = (R*19595 + G*38469 + B*7472) >> 16
     * <p>
     *                           Gray = (R*39190 + G*76939 + B*14943) >> 17
     * <p>
     *                           Gray = (R*78381 + G*153878 + B*29885) >> 18
     * <p>
     *                           Gray = (R*156762 + G*307757 + B*59769) >> 19
     * <p>
     *                           Gray = (R*313524 + G*615514 + B*119538) >> 20
     * <p>
     * 　　仔细观察上面的表格，这些精度实际上是一样的：3与4、7与8、10与11、13与14、19与20
     * 所以16位运算下最好的计算公式是使用7位精度，比先前那个系数缩放100倍的精度高，而且速度快：
     * Gray = (R*38 + G*75 + B*15) >> 7
     * <p>
     * 　　其实最有意思的还是那个2位精度的，完全可以移位优化：
     * <p>
     * Gray = (R + (WORD)G<<1 + B) >> 2
     *
     * @param rgb
     */
    public byte toGray8(int rgb) {
        int R = (rgb >> 16) & 0xff;
        int G = (rgb >> 8) & 0xff;
        int B = rgb & 0xff;
        int gray = (R * 38 + G * 75 + B * 15) >> 7;
        return 0;

    }

}
