

package javax.core.common.config;

import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Properties;
/**
 * SystemEnvironment。
 */

public class SystemEnvironment {
 
	public static void main(String[] args) {
		
		Properties properties = System.getProperties();
		Iterator it = properties.entrySet().iterator();
		while (it.hasNext()) {
			Entry entry = (Entry) it.next();
			System.out.print(entry.getKey() + "="+entry.getValue());
 
		}
//		// java类路径
//		String javaClassPath = System.getProperty("java.class.path");
//		System.out.println(javaClassPath);
// 
//	    System.setProperty("java.class.path", javaClassPath + ";D:\\");
//		
//		javaClassPath = System.getProperty("java.class.path");
//		System.out.println(javaClassPath);
	}
 
}
