package org.training.transactions.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.training.transactions.model.TransactionStatus;
import org.training.transactions.model.TransactionType;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 交易凭证（复式记账凭证头）
 * <p>
 * 一个凭证对应一笔业务（如转账、存款），包含多条分录行。
 * 所有分录的 DEBIT 总额必须等于 CREDIT 总额。
 */
@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 凭证号，格式 TX + yyyyMMdd + 序号，如 TX2026061200001 */
    @Column(unique = true, nullable = false)
    private String voucherNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType transactionType;

    /** 全局唯一业务 ID，关联跨服务调用链，兼做幂等键 */
    @Column(nullable = false, unique = true)
    private String referenceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
