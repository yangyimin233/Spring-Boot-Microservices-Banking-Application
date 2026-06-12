package org.training.account.service.external;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.training.account.service.configuration.FeignConfiguration;
import org.training.account.service.model.dto.external.TransactionResponse;

import java.math.BigDecimal;
import java.util.List;

@FeignClient(name = "transaction-service", configuration = FeignConfiguration.class)
public interface TransactionService {

    @GetMapping("/transactions")
    List<TransactionResponse> getTransactionsFromAccountId(@RequestParam String accountId);

    /** 从分录表计算实时余额 */
    @GetMapping("/transactions/internal/balance")
    BigDecimal getAccountBalance(@RequestParam String accountId);
}
