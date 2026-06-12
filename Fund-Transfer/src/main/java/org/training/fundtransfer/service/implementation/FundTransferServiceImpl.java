package org.training.fundtransfer.service.implementation;

import io.seata.spring.annotation.GlobalTransactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.training.fundtransfer.annotation.ServiceSwitch;
import org.training.fundtransfer.exception.AccountUpdateException;
import org.training.fundtransfer.exception.GlobalErrorCode;
import org.training.fundtransfer.exception.InsufficientBalance;
import org.training.fundtransfer.exception.ResourceNotFound;
import org.training.fundtransfer.external.AccountService;
import org.training.fundtransfer.external.TransactionService;
import org.training.fundtransfer.model.mapper.FundTransferMapper;
import org.training.fundtransfer.model.TransactionStatus;
import org.training.fundtransfer.model.TransferType;
import org.training.fundtransfer.model.dto.Account;
import org.training.fundtransfer.model.dto.FundTransferDto;
import org.training.fundtransfer.model.dto.Transaction;
import org.training.fundtransfer.model.dto.request.FundTransferRequest;
import org.training.fundtransfer.model.dto.response.FundTransferResponse;
import org.training.fundtransfer.model.entity.FundTransfer;
import org.training.fundtransfer.repository.FundTransferRepository;
import org.training.fundtransfer.service.FundTransferService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FundTransferServiceImpl implements FundTransferService {

    private final AccountService accountService;
    private final FundTransferRepository fundTransferRepository;
    private final TransactionService transactionService;

    private final FundTransferMapper fundTransferMapper = new FundTransferMapper();

    /**
     * Transfers funds from one account to another with compensating transaction support.
     *
     * If any step fails after the from-account has been debited,
     * a compensating action reverses the debit to prevent money loss.
     */
    @Override
    @GlobalTransactional(name = "fund-transfer-global-tx", rollbackFor = Exception.class)
    @ServiceSwitch("service.switch.fundTransfer")
    public FundTransferResponse fundTransfer(FundTransferRequest fundTransferRequest) {

        // Load and validate fromAccount (Feign → account-service, Sentinel 自动保护)
        ResponseEntity<Account> response = accountService.readByAccountNumber(fundTransferRequest.getFromAccount());
        if (Objects.isNull(response.getBody())) {
            log.error("requested account {} is not found on the server", fundTransferRequest.getFromAccount());
            throw new ResourceNotFound("requested account not found on the server", GlobalErrorCode.NOT_FOUND);
        }
        Account fromAccount = response.getBody();

        if (!"ACTIVE".equals(fromAccount.getAccountStatus())) {
            log.error("account status is pending or inactive");
            throw new AccountUpdateException("account status is not active", GlobalErrorCode.NOT_ACCEPTABLE);
        }
        if (fromAccount.getAvailableBalance().compareTo(fundTransferRequest.getAmount()) < 0) {
            log.error("required amount to transfer is not available");
            throw new InsufficientBalance("requested amount is not available", GlobalErrorCode.NOT_ACCEPTABLE);
        }

        // Load and validate toAccount (Feign → account-service, Sentinel 自动保护)
        response = accountService.readByAccountNumber(fundTransferRequest.getToAccount());
        if (Objects.isNull(response.getBody())) {
            log.error("requested account {} is not found on the server", fundTransferRequest.getToAccount());
            throw new ResourceNotFound("requested account not found on the server", GlobalErrorCode.NOT_FOUND);
        }
        Account toAccount = response.getBody();

        String transactionId = internalTransfer(fromAccount, toAccount, fundTransferRequest.getAmount());

        FundTransfer fundTransfer = FundTransfer.builder()
                .transferType(TransferType.INTERNAL)
                .amount(fundTransferRequest.getAmount())
                .fromAccount(fromAccount.getAccountNumber())
                .transactionReference(transactionId)
                .status(TransactionStatus.SUCCESS)
                .toAccount(toAccount.getAccountNumber()).build();

        fundTransferRepository.save(fundTransfer);
        return FundTransferResponse.builder()
                .transactionId(transactionId)
                .message("Fund transfer was successful").build();
    }

    /**
     * 复式记账转账: 先创建借贷分录，再从分录表重算余额
     * <p>
     * 分录模型:
     *   fromAccount: CREDIT (贷) — 资金转出，资产减少
     *   toAccount:   DEBIT  (借) — 资金转入，资产增加
     * <p>
     * Saga 补偿: 如果分录创建后余额重算失败，记录异常日志
     * (由于余额从分录派生，分录本身已提交，余额重算为重试安全操作)
     */
    private String internalTransfer(Account fromAccount, Account toAccount, BigDecimal amount) {
        String transactionReference = UUID.randomUUID().toString();

        try {
            // Step 1: 创建复式记账分录 (借贷自动平衡)
            List<Transaction> entries = List.of(
                    Transaction.builder()
                            .accountId(fromAccount.getAccountNumber())
                            .transactionType("INTERNAL_TRANSFER")
                            .direction("CREDIT")    // 资金转出 = 贷方
                            .amount(amount)
                            .description("转账至 " + toAccount.getAccountNumber())
                            .build(),
                    Transaction.builder()
                            .accountId(toAccount.getAccountNumber())
                            .transactionType("INTERNAL_TRANSFER")
                            .direction("DEBIT")     // 资金转入 = 借方
                            .amount(amount)
                            .description("来自 " + fromAccount.getAccountNumber() + " 的转账")
                            .build());

            transactionService.makeInternalTransactions(entries, transactionReference);
            log.info("分录已创建: reference={}, DEBIT={} = CREDIT={}", transactionReference, amount, amount);

            // Step 2: 从分录表重算并刷新余额缓存
            accountService.recalculateBalance(fromAccount.getAccountNumber());
            accountService.recalculateBalance(toAccount.getAccountNumber());
            log.info("余额已重算: from={}, to={}", fromAccount.getAccountNumber(), toAccount.getAccountNumber());

            return transactionReference;

        } catch (Exception e) {
            log.error("转账失败: reference={}, from={}, to={}, error={}",
                    transactionReference, fromAccount.getAccountNumber(), toAccount.getAccountNumber(), e.getMessage());
            throw new RuntimeException("Fund transfer failed", e);
        }
    }

    @Override
    public FundTransferDto getTransferDetailsFromReferenceId(String referenceId) {
        return fundTransferRepository.findFundTransferByTransactionReference(referenceId)
                .map(fundTransferMapper::convertToDto)
                .orElseThrow(() -> new ResourceNotFound("Fund transfer not found", GlobalErrorCode.NOT_FOUND));
    }

    @Override
    public List<FundTransferDto> getAllTransfersByAccountId(String accountId) {
        return fundTransferMapper.convertToDtoList(fundTransferRepository.findFundTransferByFromAccount(accountId));
    }
}
