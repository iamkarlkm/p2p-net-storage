package com.q3lives.ds.example;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.annotation.DsCompositeField;
import com.q3lives.ds.annotation.DsField;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 用户实体示例
 */
public class UserEntity extends DsTableAdapter {
    
    // 复合列存储字段 - 状态标志位（共享8字节）
    @DsCompositeField(
        name = "isActive",
        group = "STATUS",
        length = 8,
        startBits = 0,
        endBits = 0
    )
    private boolean isActive;
    
    @DsCompositeField(
        name = "isVerified",
        group = "STATUS",
        length = 8,
        startBits = 1,
        endBits = 1
    )
    private boolean isVerified;
    
    @DsCompositeField(
        name = "userLevel",
        group = "STATUS",
        length = 8,
        startBits = 2,
        endBits = 5
    )
    private int userLevel; // 0-15级
    
    @DsCompositeField(
        name = "userType",
        group = "STATUS",
        length = 8,
        startBits = 6,
        endBits = 9
    )
    private int userType; // 0-15种类型
    
    // 普通列存储字段
    @DsField(name = "username", length = 64)
    private String username;
    
    @DsField(name = "email", length = 128)
    private String email;
    
    @DsField(name = "age", length = 4, min = 0)
    private Integer age;
    
    @DsField(name = "balance", length = 8, precision = 18, scale = 2)
    private BigDecimal balance;
    
    @DsField(name = "createTime", length = 8)
    private Date createTime;
    
    @DsField(name = "lastLoginTime", length = 8)
    private Date lastLoginTime;
    
    @DsField(name = "loginCount", length = 8)
    private Long loginCount;
    
    @DsField(name = "score", length = 8)
    private Double score;
    
    // Getters and Setters
    
    public boolean isActive() {
        return isActive;
    }
    
    public void setActive(boolean active) {
        isActive = active;
    }
    
    public boolean isVerified() {
        return isVerified;
    }
    
    public void setVerified(boolean verified) {
        isVerified = verified;
    }
    
    public int getUserLevel() {
        return userLevel;
    }
    
    public void setUserLevel(int userLevel) {
        this.userLevel = userLevel;
    }
    
    public int getUserType() {
        return userType;
    }
    
    public void setUserType(int userType) {
        this.userType = userType;
    }
    
    public String getUsername() {
        return username;
    }
    
    public void setUsername(String username) {
        this.username = username;
    }
    
    public String getEmail() {
        return email;
    }
    
    public void setEmail(String email) {
        this.email = email;
    }
    
    public Integer getAge() {
        return age;
    }
    
    public void setAge(Integer age) {
        this.age = age;
    }
    
    public BigDecimal getBalance() {
        return balance;
    }
    
    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }
    
    public Date getCreateTime() {
        return createTime;
    }
    
    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
    
    public Date getLastLoginTime() {
        return lastLoginTime;
    }
    
    public void setLastLoginTime(Date lastLoginTime) {
        this.lastLoginTime = lastLoginTime;
    }
    
    public Long getLoginCount() {
        return loginCount;
    }
    
    public void setLoginCount(Long loginCount) {
        this.loginCount = loginCount;
    }
    
    public Double getScore() {
        return score;
    }
    
    public void setScore(Double score) {
        this.score = score;
    }
    
    @Override
    public String toString() {
        return "UserEntity{" +
                "id=" + getId() +
                ", isActive=" + isActive +
                ", isVerified=" + isVerified +
                ", userLevel=" + userLevel +
                ", userType=" + userType +
                ", username='" + username + '\'' +
                ", email='" + email + '\'' +
                ", age=" + age +
                ", balance=" + balance +
                ", createTime=" + createTime +
                ", lastLoginTime=" + lastLoginTime +
                ", loginCount=" + loginCount +
                ", score=" + score +
                '}';
    }
}
