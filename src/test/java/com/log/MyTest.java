
package com.log;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Slf4j
public class MyTest {

	private static final Logger systemMesseges = LoggerFactory.getLogger("sys");
    public static void main(String[] args) {
		
        log.info("info.....");
        log.warn("warn" + ".....");
        log.error("error,msg={}", "error....");
		systemMesseges.warn("systemMesseges test...");
    }
}
