package javax.net.p2p.model;
/**
 * **************************************************
 * @description 
 * @author   karl
 * @version  1.0, 2018-9-10
 * @see HISTORY
 *      Date        Desc          Author      Operation
 *  	2018-9-10   创建文件       karl        create
 * @since 2017 Phyrose Science & Technology (Kunming) Co., Ltd.
 **************************************************/
public class Node {
    private String name;
    private String description;
    private String uri;
    private byte[] sha256;
    private byte[] publicBytes;
    private int likeCount;//点赞计数
    private int blackCount;//被加入黑名单计数
    private int exposeCount;//被举报计数

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public byte[] getSha256() {
        return sha256;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setSha256(byte[] sha256) {
        this.sha256 = sha256;
    }

    public byte[] getPublicBytes() {
        return publicBytes;
    }

    public void setPublicBytes(byte[] publicBytes) {
        this.publicBytes = publicBytes;
    }

    public int getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(int likeCount) {
        this.likeCount = likeCount;
    }

    public int getBlackCount() {
        return blackCount;
    }

    public void setBlackCount(int blackCount) {
        this.blackCount = blackCount;
    }

    public int getExposeCount() {
        return exposeCount;
    }

    public void setExposeCount(int exposeCount) {
        this.exposeCount = exposeCount;
    }

}
