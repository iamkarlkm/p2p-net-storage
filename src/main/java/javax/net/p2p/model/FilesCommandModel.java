/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package javax.net.p2p.model;

import java.util.Arrays;

/**
 *
 * @author karl
 */
public class FilesCommandModel<T> {
    public int storeId;
    private String command;
    private String[] params;
    private T data;

    public FilesCommandModel(int storeId,String command, String... params) {
        this.storeId = storeId;
        this.command = command;
        this.params = params;
    }

    
    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String[] getParams() {
        return params;
    }

    public void setParams(String[] params) {
        this.params = params;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    @Override
    public String toString() {
        return "HdfsCommandModel{" + "command=" + command + ", params=" + Arrays.asList(params) + ", data=" + data + '}';
    }

    
}
