
package com.q3lives.ds.fs;

import java.util.Arrays;

/**
 * 256位DHT路由实现。每8位一层,总计32层路由。
 * @author iamkarl@163.com
 */
public class Ds256DHT {
    
    private byte[] nodeId;// DHT网络唯一标识ID。初始化后,固定不可变。
    private int level;//当前路由层。
    private int nodeSlot;//当前节点负责的slot。

    public Ds256DHT(byte[] nodeId) {
        this.nodeId = nodeId;
    }

    public byte[] getNodeId() {
        return nodeId;
    }

    
    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public int getNodeSlot() {
        return nodeSlot;
    }

    public void setNodeSlot(int nodeSlot) {
        this.nodeSlot = nodeSlot;
    }

    @Override
    public int hashCode() {
        int hash = 7;
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final Ds256DHT other = (Ds256DHT) obj;
        return Arrays.equals(this.nodeId, other.nodeId);
    }

    @Override
    public String toString() {
        return "Ds256DHT{" + "nodeId=" + nodeId + ", level=" + level + ", nodeSlot=" + nodeSlot + '}';
    }
   
    
    
}
