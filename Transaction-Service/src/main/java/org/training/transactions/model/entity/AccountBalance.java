package org.training.transactions.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 账户余额表 — 跟分录在同库同事务，FOR UPDATE 行锁兜底防超扣。
 * <p>
 * 与 Account-Service 的 availableBalance 的关系:
 * - 本表: 权威余额，跟分录原子更新，永远准确
 * - Account-Service: 展示缓存，MQ 异步刷新，最终一致
 */
@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AccountBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 账户编号（唯一） */
    @Column(nullable = false, unique = true, length = 32)
    private String accountId;

    /** 当前余额 */
    @Column(nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
