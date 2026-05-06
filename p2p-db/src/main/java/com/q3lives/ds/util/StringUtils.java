package com.q3lives.ds.util;

import java.beans.XMLDecoder;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * 字符串的一些處理方法工具类
 *
 * @author karl
 */
public class StringUtils extends org.apache.commons.lang.StringUtils {

    private static final Log log = LogFactory.getLog(StringUtils.class);

    private static final String INVALID_JSON_CHARS = "[\\t\\r\\n]";
    private static final Charset DEFAULT_ENCODING =  Charset.forName("UTF-8");
    public static final String NEW_LINE = System.getProperty("line.separator");

    private static final Pattern allNumericPattern = Pattern.compile("[^0-9]");
    private static final Pattern numericPattern = Pattern.compile("^[0-9\\-]+$");
    private static final Pattern numericStringPattern = Pattern.compile("^[0-9\\-\\-]+$");
    private static final Pattern floatNumericPattern = Pattern.compile("^[0-9\\-\\.]+$");
    private static final Pattern abcPattern = Pattern.compile("[^a-z]");
    public static final String splitStrPattern = ",|，|;|；|、|\\.|。|-|_|\\(|\\)|\\[|\\]|\\{|\\}|\\\\|/| |　|\"";

    protected static char hexDigits[] = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};
    public static final String[] monthArray = {"January", "February", "March", "April", "May", "June", "July",
        "August", "September", "October", "November", "December"};
    public static final String[] weekDayArray = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};


    public static String bytesToHex(byte bytes[]) {
        return bufferToHex(bytes, 0, bytes.length);
    }

    private static String bufferToHex(byte bytes[], int m, int n) {
        StringBuilder stringbuffer = new StringBuilder(2 * n);
        int k = m + n;
        for (int l = m; l < k; l++) {
            appendHexPair(bytes[l], stringbuffer);
        }
        return stringbuffer.toString();
    }

    private static void appendHexPair(byte bt, StringBuilder stringbuffer) {
        char c0 = hexDigits[(bt & 0xf0) >> 4];
        char c1 = hexDigits[bt & 0xf];
        stringbuffer.append(c0);
        stringbuffer.append(c1);
    }

    /**
     * 将字符串转为md5加密
     *
     * add by CaoQingqing
     *
     * @param str String
     * @return String
     */
    public static String toMd5(String str) {
        if (str != null && str.trim().length() != 0) {
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                md.update(str.getBytes());
                byte[] hash = md.digest();
                return bytesToHex(hash);
            } catch (NoSuchAlgorithmException e) {
                log.error(e, e);
            }
        }
        return null;
    }

    /**
     * 将ajax传入的参数解码
     *
     * add by CaoQingqing
     *
     * @param src String
     * @return String
     */
    public static String unAjaxEscape(String src) {
        return unEscape(unEscape(src));
    }

    /**
     * 将js escape编码参数解码
     *
     * add by wangdawei
     *
     * @param src 解码的字符串
     * @return String
     */
    public static String unEscape(String src) {
        if (src == null) {
            return null;
        }
        if (src.trim().equals("")) {
            return src;
        }
        StringBuilder tmp = new StringBuilder();
        tmp.ensureCapacity(src.length());
        int lastPos = 0, pos;
        char ch;
        while (lastPos < src.length()) {
            pos = src.indexOf("%", lastPos);
            if (pos == lastPos) {
                if (src.charAt(pos + 1) == 'u') {
                    ch = (char) Integer.parseInt(src.substring(pos + 2, pos + 6), 16);
                    tmp.append(ch);
                    lastPos = pos + 6;
                } else {
                    ch = (char) Integer.parseInt(src.substring(pos + 1, pos + 3), 16);
                    tmp.append(ch);
                    lastPos = pos + 3;
                }
            } else {
                if (pos == -1) {
                    tmp.append(src.substring(lastPos));
                    lastPos = src.length();
                } else {
                    tmp.append(src.substring(lastPos, pos));
                    lastPos = pos;
                }
            }
        }
        return tmp.toString();
    }

    /**
     * 将inputStream 转为 String 类型
     *
     * add by CaoQingqing
     *
     * @param ins InputStream
     * @return String
     */
    public static String streamToString(InputStream ins) {
        InputStreamReader isr = new InputStreamReader(ins);
        BufferedReader br = new BufferedReader(isr);
        StringBuilder s = new StringBuilder();
        try {
            String str;
            while ((str = br.readLine()) != null) {
                s.append(str);
            }
        } catch (IOException e) {
            log.error(e, e);
        } finally {
            try {
                isr.close();
            } catch (IOException e) {
                log.error(e, e);
            }
            try {
                br.close();
            } catch (IOException e) {
                log.error(e, e);
            }
        }
        return s.toString();
    }

    /**
     * 特殊字符的处理
     *
     * add by tanhaiwen
     *
     * @param oldString 需要处理的字符串
     * @return String
     */
    public static String htmlToStr(String oldString) {
        String rtnString = oldString;
        try {
            if (oldString == null) {
                return null;
            }
            if (StringUtils.isBlank(oldString)) {
                rtnString = oldString;
            } else {
                rtnString = oldString.replaceAll("<BR>", "\n").replace("<br>", "\n")
                        .replaceAll("&gt;", ">").replaceAll("&lt;", "<").replaceAll("&quot;", "\"")
                        .replaceAll("&nbsp;", " ");
            }
        } catch (Exception e) {
            log.error("字符串转换出错：" + e.getMessage());
        }
        return rtnString;
    }
    /**
     * 国标码和区位码转换常量
     */
    static final int GB_SP_DIFF = 160;
    /**
     * 存放国标一级汉字不同读音的起始区位码
     */
    static final int[] secPosValueList = {
        1601, 1637, 1833, 2078, 2274, 2302, 2433, 2594, 2787,
        3106, 3212, 3472, 3635, 3722, 3730, 3858, 4027, 4086,
        4390, 4558, 4684, 4925, 5249, 5600};
    /**
     * 存放国标一级汉字不同读音的起始区位码对应读音
     */
    static final char[] firstLetter = {
        'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'j',
        'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's',
        't', 'w', 'x', 'y', 'z'};

    /**
     * 获取一个字符串的拼音码
     *
     * @param oriStr 字符串
     * @return String 拼音码
     */
    public static String getFirstLetter(String oriStr) {
        String str = oriStr.toLowerCase();
        StringBuilder buffer = new StringBuilder();
        char ch;
        char[] temp;
        for (int i = 0; i < str.length(); i++) { // 依次处理str中每个字符
            ch = str.charAt(i);
            temp = new char[]{ch};
            byte[] uniCode = new String(temp).getBytes();
            if (uniCode[0] < 128 && uniCode[0] > 0) { // 非汉字
                buffer.append(temp);
            } else {
                buffer.append(convert(uniCode));
            }
        }
        return buffer.toString();
    }

    /**
     * 获取一个汉字的拼音首字母。 * GB码两个字节分别减去160，转换成10进制码组合就可以得到区位码 *
     * 例如汉字“你”的GB码是0xC4/0xE3，分别减去0xA0（160）就是0x24/0x43 *
     * 0x24转成10进制就是36，0x43是67，那么它的区位码就是3667，在对照表中读音为‘n’
     *
     * @param bytes 字符数组
     * @return char
     */
    static char convert(byte[] bytes) {
        char result = '-';
        int secPosValue;
        int i;
        for (i = 0; i < bytes.length; i++) {
            bytes[i] -= GB_SP_DIFF;
        }

        secPosValue = bytes[0] * 100 + bytes[1];
        for (i = 0; i < 23; i++) {
            if (secPosValue >= secPosValueList[i]
                    && secPosValue < secPosValueList[i + 1]) {
                result = firstLetter[i];
                break;
            }
        }
        return result;
    }

    public static String valueOf(Object str) {
        return null == str ? "" : str.toString();
    }

    /**
     * 转换异常信息为String
     *
     * @param cause Throwable
     * @return String 异常信息
     */
    public static String exceptionStacktraceToString(Throwable cause) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);
        cause.printStackTrace(ps);
        ps.close();
        return baos.toString();
    }

    /**
     * 根据过滤条件转换异常信息为String
     *
     * @param cause Throwable
     * @return String 异常信息
     */
    public static String exceptionStacktraceToStringByFilter(Throwable cause) {
        Set<String> filterPackages = new HashSet<String>();
        filterPackages.add("framework.webapp");
        filterPackages.add("org.linlinjava");
        filterPackages.add("com.q3lives");
        return exceptionStacktraceToString(cause, filterPackages);
    }

    /**
     * 转换异常信息为String
     *
     * @param cause Throwable
     * @param filterPackages 过滤条件
     * @return String
     */
    public static String exceptionStacktraceToString(Throwable cause, Set<String> filterPackages) {
        StringBuilder sb = new StringBuilder();
        if (cause.getMessage() != null) {
            sb.append(cause.getMessage());
        } else {
            sb.append(cause.toString());
        }
        sb.append('\n');
        for (StackTraceElement stack : cause.getStackTrace()) {
            for (String p : filterPackages) {
                if (stack.toString().contains(p)) {
                    sb.append("  at ");
                    sb.append(stack.toString());
                    sb.append('\n');
                }
            }
        }
        Throwable causeStack = cause.getCause();
        while (causeStack != null) {
            exceptionToString(sb, causeStack, filterPackages);
            causeStack = causeStack.getCause();
        }
        return sb.toString();
    }

    /**
     * 转换异常信息为String
     *
     * @param sb Stringbuilder
     * @param cause Throwable
     * @param filterPackages 过滤条件
     */
    private static void exceptionToString(StringBuilder sb, Throwable cause, Set<String> filterPackages) {
        sb.append("Caused by:");
        if (cause.getMessage() != null) {
            sb.append(cause.getMessage());
        } else {
            sb.append(cause.toString());
        }
        sb.append('\n');
        for (StackTraceElement stack : cause.getStackTrace()) {
            for (String p : filterPackages) {
                if (stack.toString().contains(p)) {
                    sb.append("  at ");
                    sb.append(stack.toString());
                    sb.append('\n');
                }
            }
        }
    }

    /**
     * String 转换为 inputStream
     *
     * @param str String
     * @return InputStream
     */
    public static InputStream String2InputStream(String str) {
        ByteArrayInputStream stream = new ByteArrayInputStream(str.getBytes());
        return stream;
    }

    /**
     * 转换inputStream 为String
     *
     * @param is inputSteam
     * @return String
     */
    public static String inputStream2String(InputStream is) {
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (IOException ex) {
            log.error(ex, ex);
        } finally {
            try {
                if (in != null) {
                    in.close();
                }

            } catch (IOException e) {
                //not log;
            }
        }
        return null;
    }

    /**
     * 转换inputStream 为String
     *
     * @param is inputstream
     * @param encode 编码格式
     * @return String
     */
    public static String inputStream2String(InputStream is, String encode) {
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(is, encode));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                sb.append(line);
                sb.append(System.getProperty("line.separator"));
            }
            return sb.toString();
        } catch (Exception ex) {
            log.error(ex, ex);
        } finally {
            try {
                in.close();
            } catch (IOException ex) {
                //not log
            }
        }
        return null;
    }

    /**
     * 按照传入的长度截取drop list中String长度，多余部分显示 ...
     *
     * @param src - 原String
     * @param length - 保留的长度
     * @return - 截取后的String
     */
    public static String droplistStringLengthConverter(String src, int length) {
        if (src != null && src.length() > length) {
            src = src.substring(0, length) + "...";
        }
        return src;
    }

    /**
     * 过滤特殊字符
     *
     * @param str 需啊哟过滤的字符
     * @return String
     */
    public static String StringFilter(String str) {
        // 清除掉所有特殊字符
        String regEx = "[`~!@#$%^&*|{}':;\"',/\\[\\]<>/?~！@#￥%……&*（）——+|{}【】‘；：”“’。，、？]";
        Pattern p = Pattern.compile(regEx);
        Matcher m = p.matcher(str);
        return m.replaceAll("_").trim().replace('\\', '_');
    }

    /**
     * 过滤特殊字符
     *
     * @param str 需要过滤的字符
     * @return String
     */
    public static String codeStringFilter(String str) {
        //只允许字母和数字		
        String regEx = "[^a-zA-Z0-9]";
        Pattern p = Pattern.compile(regEx);
        Matcher m = p.matcher(str);
        return m.replaceAll("_").trim();
    }

    /**
     * 拼接数个字符串 注:字符串之间带空格
     *
     * @param strs 需要拼接的字符串
     * @return 返回传入的字符串
     */
    public static String strConnect(String... strs) {
        StringBuilder strb = new StringBuilder("");
        if (strs != null && strs.length > 0) {
            for (String str : strs) {
                if (org.apache.commons.lang.StringUtils.isNotBlank(str)) {
                    strb.append(str);
                    strb.append(" ");
                }
            }
        }
        return strb.toString();
    }

    /**
     * 按照传入的长度参数截短String，超出部分用 "..." 表示. 部分组件Header部分等特殊位置无法使用JSF
     * Converter的地方需要调用此方法
     *
     * @param titleStr - 要截短的String
     * @param length - 截取长度
     * @return - 截取后的String
     */
    public static String convertString(String titleStr, int length) {
        StringBuilder result = new StringBuilder();
        if (titleStr.length() > length) {
            result.append(titleStr.substring(0, 1).toUpperCase());
            result.append(titleStr.substring(1, length));
            result.append("...");
            return result.toString();
        } else {
            result.append(titleStr.substring(0, 1).toUpperCase());
            result.append(titleStr.substring(1));
            return result.toString();
        }
    }

    public static String convertStreamToString(InputStream is) {

        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line = null;
        try {
            while ((line = reader.readLine()) != null) {
                sb.append(line + "\n");
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                reader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return sb.toString();
    }
    
    public static List<String> readLines(InputStream is,String encoding) throws Exception {
        List<String> list = new ArrayList();
        BufferedReader reader = null;
        try {
            String line = null;
            reader = new BufferedReader(new InputStreamReader(is, encoding));
            while ((line = reader.readLine()) != null) {
                list.add(line);
            }

        } catch (Exception e) {
            throw e;
        } finally {
            try {
                reader.close();
            } catch (IOException e) {
            }
        }
        return list;
    }

    /**
     * 处理文件上传特殊类型问题文件格式字符串
     *
     * @param str
     * @return string
     */
    public static String dealSpecialFileStr(String str) {
        String typeStr = str;
        if (typeStr != null) {
            String[] style = str.split("\\|");
            if (style.length > 1) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < style.length; i++) {
                    if (i + 1 == style.length) {
                        sb.append(style[i]);
                    } else {
                        sb.append(style[i]).append(" | ");
                    }
                }
                typeStr = sb.toString();
            }
        }
        return typeStr;
    }

    /**
     * 把object o为""的String属性置为null
     *
     * @param o
     * @param c
     */
    public static void setEmptyFieldToNull(Object o, Class< ?> c) {
        Field[] fields = c.getDeclaredFields();
        for (Field field : fields) {
            try {
                field.setAccessible(true);
                if (field.get(o) != null && field.get(o).getClass().equals(String.class) && StringUtils.isBlank((String) field.get(o).toString())) {
                    field.set(o, null);
                }
            } catch (IllegalArgumentException ex) {
                log.error(ex, ex);
            } catch (IllegalAccessException ex) {
                log.error(ex, ex);
            }
        }
    }

    /**
     * 首字母大写
     *
     * @param oriStr 字符串
     * @return String 首字母大写后的字符串
     */
    public static String upperFirstLetter(String oriStr) {
        if (null != oriStr && !oriStr.isEmpty()) {
            String str = oriStr.trim();
            return str.substring(0, 1).toUpperCase() + str.substring(1);
        } else {
            return "";
        }
    }

    /**
     * 根据输入的日期格式，给出相应日期格式的示例
     *
     * @param formate
     * @param locale
     * @return string
     */
    public static String writeExampleDateStr(String formate, Locale locale) {
        String answerDate = "";
        if (StringUtils.isNotBlank(formate)) {
            Format formatter = new SimpleDateFormat(formate, locale);//非国际化
            return answerDate = formatter.format(new Date());
        }
        return answerDate;
    }

    public static void writeUnicode(final DataOutputStream out, final String value) {
        try {
            final String unicode = encodeUnicode(value);
            final byte[] data = unicode.getBytes();
            final int dataLength = data.length;

            System.out.println("Data Length is: " + dataLength);
            System.out.println("Data is: " + value);
            out.writeInt(dataLength); //先写出字符串的长度
            out.write(data, 0, dataLength); //然后写出转化后的字符串
        } catch (IOException e) {

        }
    }

    public static String encodeUnicode(final String gbString) {
        char[] utfBytes = gbString.toCharArray();
        String unicodeBytes = "";
        for (int byteIndex = 0; byteIndex < utfBytes.length; byteIndex++) {
            String hexB = Integer.toHexString(utfBytes[byteIndex]);
            if (hexB.length() <= 2) {
                hexB = "00" + hexB;
            }
            unicodeBytes = unicodeBytes + "\\\\u" + hexB;
        }
        System.out.println("unicodeBytes is: " + unicodeBytes);
        return unicodeBytes;
    }

    /**
     * 将unicode字符串转为汉字 输入参数:源unicode字符串 输出参数:转换后的字符串
     *
     * @param dataStr
     * @return String
     */
    public static String decodeUnicode(final String dataStr) {
        if (!dataStr.contains("\\\\u")) {
            return dataStr;
        }
        int start = 0;
        int end = 0;
        final StringBuffer buffer = new StringBuffer();
        while (start > -1) {
            end = dataStr.indexOf("\\\\u", start + 3);
            String charStr = "";
            if (end == -1) {
                charStr = dataStr.substring(start + 3, dataStr.length());
            } else {
                charStr = dataStr.substring(start + 3, end);
            }
            char letter = (char) Integer.parseInt(charStr, 16); // 16进制parse整形字符串。
            buffer.append(new Character(letter).toString());
            start = end;
        }
        return buffer.toString();
    }

    /**
     * 7位ASCII字符，也叫作ISO646-US、Unicode字符集的基本拉丁块
     */
    public static final String US_ASCII = "US-ASCII";

    /**
     * ISO 拉丁字母表 No.1，也叫作 ISO-LATIN-1
     */
    public static final String ISO_8859_1 = "ISO-8859-1";

    /**
     * 8 位 UCS 转换格式
     */
    public static final String UTF_8 = "UTF-8";

    /**
     * 16 位 UCS 转换格式，Big Endian（最低地址存放高位字节）字节顺序
     */
    public static final String UTF_16BE = "UTF-16BE";

    /**
     * 16 位 UCS 转换格式，Little-endian（最高地址存放低位字节）字节顺序
     */
    public static final String UTF_16LE = "UTF-16LE";

    /**
     * 16 位 UCS 转换格式，字节顺序由可选的字节顺序标记来标识
     */
    public static final String UTF_16 = "UTF-16";

    /**
     * 中文超大字符集
     */
    public static final String GBK = "GBK";

    /**
     * 将字符编码转换成US-ASCII码
     *
     * @param str
     * @return String
     * @throws UnsupportedEncodingException
     */
    public static String toASCII(String str) throws UnsupportedEncodingException {
        return changeCharset(str, US_ASCII);
    }

    /**
     * 将字符编码转换成ISO-8859-1码
     *
     * @param str
     * @return String
     * @throws UnsupportedEncodingException
     */
    public static String toISO_8859_1(String str) throws UnsupportedEncodingException {
        return changeCharset(str, ISO_8859_1);
    }

    /**
     * 将字符编码转换成UTF-8码
     *
     * @param str
     * @return String
     * @throws UnsupportedEncodingException
     */
    public static String toUTF_8(String str) throws UnsupportedEncodingException {
        return changeCharset(str, UTF_8);
    }

    /**
     * 将字符编码转换成UTF-16BE码
     *
     * @param str
     * @return String
     * @throws UnsupportedEncodingException
     */
    public static String toUTF_16BE(String str) throws UnsupportedEncodingException {
        return changeCharset(str, UTF_16BE);
    }

    /**
     * 将字符编码转换成UTF-16LE码
     *
     * @param str String
     * @return String
     * @throws UnsupportedEncodingException
     */
    public static String toUTF_16LE(String str) throws UnsupportedEncodingException {
        return changeCharset(str, UTF_16LE);
    }

    /**
     * 将字符编码转换成UTF-16码
     *
     * @param str String
     * @return String
     * @throws UnsupportedEncodingException
     */
    public static String toUTF_16(String str) throws UnsupportedEncodingException {
        return changeCharset(str, UTF_16);
    }

    /**
     * 将字符编码转换成GBK码
     *
     * @param str String
     * @return String
     * @throws UnsupportedEncodingException
     */
    public static String toGBK(String str) throws UnsupportedEncodingException {
        return changeCharset(str, GBK);
    }

    /**
     * 字符串编码转换的实现方法
     *
     * @param str 待转换编码的字符串
     * @param newCharset 目标编码
     * @return
     * @throws UnsupportedEncodingException
     */
    public static String changeCharset(String str, String newCharset)
            throws UnsupportedEncodingException {
        if (str != null) {
            // 用默认字符编码解码字符串。
            byte[] bs = str.getBytes();
            // 用新的字符编码生成字符串
            return new String(bs, newCharset);
        }
        return null;
    }

    /**
     * 字符串编码转换的实现方法
     *
     * @param str 待转换编码的字符串
     * @param oldCharset 原编码
     * @param newCharset 目标编码
     * @return
     * @throws UnsupportedEncodingException
     */
    public static String changeCharset(String str, String oldCharset, String newCharset)
            throws UnsupportedEncodingException {
        if (str != null) {
            // 用旧的字符编码解码字符串。解码可能会出现异常。
            byte[] bs = str.getBytes(oldCharset);
            // 用新的字符编码生成字符串
            return new String(bs, newCharset);
        }
        return null;
    }

    public static String convertByLength(String str, int length) {
        if (str.length() > length) {
            StringBuilder result = new StringBuilder();
            result.append(str.substring(0, length));
            result.append("...");
            return result.toString();
        } else {
            return str;
        }
    }

    /**
     * 从给定字符串生成keywords count map
     *
     * @param str
     * @return
     */
    public static Map<String, Integer> getKeyWordsCountMap(String str) {
        Map<String, Integer> keywordsMap = new HashMap<String, Integer>();
        return getKeyWordsCountMap(str, keywordsMap);
    }

    /**
     * 从给定字符串生成keywords count并添加到给定map
     *
     * @param str 输入字符串
     * @param keywordsMap 给定map
     * @return
     */
    public static Map<String, Integer> getKeyWordsCountMap(String str, Map<String, Integer> keywordsMap) {
        String[] words = str.split(" ");
        for (String word : words) {
            Integer d = keywordsMap.get(word);
            if (d == null) {
                keywordsMap.put(word, 1);
            } else {
                keywordsMap.put(word, d++);
            }
        }
        return keywordsMap;
    }

    /**
     * 从给定map获得top keywords
     *
     * @param keywordsMap
     * @param limit top限制数
     * @return
     */
    public static List<String> getMaxCountKeyWordsWithLimit(Map<String, Integer> keywordsMap, int limit) {
        List<String> tagsList = new ArrayList<String>();
        //这里将map.entrySet()转换成list
        List<Map.Entry<String, Integer>> list = new ArrayList<Map.Entry<String, Integer>>(keywordsMap.entrySet());
        //然后通过比较器来实现排序
        Collections.sort(list, new Comparator<Map.Entry<String, Integer>>() {
            //降序排序
            public int compare(Map.Entry<String, Integer> o1,
                    Map.Entry<String, Integer> o2) {
                return o2.getValue().compareTo(o1.getValue());
            }

        });
        int count = 0;
        for (Map.Entry<String, Integer> mapping : list) {
            count++;
            tagsList.add(mapping.getKey());
            if (count == limit) {
                break;
            }
        }
        return tagsList;
    }

    /**
     * 判断是否数字表示
     *
     * @param src 源字符串
     * @return 是否数字的标志
     */
    public static boolean isNumeric(String src) {
        boolean return_value = false;
        if (src != null && src.length() > 0) {
            Matcher m = numericPattern.matcher(src);
            if (m.find()) {
                return_value = true;
            }
        }
        return return_value;
    }

    /**
     * 判断是否数字表示
     *
     * @param src 源字符串
     * @return 是否数字的标志
     */
    public static boolean isNumericString(String src) {
        boolean return_value = false;
        if (src != null && src.length() > 0) {
            Matcher m = numericStringPattern.matcher(src);
            if (m.find()) {
                return_value = true;
            }
        }
        return return_value;
    }

    /**
     * 判断是否纯字母组合
     *
     * @param src 源字符串
     * @return 是否纯字母组合的标志
     */
    public static boolean isABC(String src) {
        boolean return_value = true;
        if (src != null && src.length() > 0) {
            Matcher m = abcPattern.matcher(src.toLowerCase().trim());
            if (m.find()) {
                return_value = false;
            }
        }
        return return_value;
    }

    /**
     * 判断是否浮点数字表示
     *
     * @param src 源字符串
     * @return 是否数字的标志
     */
    public static boolean isFloatNumeric(String src) {
        boolean return_value = false;
        if (src != null && src.length() > 0) {
            Matcher m = floatNumericPattern.matcher(src);
            if (m.find()) {
                return_value = true;
            }
        }
        return return_value;
    }

    /**
     * 把string array or list用给定的符号symbol连接成一个字符串
     *
     * @param array
     * @param symbol
     * @return
     */
    public static String joinString(List array, String symbol) {
        String result = "";
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                String temp = array.get(i).toString();
                if (temp != null && temp.trim().length() > 0) {
                    result += (temp + symbol);
                }
            }
            if (result.length() > 1) {
                result = result.substring(0, result.length() - 1);
            }
        }
        return result;
    }

    public static String subStringNotEncode(String subject, int size) {
        if (subject != null && subject.length() > size) {
            subject = subject.substring(0, size) + "...";
        }
        return subject;
    }

    /**
     * 截取字符串　超出的字符用symbol代替
     *
     * @param len 字符串长度　长度计量单位为一个GBK汉字　两个英文字母计算为一个单位长度
     * @param str
     * @param symbol
     * @return
     */
    public static String getLimitLengthString(String str, int len, String symbol) {
        int iLen = len * 2;
        int counterOfDoubleByte = 0;
        String strRet = "";
        try {
            if (str != null) {
                byte[] b = str.getBytes("GBK");
                if (b.length <= iLen) {
                    return str;
                }
                for (int i = 0; i < iLen; i++) {
                    if (b[i] < 0) {
                        counterOfDoubleByte++;
                    }
                }
                if (counterOfDoubleByte % 2 == 0) {
                    strRet = new String(b, 0, iLen, "GBK") + symbol;
                    return strRet;
                } else {
                    strRet = new String(b, 0, iLen - 1, "GBK") + symbol;
                    return strRet;
                }
            } else {
                return "";
            }
        } catch (Exception ex) {
            return str.substring(0, len);
        } finally {
            strRet = null;
        }
    }

    /**
     * 截取字符串　超出的字符用symbol代替
     *
     * @param len 字符串长度　长度计量单位为一个GBK汉字　两个英文字母计算为一个单位长度
     * @param str
     * @param symbol
     * @return12
     */
    public static String getLimitLengthString(String str, int len) {
        return getLimitLengthString(str, len, "...");
    }

    /**
     *
     * 截取字符，不转码
     *
     * @param subject
     * @param size
     * @return
     */
    public static String subStrNotEncode(String subject, int size) {
        if (subject.length() > size) {
            subject = subject.substring(0, size);
        }
        return subject;
    }

    /**
     * 把string array or list用给定的符号symbol连接成一个字符串
     *
     * @param array
     * @param symbol
     * @return
     */
    public static String joinString(String[] array, String symbol) {
        String result = "";
        if (array != null) {
            for (int i = 0; i < array.length; i++) {
                String temp = array[i];
                if (temp != null && temp.trim().length() > 0) {
                    result += (temp + symbol);
                }
            }
            if (result.length() > 1) {
                result = result.substring(0, result.length() - 1);
            }
        }
        return result;
    }

    /**
     * 取得字符串的实际长度（考虑了汉字的情况）
     *
     * @param SrcStr 源字符串
     * @return 字符串的实际长度
     */
    public static int getStringLen(String SrcStr) {
        int return_value = 0;
        if (SrcStr != null) {
            char[] theChars = SrcStr.toCharArray();
            for (int i = 0; i < theChars.length; i++) {
                return_value += (theChars[i] <= 255) ? 1 : 2;
            }
        }
        return return_value;
    }

    /**
     * 检查数据串中是否包含非法字符集
     *
     * @param str
     * @return [true]|[false] 包含|不包含
     */
    public static boolean check(String str) {
        String sIllegal = "'\"";
        int len = sIllegal.length();
        if (null == str) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (str.indexOf(sIllegal.charAt(i)) != -1) {
                return true;
            }
        }

        return false;
    }

    /**
     * *************************************************************************
     * getHideEmailPrefix - 隐藏邮件地址前缀。
     *
     * @param email - EMail邮箱地址 例如: linwenguo@koubei.com 等等...
     * @return 返回已隐藏前缀邮件地址, 如 *********@koubei.com.
     * @version 1.0 (2006.11.27) Wilson Lin
     * ************************************************************************
     */
    public static String getHideEmailPrefix(String email) {
        if (null != email) {
            int index = email.lastIndexOf('@');
            if (index > 0) {
                email = repeat("*", index).concat(email.substring(index));
            }
        }
        return email;
    }

    /**
     * *************************************************************************
     * repeat - 通过源字符串重复生成N次组成新的字符串。
     *
     * @param src - 源字符串 例如: 空格(" "), 星号("*"), "浙江" 等等...
     * @param num - 重复生成次数
     * @return 返回已生成的重复字符串
     * @version 1.0 (2006.10.10) Wilson Lin
     * ************************************************************************
     */
    public static String repeat(String src, int num) {
        StringBuffer s = new StringBuffer();
        for (int i = 0; i < num; i++) {
            s.append(src);
        }
        return s.toString();
    }

    /**
     * 根据指定的字符把源字符串分割成一个数组
     *
     * @param src
     * @return
     */
    public static List<String> parseString2ListByCustomerPattern(String pattern, String src) {

        if (src == null) {
            return null;
        }
        List<String> list = new ArrayList<String>();
        String[] result = src.split(pattern);
        for (int i = 0; i < result.length; i++) {
            list.add(result[i]);
        }
        return list;
    }

    /**
     * 根据指定的字符把源字符串分割成一个数组
     *
     * @param src
     * @return
     */
    public static List<String> parseString2ListByPattern(String src) {
        String pattern = "，|,|、|。";
        return parseString2ListByCustomerPattern(pattern, src);
    }

    /**
     * 格式化一个float
     *
     * @param format 要格式化成的格式 such as #.00, #.#
     */
    public static String formatFloat(float f, String format) {
        DecimalFormat df = new DecimalFormat(format);
        return df.format(f);
    }

    /**
     * 判断是否是空字符串 null和"" 都返回 true
     *
     * @author Robin Chang
     * @param s
     * @return
     */
    public static boolean isEmpty(String s) {
        if (s != null && !s.equals("")) {
            return false;
        }
        return true;
    }

    /**
     * 判断是否是空字符串 null和"" 都返回 truefalse
     *
     * @author Robin Chang
     * @param s
     * @return
     */
    public static boolean isNotBlank(String s) {
        if (s != null && s.trim().length()>0) {
            return true;
        }
        return false;
    }

    /**
     * 自定义的分隔字符串函数 例如: 1,2,3 =>[1,2,3] 3个元素 ,2,3=>[,2,3] 3个元素 ,2,3,=>[,2,3,]
     * 4个元素 ,,,=>[,,,] 4个元素
     *
     * 5.22算法修改，为提高速度不用正则表达式 两个间隔符,,返回""元素
     *
     * @param split 分割字符 默认,
     * @param src 输入字符串
     * @return 分隔后的list
     * @author Robin
     */
    public static List<String> splitToList(String split, String src) {
        // 默认,
        String sp = ",";
        if (split != null && split.length() == 1) {
            sp = split;
        }
        List<String> r = new ArrayList<String>();
        int lastIndex = -1;
        int index = src.indexOf(sp);
        if (-1 == index && src != null) {
            r.add(src);
            return r;
        }
        while (index >= 0) {
            if (index > lastIndex) {
                r.add(src.substring(lastIndex + 1, index));
            } else {
                r.add("");
            }

            lastIndex = index;
            index = src.indexOf(sp, index + 1);
            if (index == -1) {
                r.add(src.substring(lastIndex + 1, src.length()));
            }
        }
        return r;
    }

    /**
     * 把 名=值 参数表转换成字符串 (a=1,b=2 =>a=1&b=2)
     *
     * @param map
     * @return
     */
    public static String linkedHashMapToString(LinkedHashMap<String, String> map) {
        if (map != null && map.size() > 0) {
            String result = "";
            Iterator it = map.keySet().iterator();
            while (it.hasNext()) {
                String name = (String) it.next();
                String value = (String) map.get(name);
                result += (result.equals("")) ? "" : "&";
                result += String.format("%s=%s", name, value);
            }
            return result;
        }
        return null;
    }

    /**
     * 解析字符串返回 名称=值的参数表 (a=1&b=2 => a=1,b=2)
     *
     * @see test.koubei.util.StringUtilTest#testParseStr()
     * @param str
     * @return
     */
    @SuppressWarnings("unchecked")
    public static LinkedHashMap<String, String> toLinkedHashMap(String str) {
        if (str != null && !str.equals("") && str.indexOf("=") > 0) {
            LinkedHashMap result = new LinkedHashMap();

            String name = null;
            String value = null;
            int i = 0;
            while (i < str.length()) {
                char c = str.charAt(i);
                switch (c) {
                    case 61: // =
                        value = "";
                        break;
                    case 38: // &
                        if (name != null && value != null && !name.equals("")) {
                            result.put(name, value);
                        }
                        name = null;
                        value = null;
                        break;
                    default:
                        if (value != null) {
                            value = (value != null) ? (value + c) : "" + c;
                        } else {
                            name = (name != null) ? (name + c) : "" + c;
                        }
                }
                i++;

            }

            if (name != null && value != null && !name.equals("")) {
                result.put(name, value);
            }

            return result;

        }
        return null;
    }

    /**
     * 根据输入的多个解释和下标返回一个值
     *
     * @param captions 例如:"无,爱干净,一般,比较乱"
     * @param index 1
     * @return 一般
     */
    public static String getCaption(String captions, int index) {
        if (index > 0 && captions != null && !captions.equals("")) {
            String[] ss = captions.split(",");
            if (ss != null && ss.length > 0 && index < ss.length) {
                return ss[index];
            }
        }
        return null;
    }

    /**
     * 数字转字符串,如果num<=0 则输出"";
     *
     * @param num
     * @return
     */
    public static String numberToString(Object num) {
        if (num == null) {
            return null;
        } else if (num instanceof Integer && (Integer) num > 0) {
            return Integer.toString((Integer) num);
        } else if (num instanceof Long && (Long) num > 0) {
            return Long.toString((Long) num);
        } else if (num instanceof Float && (Float) num > 0) {
            return Float.toString((Float) num);
        } else if (num instanceof Double && (Double) num > 0) {
            return Double.toString((Double) num);
        } else {
            return "";
        }
    }

    /**
     * 货币转字符串
     *
     * @param money
     * @param style 样式 [default]要格式化成的格式 such as #.00, #.#
     * @return
     */
    public static String moneyToString(Object money, String style) {
        if (money != null && style != null && (money instanceof Double || money instanceof Float)) {
            Double num = (Double) money;

            if (style.equalsIgnoreCase("default")) {
                // 缺省样式 0 不输出 ,如果没有输出小数位则不输出.0
                if (num == 0) {
                    // 不输出0
                    return "";
                } else if ((num * 10 % 10) == 0) {
                    // 没有小数
                    return Integer.toString((int) num.intValue());
                } else {
                    // 有小数
                    return num.toString();
                }

            } else {
                DecimalFormat df = new DecimalFormat(style);
                return df.format(num);
            }
        }
        return null;
    }

    /**
     * 在sou中是否存在finds 如果指定的finds字符串有一个在sou中找到,返回true;
     *
     * @param sou
     * @param find
     * @return
     */
    public static boolean strPos(String sou, String... finds) {
        if (sou != null && finds != null && finds.length > 0) {
            for (int i = 0; i < finds.length; i++) {
                if (sou.indexOf(finds[i]) > -1) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean strPos(String sou, List<String> finds) {
        if (sou != null && finds != null && finds.size() > 0) {
            for (String s : finds) {
                if (sou.indexOf(s) > -1) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean strPos(String sou, String finds) {
        List<String> t = splitToList(",", finds);
        return strPos(sou, t);
    }

    /**
     * 判断两个字符串是否相等 如果都为null则判断为相等,一个为null另一个not null则判断不相等 否则如果s1=s2则相等
     *
     * @param s1
     * @param s2
     * @return
     */
    public static boolean equals(String s1, String s2) {
        if (isEmpty(s1) && isEmpty(s2)) {
            return true;
        } else if (!isEmpty(s1) && !isEmpty(s2)) {
            return s1.equals(s2);
        }
        return false;
    }

    public static int toInt(String s) {
        if (s != null && !"".equals(s.trim())) {
            try {
                return Integer.parseInt(s);
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }

    public static double toDouble(String s) {
        if (s != null && !"".equals(s.trim())) {
            return Double.parseDouble(s);
        }
        return 0;
    }

    /**
     * 把xml 转为object
     *
     * @param xml
     * @return
     */
    public static Object xmlToObject(String xml) {
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes("UTF8"));
            XMLDecoder decoder = new XMLDecoder(new BufferedInputStream(in));
            return decoder.readObject();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static long toLong(String s) {
        try {
            if (s != null && !"".equals(s.trim())) {
                return Long.parseLong(s);
            }
        } catch (Exception exception) {
        }
        return 0L;
    }

    public static String simpleEncrypt(String str) {
        if (str != null && str.length() > 0) {
            // str = str.replaceAll("0","a");
            str = str.replaceAll("1", "b");
            // str = str.replaceAll("2","c");
            str = str.replaceAll("3", "d");
            // str = str.replaceAll("4","e");
            str = str.replaceAll("5", "f");
            str = str.replaceAll("6", "g");
            str = str.replaceAll("7", "h");
            str = str.replaceAll("8", "i");
            str = str.replaceAll("9", "j");
        }
        return str;

    }

    /**
     * 过滤用户输入的URL地址（防治用户广告） 目前只针对以http或www开头的URL地址
     * 本方法调用的正则表达式，不建议用在对性能严格的地方例如:循环及list页面等
     *
     * @author fengliang
     * @param str 需要处理的字符串
     * @return 返回处理后的字符串
     */
    public static String removeURL(String str) {
        if (str != null) {
            str = str.toLowerCase().replaceAll("(http|www|com|cn|org|\\.)+", "");
        }
        return str;
    }

    /**
     * 随即生成指定位数的含数字验证码字符串
     *
     * @author Peltason
     * @date 2007-5-9
     * @param bit 指定生成验证码位数
     * @return String
     */
    public static String numRandom(int bit) {
        if (bit == 0) {
            bit = 6; // 默认6位
        }
        String str = "";
        str = "0123456789";// 初始化种子
        return RandomStringUtils.random(bit, str);// 返回6位的字符串
    }

    /**
     * 随即生成指定位数的含验证码字符串
     *
     * @author Peltason
     *
     * @date 2007-5-9
     * @param bit 指定生成验证码位数
     * @return String
     */
    public static String random(int bit) {
        if (bit == 0) {
            bit = 6; // 默认6位
        }		// 因为o和0,l和1很难区分,所以,去掉大小写的o和l
        String str = "";
        str = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz";// 初始化种子
        return RandomStringUtils.random(bit, str);// 返回6位的字符串
    }

    /**
     * Wap页面的非法字符检查
     *
     * @author hugh115
     * @date 2007-06-29
     * @param str
     * @return
     */
    public static String replaceWapStr(String str) {
        if (str != null) {
            str = str.replaceAll("<span class=\"keyword\">", "");
            str = str.replaceAll("</span>", "");
            str = str.replaceAll("<strong class=\"keyword\">", "");
            str = str.replaceAll("<strong>", "");
            str = str.replaceAll("</strong>", "");

            str = str.replace('$', '＄');

            str = str.replaceAll("&amp;", "＆");
            str = str.replace('&', '＆');

            str = str.replace('<', '＜');

            str = str.replace('>', '＞');

        }
        return str;
    }

    /**
     * 字符串转float 如果异常返回0.00
     *
     * @param s 输入的字符串
     * @return 转换后的float
     */
    public static Float toFloat(String s) {
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return new Float(0);
        }
    }

    /**
     * 页面中去除字符串中的空格、回车、换行符、制表符
     *
     * @author shazao
     * @date 2007-08-17
     * @param str
     * @return
     */
    public static String replaceBlank(String str) {
        if (str != null) {
            Pattern p = Pattern.compile("\\s*|\t|\r|\n");
            Matcher m = p.matcher(str);
            str = m.replaceAll("");
        }
        return str;
    }

    /**
     * 全角生成半角
     *
     * @author bailong
     * @date 2007-08-29
     * @param str
     * @return
     */
    public static String Q2B(String QJstr) {
        String outStr = "";
        String Tstr = "";
        byte[] b = null;
        for (int i = 0; i < QJstr.length(); i++) {
            try {
                Tstr = QJstr.substring(i, i + 1);
                b = Tstr.getBytes("unicode");
            } catch (java.io.UnsupportedEncodingException e) {
                if (log.isErrorEnabled()) {
                    log.error(e);
                }
            }
            if (b[3] == -1) {
                b[2] = (byte) (b[2] + 32);
                b[3] = 0;
                try {
                    outStr = outStr + new String(b, "unicode");
                } catch (java.io.UnsupportedEncodingException ex) {
                    if (log.isErrorEnabled()) {
                        log.error(ex);
                    }
                }
            } else {
                outStr = outStr + Tstr;
            }
        }
        return outStr;
    }

    /**
     *
     * 转换编码
     *
     * @param s 源字符串
     * @param fencode 源编码格式
     * @param bencode 目标编码格式
     * @return 目标编码
     */
    public static String changCoding(String s, String fencode, String bencode) {
        String str;
        try {
            if (isNotEmpty(s)) {
                str = new String(s.getBytes(fencode), bencode);
            } else {
                str = "";
            }
            return str;
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    /**
     * @param str
     * @return
     * ************************************************************************
     */
    public static String removeHTMLLableExe(String str) {
        str = stringReplace(str, ">\\s*<", "><");
        str = stringReplace(str, "&nbsp;", " ");// 替换空格
        str = stringReplace(str, "<br ?/?>", "\n");// 去<br><br />
        str = stringReplace(str, "<([^<>]+)>", "");// 去掉<>内的字符
        str = stringReplace(str, "\\s\\s\\s*", " ");// 将多个空白变成一个空格
        str = stringReplace(str, "^\\s*", "");// 去掉头的空白
        str = stringReplace(str, "\\s*$", "");// 去掉尾的空白
        str = stringReplace(str, " +", " ");
        return str;
    }

    /**
     * 除去html标签
     *
     * @param str 源字符串
     * @return 目标字符串
     */
    public static String removeHTMLLable(String str) {
        str = stringReplace(str, "\\s", "");// 去掉页面上看不到的字符
        str = stringReplace(str, "<br ?/?>", "\n");// 去<br><br />
        str = stringReplace(str, "<([^<>]+)>", "");// 去掉<>内的字符
        str = stringReplace(str, "&nbsp;", " ");// 替换空格
        str = stringReplace(str, "&(\\S)(\\S?)(\\S?)(\\S?);", "");// 去<br><br />
        return str;
    }

    /**
     * 去掉HTML标签之外的字符串
     *
     * @param str 源字符串
     * @return 目标字符串
     */
    public static String removeOutHTMLLable(String str) {
        str = stringReplace(str, ">([^<>]+)<", "><");
        str = stringReplace(str, "^([^<>]+)<", "<");
        str = stringReplace(str, ">([^<>]+)$", ">");
        return str;
    }

    /**
     *
     * 字符串替换
     *
     * @param str 源字符串
     * @param sr 正则表达式样式
     * @param sd 替换文本
     * @return 结果串
     */
    public static String stringReplace(String str, String sr, String sd) {
        String regEx = sr;
        Pattern p = Pattern.compile(regEx, Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(str);
        str = m.replaceAll(sd);
        return str;
    }

    /**
     *
     * 将html的省略写法替换成非省略写法
     *
     * @param str html字符串
     * @param pt 标签如table
     * @return 结果串
     */
    public static String fomateToFullForm(String str, String pt) {
        String regEx = "<" + pt + "\\s+([\\S&&[^<>]]*)/>";
        Pattern p = Pattern.compile(regEx, Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(str);
        String[] sa = null;
        String sf = "";
        String sf2 = "";
        String sf3 = "";
        for (; m.find();) {
            sa = p.split(str);
            if (sa == null) {
                break;
            }
            sf = str.substring(sa[0].length(), str.indexOf("/>", sa[0].length()));
            sf2 = sf + "></" + pt + ">";
            sf3 = str.substring(sa[0].length() + sf.length() + 2);
            str = sa[0] + sf2 + sf3;
            sa = null;
        }
        return str;
    }

    /**
     *
     * 得到字符串的子串位置序列
     *
     * @param str 字符串
     * @param sub 子串
     * @param b true子串前端,false子串后端
     * @return 字符串的子串位置序列
     */
    public static int[] getSubStringPos(String str, String sub, boolean b) {
        // int[] i = new int[(new Integer((str.length()-stringReplace( str , sub
        // , "" ).length())/sub.length())).intValue()] ;
        String[] sp = null;
        int l = sub.length();
        sp = splitString(str, sub);
        if (sp == null) {
            return null;
        }
        int[] ip = new int[sp.length - 1];
        for (int i = 0; i < sp.length - 1; i++) {
            ip[i] = sp[i].length() + l;
            if (i != 0) {
                ip[i] += ip[i - 1];
            }
        }
        if (b) {
            for (int j = 0; j < ip.length; j++) {
                ip[j] = ip[j] - l;
            }
        }
        return ip;
    }

    /**
     *
     * 根据正则表达式分割字符串
     *
     * @param str 源字符串
     * @param ms 正则表达式
     * @return 目标字符串组
     */
    public static String[] splitString(String str, String ms) {
        String regEx = ms;
        Pattern p = Pattern.compile(regEx, Pattern.CASE_INSENSITIVE);
        String[] sp = p.split(str);
        return sp;
    }

    /**
     * 根据正则表达式提取字符串,相同的字符串只返回一个
     *
     * @param str源字符串
     * @param pattern 正则表达式
     * @return 目标字符串数据组
     * ************************************************************************
     */
    // ★传入一个字符串，把符合pattern格式的字符串放入字符串数组
    // java.util.regex是一个用正则表达式所订制的模式来对字符串进行匹配工作的类库包
    public static String[] getStringArrayByPattern(String str, String pattern) {
        Pattern p = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        Matcher matcher = p.matcher(str);
        // 范型
        Set<String> result = new HashSet<String>();// 目的是：相同的字符串只返回一个。。。 不重复元素
        // boolean find() 尝试在目标字符串里查找下一个匹配子串。
        while (matcher.find()) {
            for (int i = 0; i < matcher.groupCount(); i++) { // int groupCount()
                // 返回当前查找所获得的匹配组的数量。
                // System.out.println(matcher.group(i));
                result.add(matcher.group(i));

            }
        }
        String[] resultStr = null;
        if (result.size() > 0) {
            resultStr = new String[result.size()];
            return result.toArray(resultStr);// 将Set result转化为String[] resultStr
        }
        return resultStr;

    }

    /**
     * 得到第一个b,e之间的字符串,并返回e后的子串
     *
     * @param s 源字符串
     * @param b 标志开始
     * @param e 标志结束
     * @return b,e之间的字符串
     */

    /*
     * String aaa="abcdefghijklmn"; String[] bbb=StringProcessor.midString(aaa, "b","l"); System.out.println("bbb[0]:"+bbb[0]);//cdefghijk System.out.println("bbb[1]:"+bbb[1]);//lmn ★这个方法是得到第二个参数和第三个参数之间的字符串,赋给元素0;然后把元素0代表的字符串之后的,赋给元素1
     */

    /*
     * String aaa="abcdefgllhijklmn5465"; String[] bbb=StringProcessor.midString(aaa, "b","l"); //ab cdefg llhijklmn5465 // 元素0 元素1
     */
    public static String[] midString(String s, String b, String e) {
        int i = s.indexOf(b) + b.length();
        int j = s.indexOf(e, i);
        String[] sa = new String[2];
        if (i < b.length() || j < i + 1 || i > j) {
            sa[1] = s;
            sa[0] = null;
            return sa;
        } else {
            sa[0] = s.substring(i, j);
            sa[1] = s.substring(j);
            return sa;
        }
    }

    /**
     * 带有前一次替代序列的正则表达式替代
     *
     * @param s
     * @param pf
     * @param pb
     * @param start
     * @return
     */
    public static String stringReplace(String s, String pf, String pb, int start) {
        Pattern pattern_hand = Pattern.compile(pf);
        Matcher matcher_hand = pattern_hand.matcher(s);
        int gc = matcher_hand.groupCount();
        int pos = start;
        String sf1 = "";
        String sf2 = "";
        String sf3 = "";
        int if1 = 0;
        String strr = "";
        while (matcher_hand.find(pos)) {
            sf1 = matcher_hand.group();
            if1 = s.indexOf(sf1, pos);
            if (if1 >= pos) {
                strr += s.substring(pos, if1);
                pos = if1 + sf1.length();
                sf2 = pb;
                for (int i = 1; i <= gc; i++) {
                    sf3 = "\\" + i;
                    sf2 = replaceAll(sf2, sf3, matcher_hand.group(i));
                }
                strr += sf2;
            } else {
                return s;
            }
        }
        strr = s.substring(0, start) + strr;
        return strr;
    }

    /**
     * 存文本替换
     *
     * @param s 源字符串
     * @param sf 子字符串
     * @param sb 替换字符串
     * @return 替换后的字符串
     */
    public static String replaceAll(String s, String sf, String sb) {
        int i = 0, j = 0;
        int l = sf.length();
        boolean b = true;
        boolean o = true;
        String str = "";
        do {
            j = i;
            i = s.indexOf(sf, j);
            if (i > j) {
                str += s.substring(j, i);
                str += sb;
                i += l;
                o = false;
            } else {
                str += s.substring(j);
                b = false;
            }
        } while (b);
        if (o) {
            str = s;
        }
        return str;
    }

    /**
     * 判断是否与给定字符串样式匹配
     *
     * @param str 字符串
     * @param pattern 正则表达式样式
     * @return 是否匹配是true,否false
     */
    public static boolean isMatch(String str, String pattern) {
        Pattern pattern_hand = Pattern.compile(pattern);
        Matcher matcher_hand = pattern_hand.matcher(str);
        boolean b = matcher_hand.matches();
        return b;
    }

    /**
     * 截取字符串
     *
     * @param s 源字符串
     * @param jmp 跳过jmp
     * @param sb 取在sb
     * @param se 于se
     * @return 之间的字符串
     */
    public static String subStringExe(String s, String jmp, String sb, String se) {
        if (isEmpty(s)) {
            return "";
        }
        int i = s.indexOf(jmp);
        if (i >= 0 && i < s.length()) {
            s = s.substring(i + 1);
        }
        i = s.indexOf(sb);
        if (i >= 0 && i < s.length()) {
            s = s.substring(i + 1);
        }
        if (se == "") {
            return s;
        } else {
            i = s.indexOf(se);
            if (i >= 0 && i < s.length()) {
                s = s.substring(i + 1);
            }
            return s;
        }
    }

    /**
     * *************************************************************************
     * 用要通过URL传输的内容进行编码
     *
     * @param src
     * @param 源字符串
     * @return 经过编码的内容
     * ************************************************************************
     */
    public static String URLEncode(String src) {
        String return_value = "";
        try {
            if (src != null) {
                return_value = URLEncoder.encode(src, "GBK");

            }
        } catch (UnsupportedEncodingException e) {
            log.error(e.getMessage(), e);
            return_value = src;
        }

        return return_value;
    }

    /**
     * *************************************************************************
     *
     * @author 李锋 2007.4.18
     * @param str
     * @return 经过解码的内容
     * ************************************************************************
     */
    public static String getGBK(String str) {

        return transfer(str);
    }

    public static String transfer(String str) {
        Pattern p = Pattern.compile("&#\\d+;");
        Matcher m = p.matcher(str);
        while (m.find()) {
            String old = m.group();
            str = str.replaceAll(old, getChar(old));
        }
        return str;
    }

    public static String getChar(String str) {
        String dest = str.substring(2, str.length() - 1);
        char ch = (char) Integer.parseInt(dest);
        return "" + ch;
    }

    /**
     * yahoo首页中切割字符串.
     *
     * @author yxg
     * @param subject
     * @param size
     * @date 2007-09-17
     * @return
     */
    public static String subYhooString(String subject, int size) {
        subject = subject.substring(1, size);
        return subject;
    }

    public static String subYhooStringDot(String subject, int size) {
        subject = subject.substring(1, size) + "...";
        return subject;
    }

    /**
     * 泛型方法(通用)，把list转换成以“,”相隔的字符串 调用时注意类型初始化（申明类型） 如：List<Integer> intList =
     * new ArrayList<Integer>(); 调用方法：listTtoString(intList);
     * 效率：list中4条信息，1000000次调用时间为850ms左右
     *
     * @author fengliang
     * @serialData 2008-01-09
     * @param <T> 泛型
     * @param list list列表
     * @return 以“,”相隔的字符串
     */
    public static <T> String listTtoString(List<T> list) {
        if (list == null || list.size() < 1) {
            return "";
        }
        Iterator<T> i = list.iterator();
        if (!i.hasNext()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (;;) {
            T e = i.next();
            sb.append(e);
            if (!i.hasNext()) {
                return sb.toString();
            }
            sb.append(",");
        }
    }

    /**
     * 把整形数组转换成以“,”相隔的字符串
     *
     * @author fengliang
     * @serialData 2008-01-08
     * @param a 数组a
     * @return 以“,”相隔的字符串
     */
    public static String intArraytoString(int[] a) {
        if (a == null) {
            return "";
        }
        int iMax = a.length - 1;
        if (iMax == -1) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0;; i++) {
            b.append(a[i]);
            if (i == iMax) {
                return b.toString();
            }
            b.append(",");
        }
    }

    /**
     * 判断文字内容重复
     *
     * @author 沙枣
     * @param content
     * @return
     * @Date 2008-04-17
     */
    public static boolean isContentRepeat(String content) {
        int similarNum = 0;
        int forNum = 0;
        int subNum = 0;
        int thousandNum = 0;
        String startStr = "";
        String nextStr = "";
        boolean result = false;
        float endNum = (float) 0.0;
        if (content != null && content.length() > 0) {
            if (content.length() % 1000 > 0) {
                thousandNum = (int) Math.floor(content.length() / 1000) + 1;
            } else {
                thousandNum = (int) Math.floor(content.length() / 1000);
            }
            if (thousandNum < 3) {
                subNum = 100 * thousandNum;
            } else if (thousandNum < 6) {
                subNum = 200 * thousandNum;
            } else if (thousandNum < 9) {
                subNum = 300 * thousandNum;
            } else {
                subNum = 3000;
            }
            for (int j = 1; j < subNum; j++) {
                if (content.length() % j > 0) {
                    forNum = (int) Math.floor(content.length() / j) + 1;
                } else {
                    forNum = (int) Math.floor(content.length() / j);
                }
                if (result || j >= content.length()) {
                    break;
                } else {
                    for (int m = 0; m < forNum; m++) {
                        if (m * j > content.length() || (m + 1) * j > content.length() || (m + 2) * j > content.length()) {
                            break;
                        }
                        startStr = content.substring(m * j, (m + 1) * j);
                        nextStr = content.substring((m + 1) * j, (m + 2) * j);
                        if (startStr.equals(nextStr)) {
                            similarNum = similarNum + 1;
                            endNum = (float) similarNum / forNum;
                            if (endNum > 0.4) {
                                result = true;
                                break;
                            }
                        } else {
                            similarNum = 0;
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * 判断是否是空字符串 null和"" null返回defaultStr,否则返回字符串
     *
     * @param s
     * @param defaultStr
     * @return
     */
    public static String isEmpty(String s, String defaultStr) {
        if (s != null && !s.equals("")) {
            return s;
        }
        return defaultStr;
    }

    /**
     * 判断对象是否为空
     *
     * @param str
     * @return
     */
    public static boolean isNotEmpty(Object str) {
        boolean flag = true;
        if (str != null && !str.equals("")) {
            if (str.toString().length() > 0) {
                flag = true;
            }
        } else {
            flag = false;
        }
        return flag;
    }

    /**
     * 全角字符变半角字符
     *
     * @author shazao
     * @date 2008-04-03
     * @param str
     * @return
     */
    public static String full2Half(String str) {
        if (str == null || "".equals(str)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);

            if (c >= 65281 && c < 65373) {
                sb.append((char) (c - 65248));
            } else {
                sb.append(str.charAt(i));
            }
        }

        return sb.toString();

    }

    /**
     * 全角括号转为半角
     *
     * @author shazao
     * @date 2007-11-29
     * @param str
     * @return
     */
    public static String replaceBracketStr(String str) {
        if (str != null && str.length() > 0) {
            str = str.replaceAll("（", "(");
            str = str.replaceAll("）", ")");
        }
        return str;
    }

    /**
     * 解析字符串返回map键值对(例：a=1&b=2 => a=1,b=2)
     *
     * @param query 源参数字符串
     * @param split1 键值对之间的分隔符（例：&）
     * @param split2 key与value之间的分隔符（例：=）
     * @param dupLink 重复参数名的参数值之间的连接符，连接后的字符串作为该参数的参数值，可为null
     * null：不允许重复参数名出现，则靠后的参数值会覆盖掉靠前的参数值。
     * @return map
     * @author sky
     */
    @SuppressWarnings("unchecked")
    public static Map<String, String> parseQuery(String query, char split1, char split2, String dupLink) {
        if (!isEmpty(query) && query.indexOf(split2) > 0) {
            Map<String, String> result = new HashMap();

            String name = null;
            String value = null;
            String tempValue = "";
            int len = query.length();
            for (int i = 0; i < len; i++) {
                char c = query.charAt(i);
                if (c == split2) {
                    value = "";
                } else if (c == split1) {
                    if (!isEmpty(name) && value != null) {
                        if (dupLink != null) {
                            tempValue = result.get(name);
                            if (tempValue != null) {
                                value += dupLink + tempValue;
                            }
                        }
                        result.put(name, value);
                    }
                    name = null;
                    value = null;
                } else if (value != null) {
                    value += c;
                } else {
                    name = (name != null) ? (name + c) : "" + c;
                }
            }

            if (!isEmpty(name) && value != null) {
                if (dupLink != null) {
                    tempValue = result.get(name);
                    if (tempValue != null) {
                        value += dupLink + tempValue;
                    }
                }
                result.put(name, value);
            }

            return result;
        }
        return null;
    }

    /**
     * 将list 用传入的分隔符组装为String
     *
     * @param list
     * @param slipStr
     * @return String
     */
    @SuppressWarnings("unchecked")
    public static String listToStringSlipStr(List list, String slipStr) {
        StringBuilder returnStr = new StringBuilder();
        if (list != null && list.size() > 0) {
            for (Object o : list) {
                returnStr.append(o).append(slipStr);
            }
        }
        if (returnStr.toString().length() > 0) {
            return returnStr.toString().substring(0, returnStr.toString().lastIndexOf(slipStr));
        } else {
            return "";
        }
    }

    /**
     * 获取从start开始用*替换len个长度后的字符串
     *
     * @param str 要替换的字符串
     * @param start 开始位置
     * @param len 长度
     * @return 替换后的字符串
     */
    public static String getMaskStr(String str, int start, int len) {
        if (isEmpty(str)) {
            return str;
        }
        if (str.length() < start) {
            return str;
        }

        // 获取*之前的字符串
        String ret = str.substring(0, start);

        // 获取最多能打的*个数
        int strLen = str.length();
        if (strLen < start + len) {
            len = strLen - start;
        }

        // 替换成*
        for (int i = 0; i < len; i++) {
            ret += "*";
        }

        // 加上*之后的字符串
        if (strLen > start + len) {
            ret += str.substring(start + len);
        }

        return ret;
    }

    /**
     * 根据传入的分割符号,把传入的字符串分割为List字符串
     *
     * @param slipStr 分隔的字符串
     * @param src 字符串
     * @return 列表
     */
    public static List<String> stringToStringListBySlipStr(String slipStr, String src) {

        if (src == null) {
            return null;
        }
        List<String> list = new ArrayList<String>();
        String[] result = src.split(slipStr);
        list.addAll(Arrays.asList(result));
        return list;
    }

    /**
     * 截取字符串
     *
     * @param str 原始字符串
     * @param len 要截取的长度
     * @param tail 结束加上的后缀
     * @return 截取后的字符串
     */
    public static String getHtmlSubString(String str, int len, String tail) {
        if (str == null || str.length() <= len) {
            return str;
        }
        int length = str.length();
        char c = ' ';
        String tag = null;
        String name = null;
        int size = 0;
        String result = "";
        boolean isTag = false;
        List<String> tags = new ArrayList<String>();
        int i = 0;
        for (int end = 0, spanEnd = 0; i < length && len > 0; i++) {
            c = str.charAt(i);
            if (c == '<') {
                end = str.indexOf('>', i);
            }

            if (end > 0) {
                // 截取标签
                tag = str.substring(i, end + 1);
                int n = tag.length();
                if (tag.endsWith("/>")) {
                    isTag = true;
                } else if (tag.startsWith("</")) { // 结束符
                    name = tag.substring(2, end - i);
                    size = tags.size() - 1;
                    // 堆栈取出html开始标签
                    if (size >= 0 && name.equals(tags.get(size))) {
                        isTag = true;
                        tags.remove(size);
                    }
                } else { // 开始符
                    spanEnd = tag.indexOf(' ', 0);
                    spanEnd = spanEnd > 0 ? spanEnd : n;
                    name = tag.substring(1, spanEnd);
                    if (name.trim().length() > 0) {
                        // 如果有结束符则为html标签
                        spanEnd = str.indexOf("</" + name + ">", end);
                        if (spanEnd > 0) {
                            isTag = true;
                            tags.add(name);
                        }
                    }
                }
                // 非html标签字符
                if (!isTag) {
                    if (n >= len) {
                        result += tag.substring(0, len);
                        break;
                    } else {
                        len -= n;
                    }
                }

                result += tag;
                isTag = false;
                i = end;
                end = 0;
            } else { // 非html标签字符
                len--;
                result += c;
            }
        }
        // 添加未结束的html标签
        for (String endTag : tags) {
            result += "</" + endTag + ">";
        }
        if (i < length) {
            result += tail;
        }
        return result;
    }

    public static String getProperty(String property) {
        if (property.contains("_")) {
            return property.replaceAll("_", "\\.");
        }
        return property;
    }

    /**
     * 解析前台encodeURIComponent编码后的参数
     *
     * @param property
     * @return
     */
    public static String getEncodePra(String property) {
        String trem = "";
        if (isNotEmpty(property)) {
            try {
                trem = URLDecoder.decode(property, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
        return trem;
    }

    // 判断一个字符串是否都为数字
    public boolean isDigit(String strNum) {
        Pattern pattern = Pattern.compile("[0-9]{1,}");
        Matcher matcher = pattern.matcher((CharSequence) strNum);
        return matcher.matches();
    }

    // 截取数字
    public String getNumbers(String content) {
        Pattern pattern = Pattern.compile("\\d+");
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            return matcher.group(0);
        }
        return "";
    }

    // 截取非数字
    public String splitNotNumber(String content) {
        Pattern pattern = Pattern.compile("\\D+");
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            return matcher.group(0);
        }
        return "";
    }

    /**
     * 判断某个字符串是否存在于数组中
     *
     * @param stringArray 原数组
     * @param source 查找的字符串
     * @return 是否找到
     */
    public static boolean contains(String[] stringArray, String source) {
        // 转换为list
        List<String> tempList = Arrays.asList(stringArray);

        // 利用list的包含方法,进行判断
        return tempList.contains(source);
    }

   

    /**
     * 加百分号
     *
     * @param content
     * @return
     */
    public static String bothPercent(String content) {

        return "%" + content + "%";
    }

    /**
     * 加左百分号
     *
     * @param content
     * @return
     */
    public static String leftPercent(String content) {

        return "%" + content;
    }

    /**
     * 加右百分号
     *
     * @param content
     * @return
     */
    public static String rightPercent(String content) {

        return content + "%";
    }

    public static String convertToJsonString(String src) {
        return src.replaceAll(INVALID_JSON_CHARS, "").replaceAll("\"", "'");
    }

    public static int monthIndex(String str) {
        int index = 1;
        for (String a : monthArray) {
            if (a.toUpperCase().startsWith(str.toUpperCase())) {
                break;
            }
            index++;
        }
        return index;
    }

    public static int weekDayIndex(String str) {
        int index = 1;
        for (String a : weekDayArray) {
            if (a.toUpperCase().startsWith(str.toUpperCase())) {
                break;
            }
            index++;
        }
        return index;
    }

    /**
     * 添加文本行到文件末尾，默认编码utf-8
     *
     * @param file
     * @param msg
     * @throws java.lang.Exception
     */
    public static synchronized void appendLineToFile(File file, String msg) throws Exception {
        //FileWriter(String fileName, boolean append)
        try {
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            if (!file.exists()) {
                file.createNewFile();
            }
        } catch (Exception e) {
            throw e;
        }
        try (FileOutputStream fis = new FileOutputStream(file, true);
                BufferedWriter output = new BufferedWriter(new OutputStreamWriter(fis, "UTF-8"));){
            output.write(msg);
            output.write(NEW_LINE);
        } catch (Exception e) {
            throw e;
        }
    }

    /**
     * 添加字符串到文件末尾
     *
     * @param file
     * @param encoding
     * @param msg
     */
    public static synchronized void appendStringToFile(File file, String encoding, String msg) throws Exception {
        //FileWriter(String fileName, boolean append)
        BufferedWriter output = null;
        try {
            if (!file.exists()) {
                file.createNewFile();
            }
            output = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file, true), encoding));
            output.write(msg);
        } catch (Exception e) {
            throw e;
        } finally {
            if (output != null) {
                try {
                    output.flush();
                    output.close();
                } catch (IOException ex) {
                }
            }
        }
    }

    public static String getFileExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index == -1) {
            return null;
        }
        return fileName.substring(index + 1);
    }
    
    public static String getFileExtensionWithDot(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index == -1) {
            return null;
        }
        return fileName.substring(index);
    }

    public static String getResultSetAsString(ResultSet rs) {
        String newline = System.getProperty("line.separator");
        StringBuilder sb = new StringBuilder();
        int cols = 0;
        try {
            ResultSetMetaData meta = rs.getMetaData();
            cols = meta.getColumnCount();
            //System.out.println("cols:"+cols);
            List<Integer> width = new ArrayList<Integer>();
            for (int i = 1; i <= cols; i++) {
                int len = meta.getColumnDisplaySize(i);
				//int len = meta.getColumnDisplaySize(i)/2;//处理中文
                //System.out.println("cols:"+i+" "+"len:"+len);
                width.add(len);
            }
            //表头:
            StringBuilder sb1 = new StringBuilder();
            StringBuilder sb2 = new StringBuilder();
            for (int i = 0; i < cols; i++) {
                sb2.setLength(0);
				//meta.getColumnLabel(i);
                //sb2.append(meta.getColumnName(i+1));
                sb2.append("[" + meta.getColumnLabel(i + 1) + "]");

                int w = width.get(i);
                if (w <= sb2.length()) {
                    w = sb2.length() + 1;
                    width.set(i, w);
                }

                for (int j = sb2.toString().getBytes().length; j < w; j++) {
                    sb2.append(' ');
                }
                sb1.append(sb2);
            }
            sb.append(sb1);
            sb.append(newline);
            //分割线：
            sb1.setLength(0);
            for (int i = 0; i < cols; i++) {
                sb2.setLength(0);
                int w = width.get(i);
                if (w <= sb2.length()) {
                    w = sb2.length() + 1;
                    width.set(i, w);
                }
                w--;

                for (int j = 0; j < w; j++) {
                    sb2.append("-");
                }
                sb2.append('|');
                sb1.append(sb2);
            }
            sb.append(sb1);
            sb.append(newline);
            //System.out.println(sb.toString());
            while (rs.next()) {
                sb1.setLength(0);
                //StringBuilder sb2 = new StringBuilder();
                for (int i = 0; i < cols; i++) {
                    sb2.setLength(0);
                    int w = width.get(i);
                    sb2.append(rs.getString(i + 1));
                    //true ? w:(w=sb2.length()+1);
                    if (w <= sb2.length()) {
                        w = sb2.length() + 1;
                        width.set(i, w);
                    }
                    //System.out.println(sb2.length());
                    int x = sb2.toString().getBytes().length;

                    char tmp = ' ';
                    int z = x - sb2.length();
                    //中英混排处理：
                    if (sb2.length() > 0 && sb2.substring(sb2.length() - 1).getBytes().length != 1) {
                        if (z != 0) {
                            if (x % 2 != 0) {
                                x++;
                                z++;
                            }
                            tmp = '　';
                            x /= 2;
                            w /= 2;
                            if (w % 2 != 0) {
                                sb2.append(' ');
                            }
                            for (int k = 0; k < z; k++) {
                                sb2.append(' ');
                            }
                        }
                    }
                    for (int j = x; j < w; j++) {
                        sb2.append(tmp);

                    }
                    sb1.append(sb2);
                }

                sb.append(sb1);
                sb.append(newline);
            }

            return sb.toString();

        } catch (SQLException e) {
            log.error(e, e);
            return null;
        }
    }

    public static String guessContentTypeFromFileName(String fileName) {
        String ext;
        if (fileName.contains(".")) {
            ext = fileName.substring(fileName.lastIndexOf('.'));
        } else {
            ext = ".bin";
        }
        return URLConnection.guessContentTypeFromName(ext);
    }
    
    /** 
     * 
     * @param str 
     *         需要过滤的字符串 
     * @return 
     * @Description:过滤数字以外的字符 
     */  
    public static String filterUnNumber(String str) {  
        Pattern p = Pattern.compile("[^0-9]");
        Matcher m = p.matcher(str.replace("o", "0").replace("O", "0"));  
        //替换与模式匹配的所有字符（即非数字的字符将被""替换）  
        return m.replaceAll("").trim();  
  
    }  
    
    public static int[] stringToIntArray(String string) {
        int[] strToInt = new int[string.length()];
        for (int i = 0; i < string.length(); i++) {
            strToInt[i] = string.charAt(i) - 48;
        }
        return strToInt;
    }
    
    /**
     * 从给定map获得top keywords
     *
     * @param keywordsMap
     * @param limit top限制数
     * @return
     */
    public static List<String> getMaxCountKeyWordsWithLimit(Map<String, Integer> keywordsMap, int limit,int hint) {
        List<String> tagsList = new ArrayList<String>();
        //这里将map.entrySet()转换成list
        List<Map.Entry<String, Integer>> list = new ArrayList<Map.Entry<String, Integer>>(keywordsMap.entrySet());
        //然后通过比较器来实现排序
        Collections.sort(list, new Comparator<Map.Entry<String, Integer>>() {
            //降序排序
            public int compare(Map.Entry<String, Integer> o1,
                    Map.Entry<String, Integer> o2) {
                return o2.getValue().compareTo(o1.getValue());
            }

        });
        int count = 0;
        for (Map.Entry<String, Integer> mapping : list) {
            if (mapping.getValue() >= hint) {
                count++;
                tagsList.add(mapping.getKey());
                if (count == limit) {
                    break;
                }
            }
        }
        return tagsList;
    }

    
    public static String getHostFromUrl(String uri) {
        int index = uri.indexOf("//");
        int indexEnd = uri.indexOf("/", index + 2);
        if (index >= 0) {
            if (indexEnd > 0) {
                return uri.substring(index + 2, indexEnd);
            } else {
                return uri.substring(index + 2);
            }
        }
        return null;
    }
    public static String getSchemaAndHostFromUrl(String uri) {
        int index = uri.indexOf("//");
        int indexEnd = uri.indexOf("/", index + 2);
        if (index >= 0) {
            if (indexEnd > 0) {
                return uri.substring(0, indexEnd);
            } else {
                return uri;
            }
        }
        return null;
    }
    public static String getCharsetFromContentType(String contentType) {
        int index = contentType.indexOf("charset");
        if (index != -1) {
            return contentType.substring(contentType.indexOf("=", index + 1) + 1).trim().toLowerCase();
        }
        return "utf-8";
    }
    
    public static List<Long> toList(String ids){
        String[] idsArray = ids.split(",");
        List<Long> list = new ArrayList();
        for (String id : idsArray) {
           list.add(Long.parseLong(id));
        }
        return list;
    }
    

    public static void main(String[] args) {
        System.out.println(guessContentTypeFromFileName(". mp4"));
        //System.out.println(sun.net.www.MimeTable.getDefaultTable().getAsProperties());
        //System.out.println(stringToIntArray("1987")[3]+stringToIntArray("1987")[0]);
    }
}
