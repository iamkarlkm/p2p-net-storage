package com.q3lives.ds.bucket;

import java.io.File;
import java.util.concurrent.locks.ReentrantLock;

import com.q3lives.ds.core.DsObject;

/**
 * 空闲块栈（或队列）。
 * <p>
 * 用于管理空闲的数据块索引，以便重复利用存储空间。
 * 虽然命名为 Stack，但当前实现类似于一个循环队列 (Ring Buffer)。
 * </p>
 */
public class DsFreeBlockStack extends DsObject{

    private final static ReentrantLock LOCAL_LOCK = new ReentrantLock();
    private int[] queue;
    private int head;
    private int tail;
    private int size;
    private int capacity;
    
    
    /**
     * 创建一个空闲块队列实例。
     *
     * <p>当前实现的数据结构在内存中维护 ring buffer；底层文件映射与持久化格式由 {@link DsObject} 负责。</p>
     *
     * @param dataFile 底层文件
     * @param dataUnitSize 单元大小（byte）
     */
    public DsFreeBlockStack(File dataFile, int dataUnitSize) {
        super(dataFile,dataUnitSize);
    }

//    public DsFreeBlockStack(int capacity) {
//        this.capacity = capacity;
//        this.queue = new int[capacity];
//        this.head = 0;
//        this.tail = 0;
//        this.size = 0;
//    }

    // 其他方法实现...
    /**
     * 是否为空。
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * 是否已满。
     */
    public boolean isFull() {
        return size == capacity;
    }

    /**
     * 入队（添加空闲块）。
     * @param data 空闲块索引
     */
    public void enqueue(int data) {
        if (isFull()) {
            // 队列已满，无法添加数据
            return;
        }
        queue[tail] = data;
        tail = (tail + 1) % capacity;
        size++;
    }

    /**
     * 出队（获取空闲块）。
     * @return 空闲块索引，如果队列为空则返回 -1
     */
    public int dequeue() {
        if (isEmpty()) {
            // 队列为空，无法删除数据
            return -1;
        }
        int data = queue[head];
        head = (head + 1) % capacity;
        size--;
        return data;
    }

    /**
     * 当前队列中元素数量。
     */
    public int size() {
        return size;
    }
}
