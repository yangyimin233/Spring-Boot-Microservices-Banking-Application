package org.training.transactions.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.training.transactions.model.Direction;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TransactionDto {

    /** 账户编号 */
    private String accountId;

    /** 交易类型 */
    private String transactionType;

    /** 借贷方向 */
    private Direction direction;

    /** 金额（正数） */
    private BigDecimal amount;

    /** 摘要说明 */
    private String description;

    /** 幂等键（可选），由调用方传入。同一 referenceId 的重复请求直接返回已有结果 */
    private String referenceId;
}
