
package javax.net.p2p.common.pool;

/**
 *
 * @author karl
 */
public abstract class ClonePooledableAdapter implements ClonePooledable, Cloneable{
    
    protected ClonePooledObjects pool;
    

    @Override
    public void clear() {
        
    }

    @Override
    public ClonePooledable clone() throws CloneNotSupportedException{
        return (ClonePooledable) super.clone();
    }

    @Override
    public void load() {
        
    }
    
    @Override
    public void loadParams(Object... constructorParams) {
        
        
    }
    
    @Override
    public void setPool(ClonePooledObjects pool) {
        this.pool = pool;
    }

   public boolean release(){
       if(pool!=null){
           return pool.offer(this);
       }
       return false;
   }
   
    
}
