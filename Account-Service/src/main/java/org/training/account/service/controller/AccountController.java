package org.training.account.service.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.training.account.service.external.UserService;
import org.training.account.service.model.dto.AccountDto;
import org.training.account.service.model.dto.AccountStatusUpdate;
import org.training.account.service.model.dto.response.Response;
import org.training.account.service.model.dto.external.TransactionResponse;
import org.training.account.service.service.AccountService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;
    private final UserService userService;

    @PostMapping
    public ResponseEntity<Response> createAccount(@RequestBody AccountDto accountDto) {
        return new ResponseEntity<>(accountService.createAccount(accountDto), HttpStatus.CREATED);
    }

    @PatchMapping
    public ResponseEntity<Response> updateAccountStatus(@RequestParam String accountNumber,
                                                         @RequestBody AccountStatusUpdate accountStatusUpdate) {
        verifyAccountOwnership(accountNumber);
        return ResponseEntity.ok(accountService.updateStatus(accountNumber, accountStatusUpdate));
    }

    @GetMapping
    public ResponseEntity<AccountDto> readByAccountNumber(@RequestParam String accountNumber) {
        verifyAccountOwnership(accountNumber);
        return ResponseEntity.ok(accountService.readAccountByAccountNumber(accountNumber));
    }

    @PutMapping
    public ResponseEntity<Response> updateAccount(@RequestParam String accountNumber,
                                                   @RequestBody AccountDto accountDto) {
        verifyAccountOwnership(accountNumber);
        return ResponseEntity.ok(accountService.updateAccount(accountNumber, accountDto));
    }

    @GetMapping("/balance")
    public ResponseEntity<String> accountBalance(@RequestParam String accountNumber) {
        verifyAccountOwnership(accountNumber);
        return ResponseEntity.ok(accountService.getBalance(accountNumber));
    }

    @GetMapping("/{accountId}/transactions")
    public ResponseEntity<List<TransactionResponse>> getTransactionsFromAccountId(@PathVariable String accountId) {
        verifyAccountOwnership(accountId);
        return ResponseEntity.ok(accountService.getTransactionsFromAccountId(accountId));
    }

    @PutMapping("/closure")
    public ResponseEntity<Response> closeAccount(@RequestParam String accountNumber) {
        verifyAccountOwnership(accountNumber);
        return ResponseEntity.ok(accountService.closeAccount(accountNumber));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<AccountDto> readAccountByUserId(@PathVariable Long userId) {
        AccountDto account = accountService.readAccountByUserId(userId);
        // Verify ownership: the requesting user must own this account
        String currentAuthId = getCurrentAuthId();
        var userResp = userService.readUserById(account.getUserId());
        if (userResp.getBody() == null || !currentAuthId.equals(userResp.getBody().getAuthId())) {
            throw new AccessDeniedException("access denied: account does not belong to you");
        }
        return ResponseEntity.ok(account);
    }

    // Internal endpoints for service-to-service calls (bypass auth)

    @GetMapping("/internal")
    public ResponseEntity<AccountDto> readByAccountNumberInternal(@RequestParam String accountNumber) {
        return ResponseEntity.ok(accountService.readAccountByAccountNumber(accountNumber));
    }

    @PutMapping("/internal/{accountNumber}")
    public ResponseEntity<Response> updateAccountInternal(@PathVariable String accountNumber,
                                                           @RequestBody AccountDto accountDto) {
        return ResponseEntity.ok(accountService.updateAccount(accountNumber, accountDto));
    }

    @PutMapping("/internal/{accountNumber}/balance")
    public ResponseEntity<Response> updateBalance(@PathVariable String accountNumber,
                                                   @RequestBody java.util.Map<String, java.math.BigDecimal> body) {
        return ResponseEntity.ok(accountService.updateBalance(accountNumber, body.get("balance")));
    }

    // Helper methods

    private String getCurrentAuthId() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private void verifyAccountOwnership(String accountNumber) {
        String currentAuthId = getCurrentAuthId();
        AccountDto account = accountService.readAccountByAccountNumber(accountNumber);
        var userResp = userService.readUserById(account.getUserId());
        if (userResp.getBody() == null || !currentAuthId.equals(userResp.getBody().getAuthId())) {
            throw new AccessDeniedException("access denied: account does not belong to you");
        }
    }
}
