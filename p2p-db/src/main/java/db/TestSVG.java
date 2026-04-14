package db;
import java.awt.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import org.apache.batik.svggen.font.SVGFont;

import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

/**
 * 使用 Apache Batik 将 SVG/TIFF 等资源转码为 PNG 的示例。
 *
 * <p>该文件为演示/实验用途，不参与 ds 存储主流程。</p>
 */
public class TestSVG {
    String filePath="";
    TestSVG(String filePath) throws Exception {
        this.filePath=filePath;
        createImage2();
    }


    public void createImage() throws Exception{
        String svg_URI_input = new File("C:\\Users\\karl\\Pictures\\ai\\lenet.svg").toURL().toString();
        TranscoderInput input_svg_image = new TranscoderInput(svg_URI_input);
        //Step-2: Define OutputStream to PNG Image and attach to TranscoderOutput
        OutputStream png_ostream = new FileOutputStream(filePath);
        TranscoderOutput output_png_image = new TranscoderOutput(png_ostream);
        // Step-3: Create PNGTranscoder and define hints if required
        PNGTranscoder my_converter = new PNGTranscoder();
        my_converter.addTranscodingHint(PNGTranscoder.KEY_PIXEL_UNIT_TO_MILLIMETER,0.084672F);
my_converter.addTranscodingHint(PNGTranscoder.KEY_BACKGROUND_COLOR, Color.WHITE);
        // Step-4: Convert and Write output
        System.out.println("It will print");
        my_converter.transcode(input_svg_image, output_png_image);
        System.out.println("It will not print");
        png_ostream.flush();
        png_ostream.close();
    }
    
     public void createImage2() throws Exception{
        String svg_URI_input = new File("E:\\ai\\MNIST\\9.tiff").toURL().toString();
        TranscoderInput input_svg_image = new TranscoderInput(svg_URI_input);
        //Step-2: Define OutputStream to PNG Image and attach to TranscoderOutput
        OutputStream png_ostream = new FileOutputStream(filePath);
        TranscoderOutput output_png_image = new TranscoderOutput(png_ostream);
        // Step-3: Create PNGTranscoder and define hints if required
        PNGTranscoder my_converter = new PNGTranscoder();
        my_converter.addTranscodingHint(PNGTranscoder.KEY_PIXEL_UNIT_TO_MILLIMETER,0.084672F);
my_converter.addTranscodingHint(PNGTranscoder.KEY_BACKGROUND_COLOR, Color.WHITE);
        // Step-4: Convert and Write output
        System.out.println("It will print");
        my_converter.transcode(input_svg_image, output_png_image);
        System.out.println("It will not print");
        png_ostream.flush();
        png_ostream.close();
    }

    public static void main(String[] args) throws Exception {
//        TestSVG svg = new TestSVG("E:\\ai\\MNIST\\9.svg");
//        SVGFont.main(args);
       
         System.out.println("It will print");
          System.out.println("中文aaaaaa");
          System.out.println("It will print");
    }
}
