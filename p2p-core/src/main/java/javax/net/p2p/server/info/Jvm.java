

package javax.net.p2p.server.info;


import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.Date;

import javax.net.p2p.utils.DateUtil;
import javax.net.p2p.utils.MathUtil;

/**
 * JVM相关信息
 * @author zengxueqi
 * @since 2020/07/14
 */
public class Jvm {

    /**
     * 当前JVM占用的内存总数(M)
     */
    private double total;

    /**
     * JVM最大可用内存总数(M)
     */
    private double max;

    /**
     * JVM空闲内存(M)
     */
    private double free;

    /**
     * JDK版本
     */
    private String version;

    /**
     * JDK路径
     */
    private String home;
	
	private final RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();

    public double getTotal() {
        return MathUtil.div(total, (1024 * 1024), 2);
    }

    public void setTotal(double total) {
        this.total = total;
    }

    public double getMax() {
        return MathUtil.div(max, (1024 * 1024), 2);
    }

    public void setMax(double max) {
        this.max = max;
    }

    public double getFree() {
        return MathUtil.div(free, (1024 * 1024), 2);
    }

    public void setFree(double free) {
        this.free = free;
    }

    public double getUsed() {
        return MathUtil.div(total - free, (1024 * 1024), 2);
    }

    public double getUsage() {
        return MathUtil.mul(MathUtil.div(total - free, total, 4), 100);
    }

    /**
     * 获取JDK名称
     */
    public String getName() {
        return runtimeMXBean.getVmName();
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getHome() {
        return home;
    }

    public void setHome(String home) {
        this.home = home;
    }

    /**
     * JDK启动时间
	 * @return 
     */
    public String getStartTime() {
        return DateUtil.format(new Date(runtimeMXBean.getStartTime()));
    }

    /**
     * JDK运行时间
	 * @return 
     */
    public String getRunTime() {
        return DateUtil.getHumanReadingTimes(runtimeMXBean.getUptime());
    }

}
