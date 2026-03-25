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
