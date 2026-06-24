package org.training.transactions.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.training.transactions.model.Direction;

import javax.persistence.*;
import java.math.BigDecimal;

/**
 * 会计分录（复式记账分录行）
 * <p>
 * 每条分录对应一个账户的借贷变动。
 * 同一凭证下所有分录 DEBIT 总额 = CREDIT 总额。
 * <p>
 * 场景示例 — 转账 500 从 Acc-A 到 Acc-B:
 * <pre>
 *   分录1: Acc-A | CREDIT | 500.00 | 资金转出
 *   分录2: Acc-B | DEBIT  | 500.00 | 资金转入
 *   SUM(DEBIT)=500 == SUM(CREDIT)=500 ✅
 * </pre>
 */
@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属凭证 ID */
    @Column(nullable = false)
    private Long transactionId;

    /** 账户编号 */
    @Column(nullable = false)
    private String accountId;

    /** 借贷方向 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;

    /** 金额（始终正数） */
    @Column(nullable = false)
    private BigDecimal amount;

    /** 该笔分录后账户的余额快照（增量追加，用于 O(1) 查余额） */
    @Column(precision = 19, scale = 4)
    private BigDecimal runningBalance;

    /** 分录摘要 */
    private String description;
}
