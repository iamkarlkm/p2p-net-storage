package javax.net.p2p.model;

import java.util.List;

/**
 * **************************************************
 * @description 路由服务节点
 * @author   karl
 * @version  1.0, 2018-9-10
 * @see HISTORY
 *      Date        Desc          Author      Operation
 *  	2018-9-10   创建文件       karl        create
 * @since 2017 Phyrose Science & Technology (Kunming) Co., Ltd.
 **************************************************/
public class Router {

    private List<Router> parentRouters;
    private List<Node> serviceNodes;
    private byte[] mask;
    private byte[] subNetMask;
    private int level;//当前网络层级

    public List<Node> getServiceNodes() {
        return serviceNodes;
    }

    public void setServiceNodes(List<Node> serviceNodes) {
        this.serviceNodes = serviceNodes;
    }

    public byte[] getMask() {
        return mask;
    }

    public void setMask(byte[] mask) {
        this.mask = mask;
    }

    public byte[] getSubNetMask() {
        return subNetMask;
    }

    public void setSubNetMask(byte[] subNetMask) {
        this.subNetMask = subNetMask;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public List<Router> getParentRouters() {
        return parentRouters;
    }

    public void setParentRouters(List<Router> parentRouters) {
        this.parentRouters = parentRouters;
    }

}
