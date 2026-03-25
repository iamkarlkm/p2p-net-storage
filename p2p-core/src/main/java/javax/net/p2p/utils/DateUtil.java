package javax.net.p2p.utils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 *
 * @author karl
 */
public class DateUtil {
	private static final Logger log = LoggerFactory.getLogger(DateUtil.class);

   private static final String MIN_DATETIME_STRING = "00000101000000000";
    private static final String MAX_DATETIME_STRING = "99991231235959999";
    public static final String DATETIME_FORMAT_FUZZY = "yyyyMMddHHmmssSSS";
    public static final String DATETIME_FORMAT_MS = "yyyy-MM-dd HH:mm:ss,SSS";
	public static final String DATETIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
    public static final String DATETIME_FORMAT_MM = "yyyy-MM-dd HH:mm";
    public static final String DATE_FORMAT = "yyyy-MM-dd";
    public static final String YYYYMMDD = "yyyyMMdd";
    public static final String DATE_FORMAT_US = "MM/dd/yyyy";
    public static final String DATETIME_FORMAT_US = "MM/dd/yyyy HH:mm:ss";

    private final static SimpleDateFormat[] englishDateFormats;

    static {
        englishDateFormats = new SimpleDateFormat[10];
        englishDateFormats[0] = new SimpleDateFormat("yyyy/MMM/dd", Locale.US);
        englishDateFormats[1] = new SimpleDateFormat("yyyy-MMM-dd", Locale.US);
        englishDateFormats[2] = new SimpleDateFormat("MMM/dd/yyyy", Locale.US);
        englishDateFormats[3] = new SimpleDateFormat("MMM-dd-yyyy", Locale.US);
        englishDateFormats[4] = new SimpleDateFormat("yyyy/MMM/dd HH:mm:ss a", Locale.US);
        englishDateFormats[5] = new SimpleDateFormat("MMM-dd-yyyy HH:mm:ss", Locale.US);
        englishDateFormats[6] = new SimpleDateFormat("MMM,dd,yyyy", Locale.US);
        englishDateFormats[7] = new SimpleDateFormat("EEE, dd MMM, yyyy", Locale.US);
        englishDateFormats[8] = new SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US);
        englishDateFormats[9] = new SimpleDateFormat("yyyy/MMM/dd HH:mm", Locale.US);
    }

    public static Date parse(String text) {
        Date date = null;
        try {
            if (text.length() == 19 && text.indexOf(":") > 0) {
                if (text.contains("-")) {
                    try {
                         date = new SimpleDateFormat(DATETIME_FORMAT).parse(text);
                    } catch (Exception e) {
                    }
                } else {
                    try {
						date = new SimpleDateFormat(DATETIME_FORMAT_US).parse(text);
                       
                    } catch (Exception e) {
                    }
                }
            } else if (text.length() == 10 && !text.contains(":")) {
                if (text.contains("-")) {
                    try {
						 date = new SimpleDateFormat(DATE_FORMAT).parse(text);
                        
                    } catch (Exception e) {
                    }
                } else {
                    try {
                       date = new SimpleDateFormat(DATE_FORMAT_US).parse(text);
                    } catch (Exception e) {
                    }
                }
            } else if (text.length() == 21 && text.indexOf(":") > 0) {
                try {
                    text = text.replace(".0", "");
                    date = new SimpleDateFormat(DATETIME_FORMAT).parse(text);
                } catch (Exception e) {
                }

            } else if (text.length() == 23 && text.indexOf(":") > 0) {
                try {
                    date = new SimpleDateFormat(DATETIME_FORMAT_MS).parse(text);
                } catch (Exception e) {
                }

            } else if (text.length() == 16 && text.indexOf(":") > 0) {
                try {
                    date = new SimpleDateFormat(DATETIME_FORMAT_MM).parse(text);
                } catch (Exception e) {
                }

            } else if (text.length() == 10 && text.indexOf("-") > 0) {
                try {
                    date = new SimpleDateFormat(DATE_FORMAT).parse(text);
                } catch (Exception e) {
                }

            } else {
                try {
                    Long times = Long.parseLong(text);
                    date = new Date(times);
                } catch (Exception e) {
                }
            }
            if (date == null) {
                date = parseEnglishDate(text);
            }
            if (date == null) {
                try {
                    date = new Date(text);
                } catch (Exception ex) {
                    throw ex;
                }
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not parse to date from text: " + text, ex);
        }
        return date;
    }
	
	public static String format(Date date) {
		return new SimpleDateFormat(DATETIME_FORMAT).format(date);
	}

    public static Date parseEnglishDate(String text) {
        Date date = null;
        for (SimpleDateFormat sdf : englishDateFormats) {
            if (sdf.toPattern().length() == text.length()) {
                try {
                    date = sdf.parse(text);
                    return date;
                } catch (Exception ex) {
                }
            }
        }
        return date;
    }

    /**
     * 计算日期之间相差的天数
     *
     * @param startDate
     * @param endDate
     * @return
     */
    public static int getBetweenDays(Date startDate, Date endDate) {
        Calendar d1 = Calendar.getInstance();
        d1.setTime(startDate);
        Calendar d2 = Calendar.getInstance();
        d2.setTime(endDate);
        boolean negative = false;
        if (d1.after(d2)) {
            negative = true;
            d1.setTime(endDate);
            d2.setTime(startDate);
        }
        int days = d2.get(Calendar.DAY_OF_YEAR) - d1.get(Calendar.DAY_OF_YEAR);
        int y2 = d2.get(Calendar.YEAR);
        if (d1.get(Calendar.YEAR) != y2) {
            Calendar d3 = (Calendar) d1.clone();
            do {
                days += d3.getActualMaximum(Calendar.DAY_OF_YEAR);//得到当年的实际天数
                d3.add(Calendar.YEAR, 1);
            } while (d3.get(Calendar.YEAR) != y2);
        }
        if (negative) {
            days = 0 - days;
        }
        return days;
    }

    public static Date getMinTimeByFuzzyDateString(String str) {
        try {
            if (str.length() < MIN_DATETIME_STRING.length()) {
                String minStatisticsDateStr = str + MIN_DATETIME_STRING.substring(str.length());
                return new SimpleDateFormat(DATETIME_FORMAT_FUZZY).parse(minStatisticsDateStr);
            } else {
                return new SimpleDateFormat(DATETIME_FORMAT_FUZZY).parse(str);
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    public static Date getMaxTimeByFuzzyDateString(String str) {
        try {
            if (str.length() < MAX_DATETIME_STRING.length()) {
                String minStatisticsDateStr = str + MAX_DATETIME_STRING.substring(str.length());
                return new SimpleDateFormat(DATETIME_FORMAT_FUZZY).parse(minStatisticsDateStr);
            } else {
                return new SimpleDateFormat(DATETIME_FORMAT_FUZZY).parse(str);
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }
	
	 /**
     * Convert millis to human readable time
     *
     * @param millis TimeStamp
     *
     * @return Time String
     */
    public static String millisToTime(long millis){
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis)
                - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis));
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
                - TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(millis));
        long hours = TimeUnit.MILLISECONDS.toHours(millis);

        return (hours == 0 ? "" : hours < 10 ? "0" + hours +":" :
                hours +":") +
                (minutes == 0 ? "00" : minutes < 10 ? "0" + minutes :
                        String.valueOf(minutes)) + ":"
                + (seconds == 0 ? "00" : seconds < 10 ? "0" + seconds
                        : String.valueOf(seconds));

    }
    /**
     * Convert millis to human readable time
     *
     * @param time Time string
     * @return Time String
     */
    public static long timeToMillis(String time) {
        String[] hhmmss = time.split(":");
        int hours = 0;
        int minutes;
        int seconds;
        if(hhmmss.length == 3) {
           hours = Integer.parseInt(hhmmss[0]);
           minutes = Integer.parseInt(hhmmss[1]);
           seconds = Integer.parseInt(hhmmss[2]);
        } else {
            minutes = Integer.parseInt(hhmmss[0]);
            seconds = Integer.parseInt(hhmmss[1]);
        }
        return (((hours * 60)+(minutes * 60) + seconds) * 1000);
    }
    /**
     * Tell whether or not a given string represent a date time string or a simple date
     *
     * @param dateString Date String
     * @return True if given string is a date time False otherwise
     */
    public static boolean isDateTime(String dateString) {
        return (dateString != null) && (dateString.trim().split(" ").length > 1);
    }
    /**
     * Tell whether or not a given date is yesterday
     * @param date Date Object
     * @return True if the date is yesterday False otherwise
     */
    public static boolean isYesterday(Date date){
        // Check if yesterday
        Calendar c1 = Calendar.getInstance(); // today
        c1.add(Calendar.DAY_OF_YEAR, -1); // yesterday
        Calendar c2 = Calendar.getInstance();
        c2.setTime(date); //
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR)
                && c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR);
    }
    /**
     * Tell whether or not a given date is yesterday
     * @param dateString Date String
     * @return True if the date is yesterday False otherwise
     */
    public static boolean isYesterday(String dateString){
        return isYesterday(parse(dateString));
    }

    /**
     * Tell whether or not a given date is today date
     * @param date Date object
     * @return True if date is today False otherwise
     */
    public static boolean isToday(Date date){
         Calendar c1 = Calendar.getInstance(); // today
        Calendar c2 = Calendar.getInstance();
        c2.setTime(date); //
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR)
                && c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR);
    }

    /**
     * Tell whether or not a given date is today date
     * @param dateString Date string
     * @return True if date is today False otherwise
     */
    public static boolean isToday(String dateString){
        return isToday(parse(dateString));
    }

    /**
     * Get Previous month from a given date
     * @param date Date start
     * @return Date of the previous month
     */
    public static Date getPreviousMonthDate(Date date) {
        Calendar c = Calendar.getInstance();
        c.setTime(date); //
        c.add(Calendar.MONTH, -1);
        return  c.getTime();
    }

    /**
     * Get Previous month from a given date
     * @param date Date start
     * @return Date of the previous month
     */
    public static Date getPreviousMonthDate(String date) {
        return getPreviousMonthDate(parse(date));
    }

    /**
     * Get Next month from a given date
     * @param date Date start
     * @return Date of the previous month
     */
    public static Date getNextMonthDate(Date date) {
        Calendar c = Calendar.getInstance();
        c.setTime(date); //
        c.add(Calendar.MONTH, 1);
        return  c.getTime();
    }
    /**
     * Get Previous month from a given date
     * @param date String Date start
     * @return Date of the previous month
     */
    public static Date getNextMonthDate(String date) {
        return getNextMonthDate(parse(date));
    }

    /**
     * Get Previous week date
     * @param date Date Object
     * @param dayOfTheWeek Day Of the week
     * @return Date
     */
    public static Date getPreviousWeekDate(Date date, int dayOfTheWeek) {
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        c.setFirstDayOfWeek(dayOfTheWeek);
        c.set(Calendar.DAY_OF_WEEK, dayOfTheWeek);
        c.add(Calendar.DATE, -7);
        return  c.getTime();
    }

    /**
     * Get Previous week date
     * @param date Date String
     * @param dayOfTheWeek Day Of the week
     * @return Date
     */
    public static Date getPreviousWeekDate(String date, int dayOfTheWeek) {
        return  getPreviousWeekDate(parse(date),dayOfTheWeek);
    }

    /**
     * Get Next week date
     * @param date Date Object
     * @param dayOfTheWeek Day Of the week
     * @return Date
     */
    public static Date getNextWeekDate(Date date, int dayOfTheWeek) {
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        c.setFirstDayOfWeek(dayOfTheWeek);
        c.set(Calendar.DAY_OF_WEEK, dayOfTheWeek);
        c.add(Calendar.DATE, 7);
        return  c.getTime();
    }

    /**
     * Get Next week date
     * @param date Date Object
     * @return Date
     */
    public static Date getNextWeekDate(Date date) {
        return  getNextWeekDate(date,Calendar.MONDAY);
    }
    /**
     * Get Next week date
     * @param date Date Object
     * @return Date
     */
    public static Date getNextWeekDate(String date) {
        return  getNextWeekDate(parse(date));
    }
    /**
     * Get Next week date
     * @param date Date Object
     * @param dayOfTheWeek Day Of the week
     * @return Date
     */
    public static Date getNextWeekDate(String date, int dayOfTheWeek) {
        return  getNextWeekDate(parse(date),dayOfTheWeek);
    }
    /**
     * Get difference between two dates
     *
     * @param nowDate  Current date
     * @param oldDate  Date to compare
     * @param dateDiff Difference Unit
     * @return Difference
     */
    public static int getDateDiff(Date nowDate, Date oldDate, TimeUnit dateDiff) {
        long diffInMs = nowDate.getTime() - oldDate.getTime();
        int days = (int) TimeUnit.MILLISECONDS.toDays(diffInMs);
        int hours = (int) (TimeUnit.MILLISECONDS.toHours(diffInMs) - TimeUnit.DAYS.toHours(days));
        int minutes = (int) (TimeUnit.MILLISECONDS.toMinutes(diffInMs) - TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(diffInMs)));
        int seconds = (int) TimeUnit.MILLISECONDS.toSeconds(diffInMs);
        switch (dateDiff) {
            case DAYS:
                return days;
            case SECONDS:
                return seconds;
            case MINUTES:
                return minutes;
            case HOURS:
                return hours;
            case MILLISECONDS:
            default:
                return (int) diffInMs;
        }
    }
	
	/**
     * 输入两个时间,格式化为人类可读的时间差总计字符串
     *
	 *
	 * @param nowDate
	 * @param oldDate
	 * @return  */
	 public static String getHumanReadingDateDiff(Date nowDate, Date oldDate) {
        long diffInMs = nowDate.getTime() - oldDate.getTime();
		if(diffInMs<0){
			diffInMs = -diffInMs;
		}
		return getHumanReadingTimes(diffInMs);
	 }
	
	/**
     * 输入毫秒流逝时间,格式化为人类可读的时间总计字符串
     *
	 * @param milliseconds
     * @return Difference
     */
    public static String getHumanReadingTimes(long milliseconds) {
        int days = (int) TimeUnit.MILLISECONDS.toDays(milliseconds);
        int hours = (int) (TimeUnit.MILLISECONDS.toHours(milliseconds) - TimeUnit.DAYS.toHours(days));
        int minutes = (int) (TimeUnit.MILLISECONDS.toMinutes(milliseconds) - TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(milliseconds)));
        int seconds = (int) (TimeUnit.MILLISECONDS.toSeconds(milliseconds) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(milliseconds)));
        StringBuilder sb = new StringBuilder();
		if(days>0){
			sb.append(days).append(' ').append("days").append(' ');
		}
		if(hours>0){
			sb.append(hours).append(' ').append("hours").append(' ');
		}
		if(minutes>0){
			sb.append(minutes).append(' ').append("minutes").append(' ');
		}
		if(seconds>0){
			sb.append(seconds).append(' ').append("seconds");
		}
		if(sb.length()==0){
			sb.append(milliseconds).append(' ').append("milliseconds");
		}
		return sb.toString();
	 }
    /**
     * Get difference between two dates
     *
     * @param nowDate  Current date
     * @param oldDate  Date to compare
     * @param dateDiff Difference Unit
     * @return Difference
     */
    public static int getDateDiff(String nowDate, Date oldDate, TimeUnit dateDiff) {
        return getDateDiff(parse(nowDate),oldDate,dateDiff);
    }
    /**
     * Get difference between two dates
     *
     * @param nowDate  Current date
     * @param oldDate  Date to compare
     * @param dateDiff Difference Unit
     * @return Difference
     */
    public static int getDateDiff(Date nowDate, String oldDate, TimeUnit dateDiff) {
        return getDateDiff(nowDate,parse(oldDate),dateDiff);
    }
    /**
     * Get difference between two dates
     *
     * @param nowDate  Current date
     * @param oldDate  Date to compare
     * @param dateDiff Difference Unit
     * @return Difference
     */
    public static int getDateDiff(String nowDate, String oldDate, TimeUnit dateDiff) {
        return getDateDiff(parse(nowDate),parse(oldDate),dateDiff);
    }
 

    public static void main(String[] args) throws ParseException {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        Date d1 = sdf.parse("2015-09-30");
        Date d2 = sdf.parse("2015-10-30");
        Calendar cal = Calendar.getInstance();
        cal.setTime(d1);
        cal.add(Calendar.MONTH, 15);
        //System.out.println(getBetweenDays(d2, d2));
        System.out.println(new SimpleDateFormat(DATETIME_FORMAT).format(getMaxTimeByFuzzyDateString("198708")));
		
				System.out.println(getHumanReadingDateDiff(new Date(), parse("2015-09-30")));
	    }

    /**
     * ============================================================================
     * 使用示例 - 完整演示 DateUtil 类的各种功能
     * ============================================================================
     * 
     * <pre>
     * <code>
     * // 示例1: 基本日期解析和格式化
     * public void basicDateOperationsExample() {
     *     // 解析各种格式的日期字符串
     *     Date date1 = DateUtil.parse("2023-12-25");                 // 标准日期格式
     *     Date date2 = DateUtil.parse("2023-12-25 14:30:00");        // 标准日期时间格式
     *     Date date3 = DateUtil.parse("12/25/2023 14:30:00");        // 美式格式
     *     Date date4 = DateUtil.parse("20231225143000000");          // 模糊日期格式
     *     Date date5 = DateUtil.parse("1703511000000");              // 时间戳
     *     
     *     System.out.println("解析结果:");
     *     System.out.println("  日期1: " + DateUtil.format(date1));
     *     System.out.println("  日期2: " + DateUtil.format(date2));
     *     System.out.println("  日期3: " + DateUtil.format(date3));
     *     System.out.println("  日期4: " + DateUtil.format(date4));
     *     System.out.println("  日期5: " + DateUtil.format(date5));
     *     
     *     // 自定义格式化
     *     SimpleDateFormat customFormat = new SimpleDateFormat(DateUtil.DATETIME_FORMAT_MS);
     *     System.out.println("  自定义格式: " + customFormat.format(date2));
     * }
     * 
     * // 示例2: 英文日期格式解析
     * public void englishDateParsingExample() {
     *     // 解析各种英文日期格式
     *     String[] englishDates = {
     *         "2023/Dec/25",                    // yyyy/MMM/dd
     *         "2023-Dec-25",                    // yyyy-MMM-dd
     *         "Dec/25/2023",                    // MMM/dd/yyyy
     *         "Dec-25-2023",                    // MMM-dd-yyyy
     *         "Dec,25,2023",                    // MMM,dd,yyyy
     *         "Mon, 25 Dec, 2023",              // EEE, dd MMM, yyyy
     *         "Mon Dec 25 14:30:00 CST 2023",   // EEE MMM dd HH:mm:ss Z yyyy
     *     };
     *     
     *     System.out.println("英文日期解析:");
     *     for (String dateStr : englishDates) {
     *         try {
     *             Date date = DateUtil.parse(dateStr);
     *             System.out.println("  " + dateStr + " -> " + DateUtil.format(date));
     *         } catch (Exception e) {
     *             System.out.println("  " + dateStr + " -> 解析失败: " + e.getMessage());
     *         }
     *     }
     * }
     * 
     * // 示例3: 日期差计算
     * public void dateDifferenceExample() throws ParseException {
     *     SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
     *     Date startDate = sdf.parse("2023-12-25 10:00:00");
     *     Date endDate = sdf.parse("2023-12-27 14:30:45");
     *     
     *     // 计算天数差
     *     int daysBetween = DateUtil.getBetweenDays(startDate, endDate);
     *     System.out.println("天数差: " + daysBetween + " 天");
     *     
     *     // 使用TimeUnit计算不同单位的差值
     *     long diffMillis = DateUtil.getDateDiff(endDate, startDate, TimeUnit.MILLISECONDS);
     *     long diffSeconds = DateUtil.getDateDiff(endDate, startDate, TimeUnit.SECONDS);
     *     long diffMinutes = DateUtil.getDateDiff(endDate, startDate, TimeUnit.MINUTES);
     *     long diffHours = DateUtil.getDateDiff(endDate, startDate, TimeUnit.HOURS);
     *     
     *     System.out.println("时间差:");
     *     System.out.println("  毫秒: " + diffMillis);
     *     System.out.println("  秒: " + diffSeconds);
     *     System.out.println("  分钟: " + diffMinutes);
     *     System.out.println("  小时: " + diffHours);
     *     
     *     // 人类可读的时间差
     *     String humanReadable = DateUtil.getHumanReadingDateDiff(endDate, startDate);
     *     System.out.println("可读时间差: " + humanReadable);
     * }
     * 
     * // 示例4: 模糊日期处理
     * public void fuzzyDateExample() {
     *     // 模糊日期字符串处理
     *     String fuzzyDate = "202312";
     *     
     *     // 获取该模糊日期的最小时间
     *     Date minTime = DateUtil.getMinTimeByFuzzyDateString(fuzzyDate);
     *     
     *     // 获取该模糊日期的最大时间
     *     Date maxTime = DateUtil.getMaxTimeByFuzzyDateString(fuzzyDate);
     *     
     *     System.out.println("模糊日期处理:");
     *     System.out.println("  输入: " + fuzzyDate);
     *     System.out.println("  最小时间: " + DateUtil.format(minTime));
     *     System.out.println("  最大时间: " + DateUtil.format(maxTime));
     *     
     *     // 更多示例
     *     String[] fuzzyDates = {"2023", "202312", "20231225", "2023122514"};
     *     for (String fd : fuzzyDates) {
     *         Date min = DateUtil.getMinTimeByFuzzyDateString(fd);
     *         Date max = DateUtil.getMaxTimeByFuzzyDateString(fd);
     *         System.out.println("  " + fd + " -> [" + DateUtil.format(min) + " ~ " + DateUtil.format(max) + "]");
     *     }
     * }
     * 
     * // 示例5: 时间格式转换
     * public void timeFormatConversionExample() {
     *     // 毫秒转时间字符串
     *     long milliseconds = 3723000; // 1小时2分钟3秒
     *     String timeString = DateUtil.millisToTime(milliseconds);
     *     System.out.println("毫秒转时间: " + milliseconds + "ms -> " + timeString);
     *     
     *     // 时间字符串转毫秒
     *     String timeStr = "01:02:03";
     *     long millis = DateUtil.timeToMillis(timeStr);
     *     System.out.println("时间转毫秒: " + timeStr + " -> " + millis + "ms");
     *     
     *     // 更多转换示例
     *     String[] timeStrings = {"00:00:00", "00:05:30", "01:00:00", "23:59:59"};
     *     for (String ts : timeStrings) {
     *         long ms = DateUtil.timeToMillis(ts);
     *         String converted = DateUtil.millisToTime(ms);
     *         System.out.println("  " + ts + " -> " + ms + "ms -> " + converted);
     *     }
     * }
     * 
     * // 示例6: 日期判断和验证
     * public void dateValidationExample() {
     *     Date today = new Date();
     *     Date yesterday = new Date(today.getTime() - 24 * 60 * 60 * 1000);
     *     
     *     // 判断是否为今天
     *     boolean isToday = DateUtil.isToday(today);
     *     boolean isYesterday = DateUtil.isYesterday(yesterday);
     *     
     *     System.out.println("日期判断:");
     *     System.out.println("  今天日期: " + DateUtil.format(today));
     *     System.out.println("  是今天吗? " + isToday);
     *     System.out.println("  昨天日期: " + DateUtil.format(yesterday));
     *     System.out.println("  是昨天吗? " + isYesterday);
     *     
     *     // 判断日期字符串
     *     String todayStr = DateUtil.format(today);
     *     String yesterdayStr = DateUtil.format(yesterday);
     *     
     *     boolean isTodayStr = DateUtil.isToday(todayStr);
     *     boolean isYesterdayStr = DateUtil.isYesterday(yesterdayStr);
     *     
     *     System.out.println("字符串日期判断:");
     *     System.out.println("  \"" + todayStr + "\" 是今天吗? " + isTodayStr);
     *     System.out.println("  \"" + yesterdayStr + "\" 是昨天吗? " + isYesterdayStr);
     *     
     *     // 判断是否为日期时间字符串
     *     String dateOnly = "2023-12-25";
     *     String dateTimeStr = "2023-12-25 14:30:00";
     *     
     *     boolean isDateTime1 = DateUtil.isDateTime(dateOnly);
     *     boolean isDateTime2 = DateUtil.isDateTime(dateTimeStr);
     *     
     *     System.out.println("日期时间判断:");
     *     System.out.println("  \"" + dateOnly + "\" 是日期时间吗? " + isDateTime1);
     *     System.out.println("  \"" + dateTimeStr + "\" 是日期时间吗? " + isDateTime2);
     * }
     * 
     * // 示例7: 日期计算（上月、下月、上周、下周）
     * public void dateCalculationExample() {
     *     Date currentDate = new Date();
     *     
     *     // 获取上月日期
     *     Date previousMonth = DateUtil.getPreviousMonthDate(currentDate);
     *     
     *     // 获取下月日期
     *     Date nextMonth = DateUtil.getNextMonthDate(currentDate);
     *     
     *     // 获取上周一
     *     Date previousMonday = DateUtil.getPreviousWeekDate(currentDate, Calendar.MONDAY);
     *     
     *     // 获取下周一
     *     Date nextMonday = DateUtil.getNextWeekDate(currentDate, Calendar.MONDAY);
     *     
     *     System.out.println("日期计算:");
     *     System.out.println("  当前日期: " + DateUtil.format(currentDate));
     *     System.out.println("  上月日期: " + DateUtil.format(previousMonth));
     *     System.out.println("  下月日期: " + DateUtil.format(nextMonth));
     *     System.out.println("  上周一: " + DateUtil.format(previousMonday));
     *     System.out.println("  下周一: " + DateUtil.format(nextMonday));
     *     
     *     // 使用字符串参数
     *     String dateStr = "2023-12-25";
     *     Date prevMonthFromStr = DateUtil.getPreviousMonthDate(dateStr);
     *     Date nextMonthFromStr = DateUtil.getNextMonthDate(dateStr);
     *     
     *     System.out.println("字符串日期计算:");
     *     System.out.println("  输入: " + dateStr);
     *     System.out.println("  上月: " + DateUtil.format(prevMonthFromStr));
     *     System.out.println("  下月: " + DateUtil.format(nextMonthFromStr));
     * }
     * 
     * // 示例8: 人类可读时间格式化
     * public void humanReadableTimeExample() {
     *     // 测试各种时间长度的可读格式
     *     long[] durations = {
     *         500,                          // 500毫秒
     *         5000,                         // 5秒
     *         65000,                        // 1分5秒
     *         3600000,                      // 1小时
     *         3665000,                      // 1小时1分5秒
     *         86400000,                     // 1天
     *         90061000,                     // 1天1小时1分1秒
     *         172800000,                    // 2天
     *     };
     *     
     *     System.out.println("人类可读时间格式化:");
     *     for (long duration : durations) {
     *         String readable = DateUtil.getHumanReadingTimes(duration);
     *         System.out.println("  " + duration + "ms -> " + readable);
     *     }
     *     
     *     // 日期差的可读格式
     *     try {
     *         SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
     *         Date start = sdf.parse("2023-12-25 10:00:00");
     *         Date end = sdf.parse("2023-12-28 11:30:45");
     *         
     *         String dateDiffReadable = DateUtil.getHumanReadingDateDiff(end, start);
     *         System.out.println("日期差可读格式: " + dateDiffReadable);
     *     } catch (Exception e) {
     *         e.printStackTrace();
     *     }
     * }
     * 
     * // 示例9: 实际应用场景 - 文件时间戳处理
     * public void fileTimestampExample() {
     *     // 模拟文件时间戳处理
     *     List<FileInfo> files = Arrays.asList(
     *         new FileInfo("report.pdf", "2023-12-25 10:30:00"),
     *         new FileInfo("data.csv", "2023-12-24 14:15:00"),
     *         new FileInfo("image.jpg", "2023-12-23 09:45:00"),
     *         new FileInfo("config.json", "2023-11-30 16:20:00")
     *     );
     *     
     *     Date now = new Date();
     *     
     *     System.out.println("文件时间戳分析:");
     *     for (FileInfo file : files) {
     *         Date fileDate = DateUtil.parse(file.getTimestamp());
     *         
     *         // 判断文件是否是今天创建的
     *         boolean isToday = DateUtil.isToday(fileDate);
     *         
     *         // 判断文件是否是昨天创建的
     *         boolean isYesterday = DateUtil.isYesterday(fileDate);
     *         
     *         // 计算文件创建时间距离现在多久
     *         String timeAgo = DateUtil.getHumanReadingDateDiff(now, fileDate);
     *         
     *         System.out.println("  文件: " + file.getName());
     *         System.out.println("    时间: " + file.getTimestamp());
     *         System.out.println("    是今天吗? " + isToday);
     *         System.out.println("    是昨天吗? " + isYesterday);
     *         System.out.println("    创建于: " + timeAgo + "前");
     *         System.out.println();
     *     }
     * }
     * 
     * // 示例10: 综合使用场景 - 日志时间分析
     * public void logTimeAnalysisExample() {
     *     // 模拟日志条目
     *     List<LogEntry> logs = Arrays.asList(
     *         new LogEntry("INFO", "User login successful", "2023-12-25 14:30:15,123"),
     *         new LogEntry("ERROR", "Database connection failed", "2023-12-25 14:32:45,789"),
     *         new LogEntry("WARN", "High memory usage", "2023-12-25 14:35:10,456"),
     *         new LogEntry("INFO", "Backup completed", "2023-12-25 14:40:00,000")
     *     );
     *     
     *     System.out.println("日志时间分析:");
     *     
     *     // 解析日志时间
     *     List<Date> logTimes = new ArrayList<>();
     *     for (LogEntry log : logs) {
     *         Date logTime = DateUtil.parse(log.getTimestamp());
     *         logTimes.add(logTime);
     *         System.out.println("  " + log.getLevel() + " - " + log.getMessage());
     *         System.out.println("    时间: " + DateUtil.format(logTime));
     *     }
     *     
     *     // 计算日志时间间隔
     *     if (logTimes.size() > 1) {
     *         System.out.println("\n时间间隔分析:");
     *         for (int i = 1; i < logTimes.size(); i++) {
     *             long interval = DateUtil.getDateDiff(logTimes.get(i), logTimes.get(i-1), TimeUnit.MILLISECONDS);
     *             String readableInterval = DateUtil.getHumanReadingTimes(interval);
     *             System.out.println("  日志" + i + "到日志" + (i+1) + ": " + readableInterval);
     *         }
     *     }
     *     
     *     // 模糊查询日志（按小时）
     *     String fuzzyHour = "2023122514"; // 2023年12月25日14时
     *     Date startOfHour = DateUtil.getMinTimeByFuzzyDateString(fuzzyHour);
     *     Date endOfHour = DateUtil.getMaxTimeByFuzzyDateString(fuzzyHour);
     *     
     *     System.out.println("\n模糊查询(按小时):");
     *     System.out.println("  查询: " + fuzzyHour);
     *     System.out.println("  时间范围: " + DateUtil.format(startOfHour) + " ~ " + DateUtil.format(endOfHour));
     *     
     *     // 找出该小时的日志
     *     int hourLogs = 0;
     *     for (Date logTime : logTimes) {
     *         if (logTime.compareTo(startOfHour) >= 0 && logTime.compareTo(endOfHour) <= 0) {
     *             hourLogs++;
     *         }
     *     }
     *     System.out.println("  该小时日志数量: " + hourLogs);
     * }
     * 
     * // 数据模型定义
     * static class FileInfo {
     *     private String name;
     *     private String timestamp;
     *     
     *     public FileInfo(String name, String timestamp) {
     *         this.name = name;
     *         this.timestamp = timestamp;
     *     }
     *     
     *     public String getName() { return name; }
     *     public String getTimestamp() { return timestamp; }
     * }
     * 
     * static class LogEntry {
     *     private String level;
     *     private String message;
     *     private String timestamp;
     *     
     *     public LogEntry(String level, String message, String timestamp) {
     *         this.level = level;
     *         this.message = message;
     *         this.timestamp = timestamp;
     *     }
     *     
     *     public String getLevel() { return level; }
     *     public String getMessage() { return message; }
     *     public String getTimestamp() { return timestamp; }
     * }
     * 
     * // 示例11: 主方法演示全部功能
     * public static void main(String[] args) {
     *     try {
     *         DateUtilExample demo = new DateUtilExample();
     *         
     *         System.out.println("=== DateUtil 使用示例演示 ===");
     *         System.out.println();
     *         
     *         System.out.println("1. 基本日期操作演示:");
     *         demo.basicDateOperationsExample();
     *         System.out.println();
     *         
     *         System.out.println("2. 日期差计算演示:");
     *         demo.dateDifferenceExample();
     *         System.out.println();
     *         
     *         System.out.println("3. 模糊日期处理演示:");
     *         demo.fuzzyDateExample();
     *         System.out.println();
     *         
     *         System.out.println("4. 时间格式转换演示:");
     *         demo.timeFormatConversionExample();
     *         System.out.println();
     *         
     *         System.out.println("5. 人类可读时间演示:");
     *         demo.humanReadableTimeExample();
     *         System.out.println();
     *         
     *         System.out.println("6. 实际应用演示:");
     *         demo.fileTimestampExample();
     *         
     *     } catch (Exception e) {
     *         e.printStackTrace();
     *     }
     * }
     * </code>
     * </pre>
     * 
     * 注意事项:
     * 1. DateUtil.parse() 方法支持多种日期格式自动识别
     * 2. 英文日期解析需要匹配特定的格式模式
     * 3. 模糊日期处理适用于按年、月、日、小时等维度查询的场景
     * 4. 时间计算使用Calendar类，考虑到了时区和夏令时等问题
     * 5. 人类可读时间格式化适用于日志、报告等需要友好展示的场景
     * 6. SimpleDateFormat不是线程安全的，建议在局部变量中使用
     * 7. 对于生产环境，建议使用Java 8的java.time包（如果可用）
     */
}
