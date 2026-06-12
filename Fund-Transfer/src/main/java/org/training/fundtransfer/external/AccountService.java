package org.training.fundtransfer.external;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.training.fundtransfer.configuration.FeignClientConfiguration;
import org.training.fundtransfer.model.dto.Account;
import org.training.fundtransfer.model.dto.response.Response;

@FeignClient(name = "account-service", configuration = FeignClientConfiguration.class,
        fallback = org.training.fundtransfer.external.fallback.AccountServiceFallback.class)
public interface AccountService {

    @GetMapping("/accounts/internal")
    ResponseEntity<Account> readByAccountNumber(@RequestParam String accountNumber);

    @PutMapping("/accounts/internal/{accountNumber}")
    ResponseEntity<Response> updateAccount(@PathVariable String accountNumber, @RequestBody Account account);

    /** 从分录表重算余额（取代直接改值的 updateBalance） */
    @PutMapping("/accounts/internal/{accountNumber}/recalculate")
    ResponseEntity<Response> recalculateBalance(@PathVariable String accountNumber);
}
