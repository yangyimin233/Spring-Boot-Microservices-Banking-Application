package org.training.transactions.service;

import org.training.transactions.model.dto.TransactionDto;
import org.training.transactions.model.response.Response;
import org.training.transactions.model.response.TransactionRequest;

import java.math.BigDecimal;
import java.util.List;

public interface TransactionService {

    /**
     * 单笔记账（存款/取款） — 借贷都在同一凭证内
     */
    Response addTransaction(TransactionDto transactionDto);

    /**
     * 内部转账 — 多笔分录组成一个凭证，借贷自动平衡
     */
    Response internalTransaction(List<TransactionDto> transactionDtos, String transactionReference);

    /**
     * 查询某账户的流水
     */
    List<TransactionRequest> getTransaction(String accountId);

    /**
     * 按参照号查凭证
     */
    List<TransactionRequest> getTransactionByTransactionReference(String transactionReference);

    /**
     * 计算某账户的实时余额: SUM(DEBIT) - SUM(CREDIT)
     */
    BigDecimal getAccountBalance(String accountId);
}
