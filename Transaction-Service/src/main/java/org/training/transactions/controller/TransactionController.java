package org.training.transactions.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.training.transactions.model.dto.FundTransferRequest;
import org.training.transactions.model.dto.TransactionDto;
import org.training.transactions.model.dto.UserDto;
import org.training.transactions.model.external.Account;
import org.training.transactions.model.response.FundTransferResponse;
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
@Tag(name = "交易记账", description = "存款/取款/转账/流水查询")
public class TransactionController {

    private final TransactionService transactionService;
    private final AccountService accountService;
    private final UserService userService;
    private final JournalEntryRepository journalEntryRepository;

    @Operation(summary = "存款/取款", description = "单笔记账接口。存款无锁，取款走 Redisson + FOR UPDATE 双层锁。支持幂等键 referenceId。")
    @PostMapping
    public ResponseEntity<Response> addTransactions(@RequestBody TransactionDto transactionDto) {
        // Horizontal authorization: verify the requester owns the account
        verifyAccountOwnership(transactionDto.getAccountId());
        return new ResponseEntity<>(transactionService.addTransaction(transactionDto), HttpStatus.CREATED);
    }

    @Operation(summary = "内部记账", description = "服务间调用接口。调用方需自行构建借贷分录列表，传入 transactionReference 做幂等。")
    @PostMapping("/internal")
    public ResponseEntity<Response> makeInternalTransaction(
            @RequestBody List<TransactionDto> transactionDtos,
            @Parameter(description = "幂等键") @RequestParam String transactionReference) {
        return new ResponseEntity<>(transactionService.internalTransaction(transactionDtos, transactionReference), HttpStatus.CREATED);
    }

    @Operation(summary = "转账", description = "校验 from/to 账户 → 双层锁记账 → 保存转账日志。需水平鉴权。")
    @PostMapping("/fund-transfers")
    public ResponseEntity<FundTransferResponse> fundTransfer(@RequestBody FundTransferRequest request) {
        verifyAccountOwnership(request.getFromAccount());
        return ResponseEntity.ok(transactionService.fundTransfer(request));
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
