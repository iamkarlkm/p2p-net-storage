package db;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.opencv.core.CvType.CV_8UC1;

public class ImageSkewDetection {

    public static Mat detectAndCorrectSkew(Mat image) {
        // 1. 转换为灰度图
        Mat grayImage = new Mat();
        if(image.channels() == 3){
            Imgproc.cvtColor(image, grayImage, Imgproc.COLOR_BGR2GRAY);
        }else{
            grayImage = image;
        }


        // 2. 应用高斯模糊
        //Imgproc.GaussianBlur(grayImage, grayImage, new Size(5, 5), 0);

        // 3. Canny边缘检测
        Mat edges = new Mat();
        Imgproc.Canny(grayImage, edges, 75, 200);
//        Imgproc.Canny(grayImage, edges, 50, 100, 3, false);

        // 4. 找到边缘的轮廓
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        MatOfPoint2f[] contoursArray = new MatOfPoint2f[1];
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

        // 5. 找到最长的轮廓，假设这是文本行
        double maxArea = 0;
        MatOfPoint largestContour = null;
        for (MatOfPoint contour : contours) {
            MatOfPoint2f contour2f = new MatOfPoint2f(contour.toArray());
            double area = Imgproc.contourArea(contour2f);
            if (area > maxArea) {
                largestContour = contour;
                maxArea = area;
            }
        }

        if(largestContour == null){
            //System.out.println("No text found in the image."+contours);
            return grayImage;
        }

        // 6. 计算轮廓的最小外接矩形
        MatOfPoint2f largestContour2f = new MatOfPoint2f(largestContour.toArray());
        Rect boundingRect = Imgproc.boundingRect(largestContour2f);
        // 7. 获取矩形的四个顶点
        Point[] rectPoints = new Point[] {
                new Point(boundingRect.x, boundingRect.y),
                new Point(boundingRect.x + boundingRect.width, boundingRect.y),
                new Point(boundingRect.x + boundingRect.width, boundingRect.y + boundingRect.height),
                new Point(boundingRect.x, boundingRect.y + boundingRect.height)
        };

        // 8. 计算旋转角度
        double angle = 0;
        for (int i = 0; i < 3; i++) {
            double dx = rectPoints[i].x - rectPoints[0].x;
            double dy = rectPoints[i].y - rectPoints[0].y;
            if (Math.abs(dy) > 1e-2) {
                angle = Math.atan2(dy, dx) * 180.0 / Math.PI;
                break;
            }
        }


       // double angle = -calculateSkewAngle(largestContour);
        //System.out.println("Skew angle: " + angle);
        //System.out.println();

        // 9. 获取矩形的中心点
        Point center = new Point(
                boundingRect.x + boundingRect.width / 2,
                boundingRect.y + boundingRect.height / 2
        );

        // 10. 计算旋转矩阵
        Mat rotationMatrix = Imgproc.getRotationMatrix2D(center, angle+15, 1);

        //11. 应用旋转矩阵
        Mat rotatedImage = new Mat();
        Imgproc.warpAffine(image, rotatedImage, rotationMatrix, image.size());

        return rotatedImage;
    }

    private static double calculateSkewAngle(MatOfPoint contour) {
        // 将MatOfPoint转换为MatOfPoint2f
        MatOfPoint2f contour2f = new MatOfPoint2f(contour.toArray());

        /**
         * points: 一个包含多个MatOfPoint2f的列表，每个MatOfPoint2f代表一组2D点。
         * line: 输出的直线参数，MatOfPoint2f或MatOfPoint3f类型，具体取决于是2D拟合还是3D拟合。
         * distType: 距离类型，用于定义点到直线的距离的度量方式，如Imgproc.DIST_L2表示欧几里得距离。
         * param: 拟合算法的迭代终止参数（与reps和aeps一起使用）。
         * reps: 拟合迭代的容许次数。
         * aeps: 拟合迭代的容许误差。
         */
        // 调用fitLine函数拟合直线
        MatOfPoint2f line = new MatOfPoint2f();
        Imgproc.fitLine(contour2f, line, Imgproc.DIST_L2, 0, 0.01, 0.01);

        // 获取fitLine函数返回的直线参数
        double[] lineParams = new double[4];
        for (int i = 0; i < lineParams.length; i++) {
            lineParams[i] = line.get(i, 0)[0];
        }

        // lineParams[0], lineParams[1] 是直线的法向量的x和y分量
        // lineParams[2], lineParams[3] 是直线上的一个点的x和y坐标
        double vx = lineParams[0], vy = lineParams[1]; // 直线的方向向量
        double angle = Math.toDegrees(Math.atan2(vy, vx)); // 计算角度


//        // lineParams[1]和lineParams[0]是直线的斜率和截距
//        double slope = lineParams[1] / lineParams[0];
//
//        // 计算角度，斜率与角度的关系为：theta = atan(slope)
//        double angle = Math.atan(slope) * (180.0 / Math.PI);

        // 如果拟合的直线斜率较小，可能需要调整角度的计算方式
//        if (Math.abs(slope) < 1e-10) {
//            angle = 0; // 直线水平
//        } else if (slope < 0) {
//            angle = 180 - angle; // 调整角度范围
//        }
//
        return angle;
    }

    public static void main(String[] args) throws IOException {
        // 加载OpenCV
        Loader.load(opencv_java.class);
//        OpenCVLoader.initDebug();
//        String openCvPath = "path_to_opencv"; // 替换为OpenCV的配置文件路径
//        Core.loadLibrary(openCvPath);
        File dir = new File("E:\\ai\\MNIST");
        AiLoadData loadData = new AiLoadData(dir);
//        byte[] data = loadData.loadTrainImage(418);
        byte[] data = loadData.loadTrainImage(11);

//        int type = depth == 1 ? CV_8UC1 : CV_8UC3;
        Mat image = new Mat(28, 28, CV_8UC1);
        int count = image.put(0, 0, data);
        System.out.println(Img.toMatString(image));
        // 读取图像
        //Mat image = Imgcodecs.imread("path_to_image"); // 替换为图像文件的路径

        // 检测并纠正倾斜
        Mat correctedImage = detectAndCorrectSkew(image);

        // 显示结果
        // 显示灰度图像
//        HighGui.imshow("gray", correctedImage);
//        HighGui.waitKey(0);
//        HighGui.destroyWindow("gray");
        // Imgcodecs.imwrite("corrected_image.png", correctedImage);
        // 这里可以添加代码来显示或保存校正后的图像
        System.out.println(Img.toMatString(correctedImage));
    }
}