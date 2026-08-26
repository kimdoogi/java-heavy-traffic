package com.example.coupon.coupon.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "coupon")
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private int totalQuantity;

    private int remainingQuantity;

    @Version
    private long version;   // db-optimistic 전략용 (E6). none 전략은 JdbcClient로 이 컬럼을 우회한다.

    private Instant createdAt;

    protected Coupon() {
    }

    public Coupon(String name, int totalQuantity) {
        this.name = name;
        this.totalQuantity = totalQuantity;
        this.remainingQuantity = totalQuantity;
        this.createdAt = Instant.now();
    }

    /** 재고가 남아 있으면 1 차감. 남아 있지 않으면 false (호출자가 SOLD_OUT 처리). */
    public boolean decrease() {
        if (remainingQuantity <= 0) {
            return false;
        }
        remainingQuantity--;
        return true;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getTotalQuantity() {
        return totalQuantity;
    }

    public int getRemainingQuantity() {
        return remainingQuantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
