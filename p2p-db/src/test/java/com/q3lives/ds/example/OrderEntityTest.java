package com.q3lives.ds.example;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.Date;

/**
 * 订单实体测试
 */
public class OrderEntityTest {
    
    public static void main(String[] args) {
        System.out.println("=== 订单实体测试 ===\n");
        
        // 创建订单
        OrderEntity order = new OrderEntity();
        order.setId(20231001L);
        order.setOrderNo("ORD20231001123456");
        order.setUserId(1001L);
        
        // 设置金额
        order.setTotalAmount(new BigDecimal("999.99"));
        order.setDiscountAmount(new BigDecimal("100.00"));
        order.setActualAmount(new BigDecimal("899.99"));
        
        // 设置时间
        order.setCreateTime(new Date());
        order.setPayTime(new Date());
        order.setShipTime(null);
        order.setCompleteTime(null);
        
        // 设置其他字段
        order.setItemCount(3);
        order.setShippingAddress("北京市朝阳区某某街道123号");
        order.setRemark("请尽快发货");
        
        // 设置复合字段 - 订单状态组
        order.setOrderStatus(2); // 已支付
        order.setPaymentStatus(2); // 已支付
        order.setShippingStatus(0); // 未发货
        order.setPaid(true);
        order.setRefunded(false);
        order.setCancelled(false);
        
        // 设置复合字段 - 订单标志组
        order.setPriority(3); // 高优先级
        order.setSourceChannel(1); // APP渠道
        order.setGift(false);
        order.setNeedInvoice(true);
        
        System.out.println("原始订单:");
        System.out.println(order);
        System.out.println();
        
        // 显示映射信息
        System.out.println(order.getMappingInfo());
        System.out.println();
        
        // 序列化
        System.out.println("序列化订单...");
        ByteBuffer buffer = order.toBytes();
        System.out.println("序列化大小: " + buffer.limit() + " 字节");
        System.out.println();
        
        // 反序列化
        System.out.println("反序列化订单...");
        OrderEntity loadedOrder = new OrderEntity();
        loadedOrder.load(buffer);
        System.out.println("反序列化后订单:");
        System.out.println(loadedOrder);
        System.out.println();
        
        // 验证复合字段
        System.out.println("复合字段验证:");
        System.out.println("订单状态: " + loadedOrder.getOrderStatus() + " (期望: 2)");
        System.out.println("支付状态: " + loadedOrder.getPaymentStatus() + " (期望: 2)");
        System.out.println("物流状态: " + loadedOrder.getShippingStatus() + " (期望: 0)");
        System.out.println("已支付: " + loadedOrder.isPaid() + " (期望: true)");
        System.out.println("已退款: " + loadedOrder.isRefunded() + " (期望: false)");
        System.out.println("已取消: " + loadedOrder.isCancelled() + " (期望: false)");
        System.out.println("优先级: " + loadedOrder.getPriority() + " (期望: 3)");
        System.out.println("来源渠道: " + loadedOrder.getSourceChannel() + " (期望: 1)");
        System.out.println("是否礼物: " + loadedOrder.isGift() + " (期望: false)");
        System.out.println("需要发票: " + loadedOrder.isNeedInvoice() + " (期望: true)");
    }
}
