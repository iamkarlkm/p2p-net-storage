/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package db;

import cn.hutool.core.date.StopWatch;

/**
 *
 * @author karl
 */
public class Max {
    
    public final static int max(int a, int b)

{

     int mask = (a - b) >> 31;

     return (b & mask) | (a & ~mask);

}
    
     public final static int max2(int a, int b)

{


     return a>b?a:b;

}
    public static void main(String[] args) {
        System.out.println(max(-99,-57));
        
        StopWatch stopWatch = new StopWatch();
		System.out.println( "-执行开始...");
		stopWatch.start();
                for(int i=0;i<1000000;i++){
                    max(-99,-57);
                }
                stopWatch.stop();
                //统计执行时间（毫秒）
		System.out.println("执行时长：" + stopWatch.getLastTaskTimeNanos() + " 毫秒.");
                
                stopWatch = new StopWatch();
		System.out.println( "-执行开始...");
		stopWatch.start();
                for(int i=0;i<1000000;i++){
                    max2(-99,-57);
                }
                stopWatch.stop();
                //统计执行时间（毫秒）
		System.out.println("执行时长：" + stopWatch.getLastTaskTimeNanos() + " 毫秒.");
    }
    
}
