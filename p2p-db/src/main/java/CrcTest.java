
import java.util.zip.CRC32;

/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

/**
 *
 * @author karl
 */
public class CrcTest {
	public static void main(String[] args) {
        


        //字符串
        /**/
        CRC32 crc32 = new CRC32();
//        crc32.update("pSoLCT0gOz18lmzXP53U34驾驶人电子档案采集eusHINHqZj8PjB1jsjzX17".getBytes());
        crc32.update("1170508026449ENC(1170508026449ENC(x7MSC7H0clHT7DzcD5krzNVzYr83bNUl)                                                                                           PD梁东萍02ABK562丰田JTDBE30K300K335301022017-05-08 10:26:032017-05-08 11:42:43E25EE530100000400000ENC(cMqzHctec7sBi7PBci8Mpf9nq89A4HMNh0UODGcElx0=)2020-07-23 14:55:221d85df45c499d44ddaefd5ee612dcf860012020-06-30 17:12:38)".getBytes());
        System.out.println(crc32.getValue());
        String c = Long.toHexString(crc32.getValue());
        crc32.reset();
//		long u = 0x29010a24;
		System.out.println(c);
		
		crc32 = new CRC32();
        crc32.update("1234567890                                                                                           黄金开PD梁东萍02ABK562丰田JTDBE30K300K335301022017-05-08 10:26:032017-05-08 11:42:43E25EE530100000400000ENC(cMqzHctec7sBi7PBci8Mpf9nq89A4HMNh0UODGcElx0=)2020-07-23 14:55:221d85df45c499d44ddaefd5ee612dcf860012020-06-30 17:12:38".getBytes());
//        crc32.update("1234567890".getBytes());
System.out.println(crc32.getValue());
        c = Long.toHexString(crc32.getValue());
        crc32.reset();
		System.out.println(c);

    }

}
