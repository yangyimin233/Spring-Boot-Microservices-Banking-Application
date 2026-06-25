package org.training.transactions.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 日终对账日志
 */
@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ReconciliationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 账户编号 */
    @Column(nullable = false)
    private String accountId;

    /** 分录余额（真相） */
    @Column(precision = 19, scale = 4)
    private BigDecimal entryBalance;

    /** 缓存余额（Account-Service） */
    @Column(precision = 19, scale = 4)
    private BigDecimal cacheBalance;

    /** 差异 */
    @Column(precision = 19, scale = 4)
    private BigDecimal diff;

    /** FIXED / SKIPPED / ERROR */
    @Column(nullable = false)
    private String result;

    /** 备注 */
    private String remark;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
