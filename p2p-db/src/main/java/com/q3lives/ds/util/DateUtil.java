package com.q3lives.ds.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 *
 * @author karl
 */
public class DateUtil {

    private static final Log log = LogFactory.getLog(DateUtil.class);
    private static final String MIN_DATETIME_STRING = "00000101000000000";
    private static final String MAX_DATETIME_STRING = "99991231235959999";
    public static final SimpleDateFormat datetimeFormat_fuzzy = new SimpleDateFormat("yyyyMMddHHmmssSSS");
    public static final SimpleDateFormat datetimeFormat_ms = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    public static final SimpleDateFormat datetimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    public static final SimpleDateFormat datetimeFormat_jdk = new SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.US);//28
    public static final SimpleDateFormat datetimeFormat_mm = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    public static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
    public static final SimpleDateFormat yyyyMMdd = new SimpleDateFormat("yyyyMMdd");
    public static final SimpleDateFormat dateFormatUS = new SimpleDateFormat("MM/dd/yyyy");
    public static final SimpleDateFormat datetimeFormatUS = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss");

    private final static SimpleDateFormat[] englishDateFormats;
    
    private final static ReentrantLock LOCK = new ReentrantLock();
    
    static {
        englishDateFormats = new SimpleDateFormat[10];
        englishDateFormats[0] = new SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.US);
        englishDateFormats[1] = new SimpleDateFormat("yyyy-MMM-dd", Locale.US);
        englishDateFormats[2] = new SimpleDateFormat("MMM/dd/yyyy", Locale.US);
        englishDateFormats[3] = new SimpleDateFormat("MMM-dd-yyyy", Locale.US);
        englishDateFormats[4] = new SimpleDateFormat("yyyy/MMM/dd HH:mm:ss a", Locale.US);
        englishDateFormats[5] = new SimpleDateFormat("MMM-dd-yyyy HH:mm:ss", Locale.US);
        englishDateFormats[6] = new SimpleDateFormat("MMM,dd,yyyy", Locale.US);
        englishDateFormats[7] = new SimpleDateFormat("EEE, dd MMM, yyyy", Locale.US);
        englishDateFormats[8] = new SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US);
        englishDateFormats[9] = new SimpleDateFormat("yyyy/MMM/dd HH:mm", Locale.US);
        englishDateFormats[10] = new SimpleDateFormat("yyyy/MMM/dd", Locale.US);
       
    }

    public static Date convertDate(String text) {
        Date date = null;
        LOCK.lock();
        try {
            if (text.length() == 19 && text.indexOf(":") > 0) {
                if (text.contains("/")) {
                    try {
                        date = datetimeFormatUS.parse(text);
                    } catch (Exception e) {
                    }
                } else {
                    try {
                        date = datetimeFormat.parse(text.replace('T', ' '));
                    } catch (Exception e) {
                    }
                }
            }else if (text.length() == 23 && text.indexOf(":") > 0) {
                try {
                    date = datetimeFormat_ms.parse(text.replace('T', ' ').replace(',', '.'));
                } catch (Exception e) {
                }

            } else if (text.length() == 28 && text.indexOf(":") > 0) {
                try {
                    date = datetimeFormat_jdk.parse(text);
                } catch (Exception e) {
                }

            }  else if (text.length() == 10 && !text.contains(":")) {
                if (text.contains("/")) {
                    try {
                        date = dateFormatUS.parse(text);
                    } catch (Exception e) {
                    }
                } else {
                    try {
                        date = dateFormat.parse(text);
                    } catch (Exception e) {
                    }
                }
            } else if (text.length() == 21 && text.indexOf(":") > 0) {
                try {
                    text = text.replace(".0", "");
                    date = datetimeFormat.parse(text);
                } catch (Exception e) {
                }

            } else if (text.length() == 16 && text.indexOf(":") > 0) {
                try {
                    date = datetimeFormat_mm.parse(text.replace('T', ' '));
                } catch (Exception e) {
                }

            } else if (text.length() == 10 && text.indexOf("-") > 0) {
                try {
                    date = dateFormat.parse(text);
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
                date = convertEnglishDate(text);
            }
            if (date == null) {
                try {
                    date = new Date(text);
                } catch (Exception ex) {
                    throw ex;
                }
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not parse to date from string: " + text, ex);
        }finally{
            LOCK.unlock();
        }
        return date;
    }
    
    public static Date convert(Object val) {
       if(val instanceof Long){
           return new Date((Long)val);
       }else if(val instanceof Date){
           return (Date) val;
       }else{
           return convertDate(val.toString());
       }
    }
    
     public static Date toDate(LocalDate localDate) {
        return Date.from(localDate.atStartOfDay().atZone(ZoneId.systemDefault()).toInstant());
    }
 
    public static Date toDate(LocalDateTime localDateTime) {
        return Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant());
    }
 
    public static LocalDate toLocalDate(Date date) {
        return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
    }
 
    public static LocalDateTime toLocalDateTime(Date date) {
        return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
    
    public static Date toDate(LocalTime localTime){
        return java.sql.Timestamp.valueOf(localTime.atDate(LocalDate.now()));
    }
    
    public static LocalTime toLocalTime(Date date){
        return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDateTime().toLocalTime();
    }

    public static Date convertEnglishDate(String text) {
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
                return datetimeFormat_fuzzy.parse(minStatisticsDateStr);
            } else {
                return datetimeFormat_fuzzy.parse(str);
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    public static Date getMaxTimeByFuzzyDateString(String str) {
        try {
            if (str.length() < MAX_DATETIME_STRING.length()) {
                String minStatisticsDateStr = str + MAX_DATETIME_STRING.substring(str.length());
                return datetimeFormat_fuzzy.parse(minStatisticsDateStr);
            } else {
                return datetimeFormat_fuzzy.parse(str);
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    public static void main(String[] args) throws ParseException {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        Date d1 = sdf.parse("2015-09-30");
        Date d2 = sdf.parse("2015-10-30");
        Calendar cal = Calendar.getInstance();
        cal.setTime(d1);
        cal.add(Calendar.MONTH, 15);
        //System.out.println(getBetweenDays(d2, d2));
        System.out.println(datetimeFormat.format(getMaxTimeByFuzzyDateString("198708")));
    }

}
