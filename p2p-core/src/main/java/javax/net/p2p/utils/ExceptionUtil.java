package javax.net.p2p.utils;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Exception工具类
 *
 * @author karl
 *
 */
public class ExceptionUtil {

    private static final Log log = LogFactory.getLog(ExceptionUtil.class);

    /**
     * 返回错误信息字符串
     *
     * @param ex Exception
     * @return 错误信息字符串
     */
    public static String getExceptionMessage(Exception ex) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            ex.printStackTrace(pw);
            String errorMessage = sw.toString();
            pw.close();

            sw.close();
            return errorMessage;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void logAndThrow(Throwable e) {
        log.error(e, e);
        throw new RuntimeException(e);
    }

}
