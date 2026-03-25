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

}
