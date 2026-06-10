package org.training.fundtransfer.external.fallback;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.training.fundtransfer.external.TransactionService;
import org.training.fundtransfer.model.dto.Transaction;
import org.training.fundtransfer.model.dto.response.Response;

import java.util.List;

/**
 * TransactionService Feign 的 Sentinel 降级实现
 * 当 transaction-service 被限流/熔断/不可用时走这里
 */
@Slf4j
@Component
public class TransactionServiceFallback implements TransactionService {

    @Override
    public ResponseEntity<Response> makeTransaction(Transaction transaction) {
        log.error("[Sentinel Fallback] makeTransaction blocked");
        throw new RuntimeException("交易服务繁忙，请稍后重试");
    }

    @Override
    public ResponseEntity<Response> makeInternalTransactions(List<Transaction> transactions, String transactionReference) {
        log.error("[Sentinel Fallback] makeInternalTransactions blocked, reference: {}", transactionReference);
        throw new RuntimeException("交易服务繁忙，转账已回滚，请稍后重试");
    }
}
