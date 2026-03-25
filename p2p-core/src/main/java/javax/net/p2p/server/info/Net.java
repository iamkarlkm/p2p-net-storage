/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package javax.net.p2p.server.info;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author karl
 */
public class Net {

	private final InetAddress ipv4Address;
	private final InetAddress ipv6Address;

	public InetAddress getIpv4Address() {
		return ipv4Address;
	}
	
	public InetAddress getIpv6Address() {
		return ipv6Address;
	}
	public Net(){
		try {
			//Handover
			ipv4Address = Inet4Address.getLocalHost();
			ipv6Address = Inet6Address.getLocalHost();
		} catch (UnknownHostException ex) {
			throw new RuntimeException(ex);
		}
	}
	
	public static void main(String[] args) throws Exception {
		InetAddress address = Inet4Address.getLocalHost();
		System.out.println(address.getHostAddress());
		System.out.println(address.getHostName());
	}
}
