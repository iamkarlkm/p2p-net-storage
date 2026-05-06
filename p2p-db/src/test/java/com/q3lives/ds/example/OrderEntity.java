package com.q3lives.ds.example;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.annotation.DsCompositeField;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsMapField;
import com.q3lives.ds.collections.NewClass;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 订单实体示例 - 展示更复杂的复合列存储
 */
public class OrderEntity extends DsTableAdapter {
    
    // 复合列存储 - 订单状态组（8字节）
    @DsCompositeField(
        name = "orderStatus",
        group = "ORDER_STATUS",
        length = 8,
        startBits = 0,
        endBits = 3
    )
    private int orderStatus; // 0-15: 待支付、已支付、已发货、已完成等
    
    @DsCompositeField(
        name = "paymentStatus",
        group = "ORDER_STATUS",
        length = 8,
        startBits = 4,
        endBits = 6
    )
    private int paymentStatus; // 0-7: 未支付、部分支付、已支付等
    
    @DsCompositeField(
        name = "shippingStatus",
        group = "ORDER_STATUS",
        length = 8,
        startBits = 7,
        endBits = 9
    )
    private int shippingStatus; // 0-7: 未发货、已发货、运输中、已签收等
    
    @DsCompositeField(
        name = "isPaid",
        group = "ORDER_STATUS",
        length = 8,
        startBits = 10,
        endBits = 10
    )
    private boolean isPaid;
    
    @DsCompositeField(
        name = "isRefunded",
        group = "ORDER_STATUS",
        length = 8,
        startBits = 11,
        endBits = 11
    )
    private boolean isRefunded;
    
    @DsCompositeField(
        name = "isCancelled",
        group = "ORDER_STATUS",
        length = 8,
        startBits = 12,
        endBits = 12
    )
    private boolean isCancelled;
    
    // 复合列存储 - 订单标志组（8字节）
    @DsCompositeField(
        name = "priority",
        group = "ORDER_FLAGS",
        length = 8,
        startBits = 0,
        endBits = 2
    )
    private int priority; // 0-7: 优先级
    
    @DsCompositeField(
        name = "sourceChannel",
        group = "ORDER_FLAGS",
        length = 8,
        startBits = 3,
        endBits = 6
    )
    private int sourceChannel; // 0-15: 来源渠道（APP、网页、小程序等）
    
    @DsCompositeField(
        name = "isGift",
        group = "ORDER_FLAGS",
        length = 8,
        startBits = 7,
        endBits = 7
    )
    private boolean isGift;
    
    @DsCompositeField(
        name = "needInvoice",
        group = "ORDER_FLAGS",
        length = 8,
        startBits = 8,
        endBits = 8
    )
    private boolean needInvoice;
    
    // 普通列存储字段
    @DsField(name = "orderNo", length = 32)
    private String orderNo;
    
    @DsField(name = "userId", length = 8)
    private Long userId;
    
    @DsField(name = "totalAmount", length = 8, precision = 18, scale = 2)
    private BigDecimal totalAmount;
    
    @DsField(name = "discountAmount", length = 8, precision = 18, scale = 2)
    private BigDecimal discountAmount;
    
    @DsField(name = "actualAmount", length = 8, precision = 18, scale = 2)
    private BigDecimal actualAmount;
    
    @DsField(name = "createTime", length = 8)
    private Date createTime;
    
    @DsField(name = "payTime", length = 8)
    private Date payTime;
    
    @DsField(name = "shipTime", length = 8)
    private Date shipTime;
    
    @DsField(name = "completeTime", length = 8)
    private Date completeTime;
    
    @DsField(name = "itemCount", length = 4)
    private Integer itemCount;
    
    @DsField(name = "shippingAddress", length = 256)
    private String shippingAddress;
    
   
    @DsField(name = "remark", length = 512)
    private String remark;
    
    // Getters and Setters
    
    public int getOrderStatus() {
        return orderStatus;
    }
    
    public void setOrderStatus(int orderStatus) {
        this.orderStatus = orderStatus;
    }
    
    public int getPaymentStatus() {
        return paymentStatus;
    }
    
    public void setPaymentStatus(int paymentStatus) {
        this.paymentStatus = paymentStatus;
    }
    
    public int getShippingStatus() {
        return shippingStatus;
    }
    
    public void setShippingStatus(int shippingStatus) {
        this.shippingStatus = shippingStatus;
    }
    
    public boolean isPaid() {
        return isPaid;
    }
    
    public void setPaid(boolean paid) {
        isPaid = paid;
    }
    
    public boolean isRefunded() {
        return isRefunded;
    }
    
    public void setRefunded(boolean refunded) {
        isRefunded = refunded;
    }
    
    public boolean isCancelled() {
        return isCancelled;
    }
    
    public void setCancelled(boolean cancelled) {
        isCancelled = cancelled;
    }
    
    public int getPriority() {
        return priority;
    }
    
    public void setPriority(int priority) {
        this.priority = priority;
    }
    
    public int getSourceChannel() {
        return sourceChannel;
    }
    
    public void setSourceChannel(int sourceChannel) {
        this.sourceChannel = sourceChannel;
    }
    
    public boolean isGift() {
        return isGift;
    }
    
    public void setGift(boolean gift) {
        isGift = gift;
    }
    
    public boolean isNeedInvoice() {
        return needInvoice;
    }
    
    public void setNeedInvoice(boolean needInvoice) {
        this.needInvoice = needInvoice;
    }
    
    public String getOrderNo() {
        return orderNo;
    }
    
    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }
    
    public Long getUserId() {
        return userId;
    }
    
    public void setUserId(Long userId) {
        this.userId = userId;
    }
    
    public BigDecimal getTotalAmount() {
        return totalAmount;
    }
    
    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }
    
    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }
    
    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }
    
    public BigDecimal getActualAmount() {
        return actualAmount;
    }
    
    public void setActualAmount(BigDecimal actualAmount) {
        this.actualAmount = actualAmount;
    }
    
    public Date getCreateTime() {
        return createTime;
    }
    
    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
    
    public Date getPayTime() {
        return payTime;
    }
    
    public void setPayTime(Date payTime) {
        this.payTime = payTime;
    }
    
    public Date getShipTime() {
        return shipTime;
    }
    
    public void setShipTime(Date shipTime) {
        this.shipTime = shipTime;
    }
    
    public Date getCompleteTime() {
        return completeTime;
    }
    
    public void setCompleteTime(Date completeTime) {
        this.completeTime = completeTime;
    }
    
    public Integer getItemCount() {
        return itemCount;
    }
    
    public void setItemCount(Integer itemCount) {
        this.itemCount = itemCount;
    }
    
    public String getShippingAddress() {
        return shippingAddress;
    }
    
    public void setShippingAddress(String shippingAddress) {
        this.shippingAddress = shippingAddress;
    }
    
    public String getRemark() {
        return remark;
    }
    
    public void setRemark(String remark) {
        this.remark = remark;
    }
    
    @Override
    public String toString() {
        return "OrderEntity{" +
                "id=" + getId() +
                ", orderNo='" + orderNo + '\'' +
                ", userId=" + userId +
                ", orderStatus=" + orderStatus +
                ", paymentStatus=" + paymentStatus +
                ", shippingStatus=" + shippingStatus +
                ", isPaid=" + isPaid +
                ", isRefunded=" + isRefunded +
                ", isCancelled=" + isCancelled +
                ", priority=" + priority +
                ", sourceChannel=" + sourceChannel +
                ", isGift=" + isGift +
                ", needInvoice=" + needInvoice +
                ", totalAmount=" + totalAmount +
                ", discountAmount=" + discountAmount +
                ", actualAmount=" + actualAmount +
                ", itemCount=" + itemCount +
                ", createTime=" + createTime +
                ", payTime=" + payTime +
                '}';
    }
}
