package org.training.fundtransfer.service.implementation;

import io.seata.spring.annotation.GlobalTransactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
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
     * Transfers funds with compensating transaction pattern (Saga).
     * Step 1: Debit fromAccount via balance endpoint
     * Step 2: Credit toAccount via balance endpoint
     * Step 3: Record both transaction entries
     *
     * If step 2 or 3 fails after step 1 succeeded, the debit is REVERSED.
     */
    private String internalTransfer(Account fromAccount, Account toAccount, BigDecimal amount) {
        BigDecimal originalFromBalance = fromAccount.getAvailableBalance();
        BigDecimal originalToBalance = toAccount.getAvailableBalance();
        boolean fromDebited = false;
        boolean toCredited = false;

        try {
            // Step 1: Debit fromAccount
            BigDecimal newFromBalance = fromAccount.getAvailableBalance().subtract(amount);
            accountService.updateBalance(fromAccount.getAccountNumber(), java.util.Map.of("balance", newFromBalance));
            fromDebited = true;
            log.info("Debited {} from account {}", amount, fromAccount.getAccountNumber());

            // Step 2: Credit toAccount
            BigDecimal newToBalance = toAccount.getAvailableBalance().add(amount);
            accountService.updateBalance(toAccount.getAccountNumber(), java.util.Map.of("balance", newToBalance));
            toCredited = true;
            log.info("Credited {} to account {}", amount, toAccount.getAccountNumber());

            // Step 3: Record transactions
            String transactionReference = UUID.randomUUID().toString();
            List<Transaction> transactions = List.of(
                    Transaction.builder()
                            .accountId(fromAccount.getAccountNumber())
                            .transactionType("INTERNAL_TRANSFER")
                            .amount(amount.negate())
                            .description("Internal fund transfer from " + fromAccount.getAccountNumber()
                                    + " to " + toAccount.getAccountNumber())
                            .build(),
                    Transaction.builder()
                            .accountId(toAccount.getAccountNumber())
                            .transactionType("INTERNAL_TRANSFER")
                            .amount(amount)
                            .description("Internal fund transfer received from: " + fromAccount.getAccountNumber())
                            .build());

            transactionService.makeInternalTransactions(transactions, transactionReference);
            return transactionReference;

        } catch (Exception e) {
            log.error("Fund transfer failed at step (debited={}, credited={}): {}", fromDebited, toCredited, e.getMessage());

            // Compensating action: if fromAccount was debited but toAccount wasn't credited, reverse the debit
            if (fromDebited && !toCredited) {
                try {
                    log.warn("COMPENSATION: Reversing debit of {} from account {}", amount, fromAccount.getAccountNumber());
                    accountService.updateBalance(fromAccount.getAccountNumber(), java.util.Map.of("balance", originalFromBalance));
                    log.info("COMPENSATION SUCCESS: Debit reversed for account {}", fromAccount.getAccountNumber());
                } catch (Exception compEx) {
                    log.error("CRITICAL: Compensation failed for account {}. Manual intervention required! " +
                                    "Original balance: {}, Amount: {}",
                            fromAccount.getAccountNumber(), originalFromBalance, amount, compEx);
                }
            }

            // If toAccount was credited but transaction recording failed, reverse the credit too
            if (toCredited) {
                try {
                    log.warn("COMPENSATION: Reversing credit of {} from account {}", amount, toAccount.getAccountNumber());
                    accountService.updateBalance(toAccount.getAccountNumber(), java.util.Map.of("balance", originalToBalance));
                    log.info("COMPENSATION SUCCESS: Credit reversed for account {}", toAccount.getAccountNumber());
                } catch (Exception compEx) {
                    log.error("CRITICAL: Credit compensation failed for account {}. Manual intervention required!",
                            toAccount.getAccountNumber(), compEx);
                }
            }

            throw new RuntimeException("Fund transfer failed and was compensated", e);
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
