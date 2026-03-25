/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package javax.net.p2p.filesync;

import java.io.File;

/**
 *
 * @author karl
 */
public interface FileWatchListener {
	
	void process(File file);

}
