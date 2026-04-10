

package javax.net.p2p.utils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
/**
 * PackageScanner。
 */

public class PackageScanner {

    public static List<Class> getPackageClasses(String packageName) throws IOException, ClassNotFoundException {
        String packagePath = packageName.replace('.', '/');
        List<Class> classes = new ArrayList<>();

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        String classPath = classLoader.getResource(packagePath).getFile();
        File packageDirectory = new File(classPath);

        if (packageDirectory.exists() && packageDirectory.isDirectory()) {
            File[] files = packageDirectory.listFiles();
            for (File file : files) {
                String fileName = file.getName();
                if (file.isFile() && fileName.endsWith(".class")) {
                    String className = packageName + '.' + fileName.substring(0, fileName.lastIndexOf('.'));
                    Class<?> clazz = Class.forName(className);
                    classes.add(clazz);
                }
            }
        }

        return classes;
    }

}
