
package javax.net.p2p.model;

/**
 *
 * @author karl
 */
public class CloudFileDataModel {

    public long length;
    public byte[] data;

    public String path;
//		public String md5;

    public CloudFileDataModel(String path) {
        this.path = path;
    }

    public CloudFileDataModel(String path, byte[] data) {
        this.data = data;
        this.path = path;
        this.length = data.length;
    }

    @Override
    public String toString() {
        return "CloudFileDataModel{" + "length=" + length + ", data=" + data + ", path=" + path + '}';
    }

}
