package javax.net.p2p.model;

import java.util.List;

/**
 * **************************************************
 * @description 搜索服务节点
 * @author   karl
 * @version  1.0, 2018-9-10
 * @see HISTORY
 *      Date        Desc          Author      Operation
 *  	2018-9-10   创建文件       karl        create
 * @since 2017 Phyrose Science & Technology (Kunming) Co., Ltd.
 **************************************************/
public class Searcher {

    private List<Node> serviceNodes;

    public List<Node> getServiceNodes() {
        return serviceNodes;
    }

    public void setServiceNodes(List<Node> serviceNodes) {
        this.serviceNodes = serviceNodes;
    }

}
