package org.training.transactions.model;

/**
 * 借贷方向 — 负债视角（核心银行模型）
 * <p>
 * 用户存款是银行对用户的负债，遵循负债类科目规则:
 * - DEBIT  (借): 负债减少 = 客户取款/转出，银行欠客户的钱变少
 * - CREDIT (贷): 负债增加 = 客户存款/转入，银行欠客户的钱变多
 * <p>
 * 每笔凭证的借方总额必须等于贷方总额。
 * <p>
 * 示例 — 转账 500 从 Acc-A 到 Acc-B:
 *   Acc-A: DEBIT  500 (负债减少 — 银行不再欠 Acc-A 这 500)
 *   Acc-B: CREDIT 500 (负债增加 — 银行现在欠 Acc-B 这 500)
 *   内部现金科目: CREDIT 500 & DEBIT 500 (资金池内部轧差，净额为零)
 */
public enum Direction {
    DEBIT, CREDIT
}
