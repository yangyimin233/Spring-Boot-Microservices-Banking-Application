package org.training.fundtransfer.external;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.training.fundtransfer.configuration.FeignClientConfiguration;
import org.training.fundtransfer.model.dto.Account;
import org.training.fundtransfer.model.dto.response.Response;

import java.math.BigDecimal;
import java.util.Map;

@FeignClient(name = "account-service", configuration = FeignClientConfiguration.class,
        fallback = org.training.fundtransfer.external.fallback.AccountServiceFallback.class)
public interface AccountService {

    @GetMapping("/accounts/internal")
    ResponseEntity<Account> readByAccountNumber(@RequestParam String accountNumber);

    @PutMapping("/accounts/internal/{accountNumber}")
    ResponseEntity<Response> updateAccount(@PathVariable String accountNumber, @RequestBody Account account);

    @PutMapping("/accounts/internal/{accountNumber}/balance")
    ResponseEntity<Response> updateBalance(@PathVariable String accountNumber, @RequestBody Map<String, BigDecimal> body);
}
