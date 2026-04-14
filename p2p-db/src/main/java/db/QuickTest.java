package db;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.global.opencv_highgui;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Scalar;

import java.io.UnsupportedEncodingException;

import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgproc.putText;

/**
 * JavaCV/OpenCV 的快速验证代码（读取图片并进行绘制/显示等操作）。
 *
 * <p>该文件为演示/实验用途，不参与 ds 存储主流程。</p>
 */
public class QuickTest {
	//static { Loader.load(); }
	
	
	
    //@Test
    public void test() throws Exception {
        System.out.println("Beginning LenetMNIST");
//        LeNetMNIST.main(new String[]{});
        System.out.println("Ending LenetMNIST");
        System.out.println("Beginning VaeMNISTAnomaly");
        //VaeMNISTAnomaly.main(new String[]{});
        //System.out.println("Beginning VaeMNISTAnomaly");
		//org.nd4j.linalg.factory.Nd4jBackend.load();
		//System.load("H:\\hf\\windows-x86_64\\jnicudart.dll");
		//System.load(Core.NATIVE_LIBRARY_NAME);
		//new opencv_core().
		main(null);
    }
	
		public static void main(String[] args) throws UnsupportedEncodingException {
        // 替换为您的图片路径
        String imagePath = "C:\\Users\\karl\\Pictures\\sd\\OIP-C.jpg";

        // 使用 OpenCV 加载图片
        Mat image = imread(imagePath);

        if (image.empty()) {
            System.out.println("图片未找到或路径错误！");
            return;
        }

        // 显示图片
       // opencv_highgui.imshow(new BytePointer("图片","GBK"), image);

        // 等待用户按键，再退出
        //opencv_highgui.waitKey(0);
		
		imagePath = "C:\\Users\\karl\\Pictures\\sd\\OIP-C2.jpg";

        // 使用 OpenCV 加载图片
        image = imread(imagePath);
		
		Scalar sc = Scalar.YELLOW;
			  putText(image, "liu yifei", new Point(8, 8),
			 opencv_imgproc.CV_FONT_HERSHEY_PLAIN, 0.8, sc, 1, opencv_imgproc.LINE_AA, false);
			
		//Scalar sc = Scalar.YELLOW;
			  putText(image, "刘亦菲", new Point(32, 32),
			 opencv_imgproc.CV_FONT_HERSHEY_PLAIN, 0.8, sc, 1, opencv_imgproc.LINE_AA, false);
			 
		// 显示图片
        opencv_highgui.imshow(new BytePointer("图片","GBK"), image);

        // 等待用户按键，再退出
        opencv_highgui.waitKey(0);

        // 释放窗口资源
        opencv_highgui.destroyAllWindows();
    }
}
