/** @author qiuyi.l
 * @date 2022/7/13 14:24
 *<p>解决JDK module UNNAMED
 *<p>启动参数需要加上--add-exports java.base/jdk.internal.access=ALL-UNNAMED
 *<p>pom.xml需要配置编译参数--add-exports=java.base/jdk.internal.access=ALL-UNNAMED，不然maven编译会报错
 */

public class AccessModuleUtil {
    /**
     *开放所有模块(放到启动的main方法第一行调用。测过运行时调用不会生效)
     */
    public static void exportAll(){
        try {
            Class<?> sharedSecrets = Class.forName("jdk.internal.access.SharedSecrets");
            Object javaLangAccess = sharedSecrets.getMethod("getJavaLangAccess").invoke(null);
            var addOpens = javaLangAccess.getClass().getMethod("addOpensToAllUnnamed", Module.class, String.class);
            var addExports = javaLangAccess.getClass().getMethod("addExportsToAllUnnamed", Module.class, String.class);
            for (Module module : ModuleLayer.boot().modules()) {
                for (String pkgName : module.getPackages()){
                    addOpens.invoke(javaLangAccess, module, pkgName);
                    addExports.invoke(javaLangAccess, module, pkgName);
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
