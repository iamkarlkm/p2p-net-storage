/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package db;

/**
 *
 * @author karl
 */
public class NewClass {
	
	public static void main(String[] args) throws Exception {
		byte a = (byte) 0x80;
		System.out.println(a);
		System.out.println(Integer.toHexString(a));
		System.out.println(Integer.toHexString(a& 0b11000000));
		
		System.out.println((byte)(a& 0b11000000));
	}

}
