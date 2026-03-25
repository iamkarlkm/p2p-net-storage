/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package javax.net.p2p.interfaces;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.P2PWrapper;

/**
 *
 * @author Administrator
 */
public interface P2PCommandHandler {
	P2PCommand getCommand();
    P2PWrapper process(P2PWrapper request);
}
