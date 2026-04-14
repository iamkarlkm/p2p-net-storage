package db;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.highgui.HighGui;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.image.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;

/**
 * OpenCV 图像处理工具/示例（显示、缩放、灰度转换等）。
 *
 * <p>该文件为演示/实验用途，不参与 ds 存储主流程。</p>
 */
public class OpenCVImage {
	public static void loadDll() {
		// 解决awt报错问题
		System.setProperty("java.awt.headless", "false");
		System.out.println(System.getProperty("java.library.path"));
		// 加载动态库
		URL url = ClassLoader.getSystemResource("D:\\cv\\opencv\\build\\java\\x64\\opencv_java480.dll");
		System.load(url.getPath());
	}

	public static void showGray(String windowName,byte[] data,int width,int height) {
Loader.load(opencv_java.class);
		Mat image = new Mat(width, height, CvType.CV_8UC1);
		image.put(0, 0, data);

		//MatOfInt params = new MatOfInt(Imgcodecs.IMREAD_GRAYSCALE);
		//Imgcodecs..imdecode(image, Imgcodecs.IMREAD_GRAYSCALE);

		// 显示图像

		//String windowName = "Image";
		HighGui.imshow(windowName, image);
		HighGui.waitKey(0);
		HighGui.destroyWindow(windowName);
	}
	
	public static byte[] resizeGray(byte[] data,int width,int height, int targetWidth, int targetHeight) {
		Loader.load(opencv_java.class);
		Mat image = new Mat(28, 28, CvType.CV_8U);
		image.put(0, 0, data);
		Mat out = resizeGray(image, targetWidth, targetHeight);
		byte[] res = new byte[targetWidth*targetHeight];
		out.get(0, 0, res);
		return res;
	}
	
	public static Mat resizeGray(Mat original, int targetWidth, int targetHeight) {

    Mat resized = new Mat();
    
    // 调用resize方法缩放
    Imgproc.resize(original, resized, new Size(targetWidth, targetHeight));
    
    return resized;
  } 
	
		// Mat -> BufferedImage
public static BufferedImage matToBufferedImage(Mat mat) {
  int type = BufferedImage.TYPE_BYTE_GRAY;
  if (mat.channels() > 1) {
    type = BufferedImage.TYPE_3BYTE_BGR;
  }
  BufferedImage image = new BufferedImage(mat.cols(), mat.rows(), type);
  mat.get(0, 0, ((DataBufferByte)image.getRaster().getDataBuffer()).getData());  
  return image;
}

// BufferedImage -> byte[]
public static byte[] bufferedImage2Bytes(BufferedImage img) {
  ByteArrayOutputStream baos = new ByteArrayOutputStream();
  try {
    ImageIO.write(img, "png", baos);
  } catch (IOException e) {
    e.printStackTrace();
  }
  return baos.toByteArray(); 
}

// BufferedImage -> byte[]
public static BufferedImage bytes2BufferedImage(byte[] data) throws IOException {
  ByteArrayInputStream bais = new ByteArrayInputStream(data);
  
    return ImageIO.read(bais);
}

public BufferedImage createBufferedImage(int width, 
                  int height,
                  byte[] pixels) {
//          ColorSpace cs = ColorSpace.getInstance(ColorSpace.CS_GRAY);
//                int[] nBits = {8};
//           ColorModel     cm = new ComponentColorModel(cs, nBits, false, true,
//                                                     Transparency.OPAQUE,
//                                                     DataBuffer.TYPE_BYTE);
//          SampleModel sm = 
//                  getIndexSampleModel((IndexColorModel) cm, 
//                  width, height);
//          DataBuffer db = new DataBufferByte(pixels, 
//                  width * height, 0);
//          WritableRaster raster = 
//                  Raster.createWritableRaster(sm, db, null);
//          BufferedImage image = new BufferedImage(cm, 
//                                          raster, false, null);

          return null;
      }

public static final BufferedImage createGrayImage(final byte[] pixels, final int width, final int height) {
 // BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
 ColorSpace cs = ColorSpace.getInstance(ColorSpace.CS_GRAY);
                int[] nBits = {8};
           ColorModel     cm = new ComponentColorModel(cs, nBits, false, true,
                                                     Transparency.OPAQUE,
                                                     DataBuffer.TYPE_BYTE);
  SampleModel sm = cm.createCompatibleSampleModel(width, height);
  DataBuffer db = new DataBufferByte(pixels, width*height, 0);
  WritableRaster raster = Raster.createWritableRaster(sm, db, null);
            
  return new BufferedImage(cm, raster, false, null);
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

	public static void main(String[] args) throws Exception {
Loader.load(opencv_java.class);
		//Mat image = Imgcodecs.imread("E:\\ai\\1.png");
		
		Mat image = Imgcodecs.imread("E:\\ai\\2.jpg");
//Imgcodecs.im
		// 显示图像
		System.out.println(image);
		String windowName = "图像";
		HighGui.imshow(windowName, image);
		HighGui.waitKey(0);
		//HighGui.destroyWindow(windowName);
		image = Imgcodecs.imread("E:\\ai\\1.png");
		System.out.println(image);
		HighGui.resizeWindow(windowName, image.width(), image.height());
		HighGui.imshow(windowName, image);
		
		HighGui.waitKey(0);  
		//HighGui.destroyWindow(windowName);
		//System.out.println("888");
		//HighGui.destroyWindow(windowName);
		//HighGui.destroyAllWindows();
	}
}
