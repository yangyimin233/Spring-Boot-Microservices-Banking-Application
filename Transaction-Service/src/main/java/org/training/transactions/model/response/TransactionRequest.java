package org.training.transactions.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.training.transactions.model.Direction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TransactionRequest {

    /** 凭证号 */
    private String voucherNo;

    /** 参照号（关联跨服务调用） */
    private String referenceId;

    /** 账户编号 */
    private String accountId;

    /** 交易类型 */
    private String transactionType;

    /** 借贷方向 */
    private String direction;

    /** 金额 */
    private BigDecimal amount;

    /** 交易时间 */
    private LocalDateTime localDateTime;

    /** 状态 */
    private String transactionStatus;

    /** 摘要 */
    private String comments;
}
