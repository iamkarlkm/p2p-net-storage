
package com.q3lives.ds.interfaces;

/**
 *
 * @author Administrator
 */
public interface DsPropCheckable {
    
    /**
     * 如果约束校验不过,返回一个错误消息,否则返回null
     * @param prop
     * @return 
     */
    String checkProp(Object prop);
   
}
