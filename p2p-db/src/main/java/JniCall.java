/**
 * JNI 调用示例（声明 native 方法并提供 main 测试入口）。
 *
 * <p>该文件为演示/实验用途，不参与 ds 存储主流程。</p>
 */
public class JniCall {

    public native void callJava();

    static {
        //System.loadLibrary("JniCalll"); // Load native library at runtime
        // hello.dll (Windows) or libhello.so (Unixes)
    }

    // Declare a native method sayHello() that receives nothing and returns void
    private native void sayHello();

    // Test Driver
    public static void main(String[] args) {
        new JniCall().sayHello();  // invoke the native method
    }
}
