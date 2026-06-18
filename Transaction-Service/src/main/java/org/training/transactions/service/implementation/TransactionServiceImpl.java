package org.training.transactions.service.implementation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.training.transactions.configuration.DistributedLockService;
import org.training.transactions.exception.AccountStatusException;
import org.training.transactions.exception.GlobalErrorCode;
import org.training.transactions.exception.InsufficientBalance;
import org.training.transactions.exception.ResourceNotFound;
import org.training.transactions.external.AccountService;
import org.training.transactions.model.Direction;
import org.training.transactions.model.TransactionStatus;
import org.training.transactions.model.TransactionType;
import org.training.transactions.model.dto.TransactionDto;
import org.training.transactions.model.entity.JournalEntry;
import org.training.transactions.model.entity.Transaction;
import org.training.transactions.model.external.Account;
import org.training.transactions.model.response.Response;
import org.training.transactions.model.response.TransactionRequest;
import org.training.transactions.repository.JournalEntryRepository;
import org.training.transactions.repository.TransactionRepository;
import org.training.transactions.service.TransactionService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 复式记账服务实现（简化版）
 * <p>
 * 设计原则:
 * 1. 分录（journal_entry）是唯一真相源，余额从分录派生
 * 2. 交易热路径只写分录，不做跨服务余额修改
 * 3. 余额同步（recalculateBalance）在事务提交后执行，失败不影响交易
 * 4. 并发安全靠 Redisson 分布式锁 + 锁内读 Account-Service 最新余额
 * 5. 接口幂等靠 referenceId 唯一约束
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepository transactionRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final AccountService accountService;
    private final DistributedLockService lockService;

    @Lazy
    @Autowired
    private TransactionServiceImpl self;

    @Value("${spring.application.ok}")
    private String ok;

    private static final DateTimeFormatter VOUCHER_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // ==================== 公开 API ====================

    @Override
    public Response addTransaction(TransactionDto transactionDto) {
        ResponseEntity<Account> response = accountService.readByAccountNumber(transactionDto.getAccountId());
        if (Objects.isNull(response.getBody())) {
            throw new ResourceNotFound("Requested account not found on the server", GlobalErrorCode.NOT_FOUND);
        }
        Account account = response.getBody();

        boolean isWithdrawal = transactionDto.getTransactionType().equals(TransactionType.WITHDRAWAL.toString());

        if (isWithdrawal) {
            if (!"ACTIVE".equals(account.getAccountStatus())) {
                throw new AccountStatusException("account is inactive or closed");
            }

            String lockKey = "withdraw:" + transactionDto.getAccountId();
            return lockService.executeWithLock(lockKey, 10, -1, () -> {
                // 锁内重读最新余额（Account-Service 单行读）
                ResponseEntity<Account> freshResp = accountService.readByAccountNumber(transactionDto.getAccountId());
                Account fresh = freshResp.getBody();
                if (fresh == null || fresh.getAvailableBalance() == null
                        || fresh.getAvailableBalance().compareTo(transactionDto.getAmount()) < 0) {
                    throw new InsufficientBalance("Insufficient balance in the account");
                }

                // @Transactional 写分录，返回时事务已提交
                Response resp = self.doBookkeeping(transactionDto);

                // 事务已提交，分录已落库 → 安全同步余额
                accountService.recalculateBalance(transactionDto.getAccountId());

                return resp;
            });
        }

        // 存款：无超扣风险，不锁
        Response resp = self.doBookkeeping(transactionDto);
        accountService.recalculateBalance(transactionDto.getAccountId());
        return resp;
    }

    @Override
    public Response internalTransaction(List<TransactionDto> transactionDtos, String transactionReference) {
        // 幂等快速检查（锁外）
        Optional<Transaction> existing = transactionRepository.findByReferenceId(transactionReference);
        if (existing.isPresent()) {
            log.info("内部转账幂等命中(锁外): referenceId={}", transactionReference);
            return Response.builder()
                    .responseCode(ok)
                    .message("Transaction completed successfully (idempotent)").build();
        }

        List<String> accountIds = transactionDtos.stream()
                .map(TransactionDto::getAccountId)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        return lockService.executeWithMultiLock(accountIds, 10, -1, () -> {
            // 锁内检查所有 DEBIT 账户余额
            for (TransactionDto dto : transactionDtos) {
                if (dto.getDirection() == Direction.DEBIT) {
                    ResponseEntity<Account> freshResp = accountService.readByAccountNumber(dto.getAccountId());
                    Account fresh = freshResp.getBody();
                    if (fresh == null || fresh.getAvailableBalance() == null
                            || fresh.getAvailableBalance().compareTo(dto.getAmount()) < 0) {
                        throw new InsufficientBalance("Insufficient balance for account: " + dto.getAccountId());
                    }
                }
            }

            // @Transactional 写分录，返回时事务已提交
            Response resp = self.doInternalTransfer(transactionDtos, transactionReference);

            // 事务已提交，安全同步所有账户余额
            for (String id : accountIds) {
                accountService.recalculateBalance(id);
            }

            return resp;
        });
    }

    @Override
    public List<TransactionRequest> getTransaction(String accountId) {
        return journalEntryRepository.findByAccountId(accountId)
                .stream().map(entry -> {
                    Transaction voucher = transactionRepository.findById(entry.getTransactionId()).orElse(null);
                    TransactionRequest req = new TransactionRequest();
                    req.setAccountId(entry.getAccountId());
                    req.setAmount(entry.getAmount());
                    req.setDirection(entry.getDirection().toString());
                    req.setComments(entry.getDescription());
                    if (voucher != null) {
                        req.setVoucherNo(voucher.getVoucherNo());
                        req.setReferenceId(voucher.getReferenceId());
                        req.setTransactionType(voucher.getTransactionType().toString());
                        req.setTransactionStatus(voucher.getStatus().toString());
                        req.setLocalDateTime(voucher.getCreatedAt());
                    }
                    return req;
                }).collect(Collectors.toList());
    }

    @Override
    public List<TransactionRequest> getTransactionByTransactionReference(String transactionReference) {
        Transaction voucher = transactionRepository.findTransactionByReferenceId(transactionReference)
                .orElseThrow(() -> new ResourceNotFound("Transaction not found", GlobalErrorCode.NOT_FOUND));

        return journalEntryRepository.findByTransactionId(voucher.getId())
                .stream().map(entry -> {
                    TransactionRequest req = new TransactionRequest();
                    req.setAccountId(entry.getAccountId());
                    req.setAmount(entry.getAmount());
                    req.setDirection(entry.getDirection().toString());
                    req.setComments(entry.getDescription());
                    req.setVoucherNo(voucher.getVoucherNo());
                    req.setReferenceId(voucher.getReferenceId());
                    req.setTransactionType(voucher.getTransactionType().toString());
                    req.setTransactionStatus(voucher.getStatus().toString());
                    req.setLocalDateTime(voucher.getCreatedAt());
                    return req;
                }).collect(Collectors.toList());
    }

    @Override
    public BigDecimal getAccountBalance(String accountId) {
        BigDecimal balance = journalEntryRepository.getAccountBalance(accountId);
        return balance != null ? balance : BigDecimal.ZERO;
    }

    // ==================== @Transactional 门面 ====================

    /**
     * 单笔记账（存款/取款共用）—— 只写分录，不做余额修改。
     * <p>
     * 调用方在事务提交后负责调 recalculateBalance 同步余额到 Account-Service。
     */
    @Transactional
    public Response doBookkeeping(TransactionDto dto) {
        // 幂等检查
        String referenceId = dto.getReferenceId();
        if (referenceId != null && !referenceId.isEmpty()) {
            Optional<Transaction> existing = transactionRepository.findByReferenceId(referenceId);
            if (existing.isPresent()) {
                log.info("记账幂等命中: referenceId={}, voucherNo={}", referenceId, existing.get().getVoucherNo());
                return Response.builder()
                        .responseCode(ok)
                        .message("Transaction completed successfully (idempotent)").build();
            }
        } else {
            referenceId = UUID.randomUUID().toString();
        }

        String voucherNo = generateVoucherNo();
        Transaction voucher = Transaction.builder()
                .voucherNo(voucherNo)
                .transactionType(TransactionType.valueOf(dto.getTransactionType()))
                .referenceId(referenceId)
                .status(TransactionStatus.COMPLETED)
                .build();
        transactionRepository.save(voucher);

        List<JournalEntry> entries = buildEntriesForSingleTx(voucher.getId(), dto);
        journalEntryRepository.saveAll(entries);

        validateBalance(voucher.getId());

        log.info("凭证 {} 记账完成 | {} | 分录数={}", voucherNo, dto.getTransactionType(), entries.size());

        return Response.builder()
                .responseCode(ok)
                .message("Transaction completed successfully").build();
    }

    /**
     * 内部转账记账 —— 只写分录，不做余额修改。
     * <p>
     * 调用方在事务提交后负责逐账户 recalculateBalance。
     */
    @Transactional
    public Response doInternalTransfer(List<TransactionDto> transactionDtos, String transactionReference) {
        // 幂等检查（事务内最终屏障）
        Optional<Transaction> existing = transactionRepository.findByReferenceId(transactionReference);
        if (existing.isPresent()) {
            log.info("内部转账幂等命中(事务内): referenceId={}", transactionReference);
            return Response.builder()
                    .responseCode(ok)
                    .message("Transaction completed successfully (idempotent)").build();
        }

        String voucherNo = generateVoucherNo();
        Transaction voucher = Transaction.builder()
                .voucherNo(voucherNo)
                .transactionType(TransactionType.INTERNAL_TRANSFER)
                .referenceId(transactionReference)
                .status(TransactionStatus.COMPLETED)
                .build();
        transactionRepository.save(voucher);

        List<JournalEntry> entries = new ArrayList<>();
        for (TransactionDto dto : transactionDtos) {
            entries.add(JournalEntry.builder()
                    .transactionId(voucher.getId())
                    .accountId(dto.getAccountId())
                    .direction(dto.getDirection() != null ? dto.getDirection() : Direction.DEBIT)
                    .amount(dto.getAmount())
                    .description(dto.getDescription())
                    .build());
        }
        journalEntryRepository.saveAll(entries);

        validateBalance(voucher.getId());

        log.info("凭证 {} 内部转账完成 | reference={} | 分录数={}", voucherNo, transactionReference, entries.size());

        return Response.builder()
                .responseCode(ok)
                .message("Transaction completed successfully").build();
    }

    // ==================== 私有方法 ====================

    private List<JournalEntry> buildEntriesForSingleTx(Long voucherId, TransactionDto dto) {
        String cashAccount = "9999999999999";
        List<JournalEntry> entries = new ArrayList<>();

        if (TransactionType.DEPOSIT.toString().equals(dto.getTransactionType())) {
            entries.add(buildEntry(voucherId, dto.getAccountId(), Direction.CREDIT, dto.getAmount(), dto.getDescription()));
            entries.add(buildEntry(voucherId, cashAccount, Direction.DEBIT, dto.getAmount(), "银行现金入库"));
        } else if (TransactionType.WITHDRAWAL.toString().equals(dto.getTransactionType())) {
            entries.add(buildEntry(voucherId, dto.getAccountId(), Direction.DEBIT, dto.getAmount(), dto.getDescription()));
            entries.add(buildEntry(voucherId, cashAccount, Direction.CREDIT, dto.getAmount(), "银行现金出库"));
        }
        return entries;
    }

    private JournalEntry buildEntry(Long voucherId, String accountId, Direction dir, BigDecimal amount, String desc) {
        return JournalEntry.builder()
                .transactionId(voucherId)
                .accountId(accountId)
                .direction(dir)
                .amount(amount)
                .description(desc)
                .build();
    }

    private String generateVoucherNo() {
        String date = LocalDate.now().format(VOUCHER_FMT);
        long count = transactionRepository.count() + 1;
        return "TX" + date + String.format("%06d", count);
    }

    private void validateBalance(Long transactionId) {
        List<JournalEntry> entries = journalEntryRepository.findByTransactionId(transactionId);
        BigDecimal debitSum = entries.stream()
                .filter(e -> e.getDirection() == Direction.DEBIT)
                .map(JournalEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditSum = entries.stream()
                .filter(e -> e.getDirection() == Direction.CREDIT)
                .map(JournalEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (debitSum.compareTo(creditSum) != 0) {
            log.error("借贷不平衡! 凭证ID={}, DEBIT={}, CREDIT={}", transactionId, debitSum, creditSum);
            throw new IllegalStateException("借贷不平衡: DEBIT=" + debitSum + ", CREDIT=" + creditSum);
        }
        log.debug("凭证 {} 借贷平衡: DEBIT={}, CREDIT={}", transactionId, debitSum, creditSum);
    }
}
