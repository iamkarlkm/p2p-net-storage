package javax.net.p2p.common.pool;


import javax.net.p2p.common.pool.PooledableAdapter;




/**
 *
 * @author karl
 */
public class PooledableTest extends PooledableAdapter{
    
    private long timeout;
    
    private String name;

    public PooledableTest(long timeout, String name) {
        this.timeout = timeout;
        this.name = name;
    }
    
    

    @Override
    public void clear() {
        
    }


    @Override
    public void loadParams(Object... params) {
        this.timeout = (long) params[0];
        this.name =  (String) params[1];
        
    }

    @Override
    public String toString() {
        return "PooledableTest{" + "timeout=" + timeout + ", name=" + name + '}';
    }

    @Override
    public boolean release() {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

   
    
}
