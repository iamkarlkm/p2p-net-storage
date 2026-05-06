/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

/**
 *
 * @author Administrator
 */
public class NewClass {
    public static void main(String[] args) {
         // 强制设置 System.out 为 UTF-8
    System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        System.out.println(System.getProperty("file.encoding"));
        System.out.println(System.out.charset());
    }
}
