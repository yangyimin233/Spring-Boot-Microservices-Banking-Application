package org.training.fundtransfer.external.fallback;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.training.fundtransfer.external.AccountService;
import org.training.fundtransfer.model.dto.Account;
import org.training.fundtransfer.model.dto.response.Response;

import java.math.BigDecimal;
import java.util.Map;

/**
 * AccountService Feign 的 Sentinel 降级实现
 * 当 account-service 被限流/熔断/不可用时走这里
 */
@Slf4j
@Component
public class AccountServiceFallback implements AccountService {

    @Override
    public ResponseEntity<Account> readByAccountNumber(String accountNumber) {
        log.error("[Sentinel Fallback] readByAccountNumber blocked, account: {}", accountNumber);
        throw new RuntimeException("账户服务繁忙，请稍后重试");
    }

    @Override
    public ResponseEntity<Response> updateAccount(String accountNumber, Account account) {
        log.error("[Sentinel Fallback] updateAccount blocked, account: {}", accountNumber);
        throw new RuntimeException("账户服务繁忙，请稍后重试");
    }

    @Override
    public ResponseEntity<Response> updateBalance(String accountNumber, Map<String, BigDecimal> body) {
        log.error("[Sentinel Fallback] updateBalance blocked, account: {}", accountNumber);
        throw new RuntimeException("账户服务繁忙，请稍后重试");
    }
}
