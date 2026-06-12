package org.training.transactions.model;

/**
 * 借贷方向
 * <p>
 * 复式记账核心约定:
 * - DEBIT  (借): 资产增加、负债减少
 * - CREDIT (贷): 资产减少、负债增加
 * <p>
 * 对银行账户而言:
 * - DEBIT  = 客户存入，银行欠客户的钱增加（负债增加）
 * - CREDIT = 客户取出，银行欠客户的钱减少（负债减少）
 * <p>
 * 每笔凭证的借方总额必须等于贷方总额。
 */
public enum Direction {
    DEBIT, CREDIT
}
