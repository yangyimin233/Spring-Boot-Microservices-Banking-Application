package org.training.transactions.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.training.transactions.exception.GlobalErrorCode;
import org.training.transactions.exception.ResourceNotFound;
import org.training.transactions.external.AccountService;
import org.training.transactions.external.UserService;
import org.training.transactions.model.dto.TransactionDto;
import org.training.transactions.model.dto.UserDto;
import org.training.transactions.model.external.Account;
import org.training.transactions.model.response.Response;
import org.training.transactions.model.response.TransactionRequest;
import org.training.transactions.repository.JournalEntryRepository;
import org.training.transactions.service.TransactionService;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionService transactionService;
    private final AccountService accountService;
    private final UserService userService;
    private final JournalEntryRepository journalEntryRepository;

    @PostMapping
    public ResponseEntity<Response> addTransactions(@RequestBody TransactionDto transactionDto) {
        // Horizontal authorization: verify the requester owns the account
        verifyAccountOwnership(transactionDto.getAccountId());
        return new ResponseEntity<>(transactionService.addTransaction(transactionDto), HttpStatus.CREATED);
    }

    @PostMapping("/internal")
    public ResponseEntity<Response> makeInternalTransaction(@RequestBody List<TransactionDto> transactionDtos,
                                                             @RequestParam String transactionReference) {
        return new ResponseEntity<>(transactionService.internalTransaction(transactionDtos, transactionReference), HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<List<TransactionRequest>> getTransactions(@RequestParam String accountId) {
        // Horizontal authorization: verify the requester owns the account
        verifyAccountOwnership(accountId);
        return new ResponseEntity<>(transactionService.getTransaction(accountId), HttpStatus.OK);
    }

    @GetMapping("/{referenceId}")
    public ResponseEntity<List<TransactionRequest>> getTransactionByTransactionReference(@PathVariable String referenceId) {
        return new ResponseEntity<>(transactionService.getTransactionByTransactionReference(referenceId), HttpStatus.OK);
    }

    /** 内部用: 查询某账户的实时余额（优先 running_balance O(1)） */
    @GetMapping("/internal/balance")
    public ResponseEntity<BigDecimal> getAccountBalance(@RequestParam String accountId) {
        return ResponseEntity.ok(transactionService.getAccountBalance(accountId));
    }

    /** 性能对比: running_balance(O1) vs SUM(On) */
    @GetMapping("/internal/balance/benchmark")
    public ResponseEntity<Map<String, Object>> benchmarkBalance(@RequestParam String accountId) {
        Map<String, Object> result = new LinkedHashMap<>();

        // O(1): running_balance
        long t1 = System.nanoTime();
        BigDecimal rb = transactionService.getAccountBalance(accountId);
        long t2 = System.nanoTime();

        // O(n): SUM
        long t3 = System.nanoTime();
        BigDecimal sum = journalEntryRepository.getAccountBalance(accountId);
        long t4 = System.nanoTime();

        long rbNs = t2 - t1;
        long sumNs = t4 - t3;

        result.put("accountId", accountId);
        result.put("runningBalance_value", rb);
        result.put("runningBalance_ns", rbNs);
        result.put("runningBalance_ms", String.format("%.3f", rbNs / 1_000_000.0));
        result.put("sum_value", sum);
        result.put("sum_ns", sumNs);
        result.put("sum_ms", String.format("%.3f", sumNs / 1_000_000.0));
        result.put("speedup", String.format("%.1fx", (double) sumNs / Math.max(rbNs, 1)));
        result.put("match", rb != null && rb.compareTo(sum) == 0);

        return ResponseEntity.ok(result);
    }

    // Helper

    private void verifyAccountOwnership(String accountId) {
        String currentAuthId = SecurityContextHolder.getContext().getAuthentication().getName();
        ResponseEntity<Account> accountResp = accountService.readByAccountNumber(accountId);
        if (Objects.isNull(accountResp.getBody())) {
            throw new ResourceNotFound("Account not found", GlobalErrorCode.NOT_FOUND);
        }
        ResponseEntity<UserDto> userResp = userService.readUserById(accountResp.getBody().getUserId());
        if (Objects.isNull(userResp.getBody()) || !currentAuthId.equals(userResp.getBody().getAuthId())) {
            throw new AccessDeniedException("Access denied: you do not own this account");
        }
    }
}
