package org.training.transactions.service.implementation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.training.transactions.configuration.BalanceSyncProducer;
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
import org.training.transactions.model.response.Response;
import org.training.transactions.model.response.TransactionRequest;
import org.training.transactions.repository.JournalEntryRepository;
import org.training.transactions.repository.TransactionRepository;
import org.training.transactions.service.TransactionService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 复式记账服务实现 — running_balance 增量追加 + RabbitMQ 异步余额同步
 * <p>
 * 设计原则:
 * 1. 分录（journal_entry）是唯一真相源，余额从分录派生
 * 2. running_balance 增量追加: 每笔分录写时带上该账户当时的余额快照
 *    — O(1) 查余额（读最后一条分录）
 *    — O(n) SUM 保留用于对比测试
 * 3. 余额检查: 优先读 running_balance，旧数据无 running_balance 时回退 SUM
 * 4. 事务提交后发 RabbitMQ 异步同步余额到 Account-Service
 * 5. 并发安全靠 Redisson 锁，幂等靠 referenceId UK
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepository transactionRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final AccountService accountService;
    private final DistributedLockService lockService;
    private final BalanceSyncProducer syncProducer;

    @Lazy
    @Autowired
    private TransactionServiceImpl self;

    @Value("${spring.application.ok}")
    private String ok;

    private static final DateTimeFormatter VOUCHER_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // ==================== 公开 API ====================

    @Override
    public Response addTransaction(TransactionDto transactionDto) {
        var response = accountService.readByAccountNumber(transactionDto.getAccountId());
        if (Objects.isNull(response.getBody())) {
            throw new ResourceNotFound("Requested account not found on the server", GlobalErrorCode.NOT_FOUND);
        }

        boolean isWithdrawal = transactionDto.getTransactionType().equals(TransactionType.WITHDRAWAL.toString());

        if (isWithdrawal) {
            if (!"ACTIVE".equals(response.getBody().getAccountStatus())) {
                throw new AccountStatusException("account is inactive or closed");
            }

            String lockKey = "withdraw:" + transactionDto.getAccountId();
            return lockService.executeWithLock(lockKey, 10, 30, () -> {
                // 锁内读余额（优先 running_balance，旧数据回退 SUM）
                BigDecimal balance = getBalanceForCheck(transactionDto.getAccountId());
                if (balance == null || balance.compareTo(transactionDto.getAmount()) < 0) {
                    throw new InsufficientBalance("Insufficient balance in the account");
                }

                Response resp = self.doBookkeeping(transactionDto);
                syncProducer.sendBalanceSync(transactionDto.getAccountId());
                return resp;
            });
        }

        Response resp = self.doBookkeeping(transactionDto);
        syncProducer.sendBalanceSync(transactionDto.getAccountId());
        return resp;
    }

    @Override
    public Response internalTransaction(List<TransactionDto> transactionDtos, String transactionReference) {
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

        Response resp = lockService.executeWithMultiLock(accountIds, 10, 30, () -> {
            for (TransactionDto dto : transactionDtos) {
                if (dto.getDirection() == Direction.DEBIT) {
                    BigDecimal balance = getBalanceForCheck(dto.getAccountId());
                    if (balance == null || balance.compareTo(dto.getAmount()) < 0) {
                        throw new InsufficientBalance("Insufficient balance for account: " + dto.getAccountId());
                    }
                }
            }
            return self.doInternalTransfer(transactionDtos, transactionReference);
        });

        accountIds.forEach(syncProducer::sendBalanceSync);
        return resp;
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
        // O(1) 优先: 读最后一条分录的 running_balance
        BigDecimal runningBalance = journalEntryRepository.findTopByAccountIdOrderByIdDesc(accountId)
                .map(JournalEntry::getRunningBalance)
                .orElse(null);
        if (runningBalance != null) {
            return runningBalance;
        }
        // 旧数据回退 O(n) SUM
        BigDecimal sum = journalEntryRepository.getAccountBalance(accountId);
        return sum != null ? sum : BigDecimal.ZERO;
    }

    // ==================== @Transactional 门面 ====================

    @Transactional
    public Response doBookkeeping(TransactionDto dto) {
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

        Map<String, BigDecimal> rbCache = new HashMap<>();
        List<JournalEntry> entries = buildEntriesForSingleTx(voucher.getId(), dto, rbCache);
        journalEntryRepository.saveAll(entries);

        validateBalance(voucher.getId());

        log.info("凭证 {} 记账完成 | {} | 分录数={}", voucherNo, dto.getTransactionType(), entries.size());

        return Response.builder()
                .responseCode(ok)
                .message("Transaction completed successfully").build();
    }

    @Transactional
    public Response doInternalTransfer(List<TransactionDto> transactionDtos, String transactionReference) {
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

        Map<String, BigDecimal> rbCache = new HashMap<>();
        List<JournalEntry> entries = new ArrayList<>();
        for (TransactionDto dto : transactionDtos) {
            entries.add(buildEntry(voucher.getId(), dto, rbCache));
        }
        journalEntryRepository.saveAll(entries);

        validateBalance(voucher.getId());

        log.info("凭证 {} 内部转账完成 | reference={} | 分录数={}", voucherNo, transactionReference, entries.size());

        return Response.builder()
                .responseCode(ok)
                .message("Transaction completed successfully").build();
    }

    // ==================== 私有方法 ====================

    /** 余额检查 — 优先 running_balance，旧数据无 running_balance 时回退 SUM */
    private BigDecimal getBalanceForCheck(String accountId) {
        BigDecimal rb = journalEntryRepository.findTopByAccountIdOrderByIdDesc(accountId)
                .map(JournalEntry::getRunningBalance)
                .orElse(null);
        if (rb != null) {
            return rb;
        }
        BigDecimal sum = journalEntryRepository.getAccountBalance(accountId);
        return sum != null ? sum : BigDecimal.ZERO;
    }

    /**
     * 计算 running_balance — 带本地缓存，同批次同账户多次写入时复用。
     * <p>
     * 1. 先查缓存（同事务内已写入的同账户分录）
     * 2. 缓存无 → 读 DB: findTopByAccountIdOrderByIdDesc → runningBalance
     * 3. DB 无 runningBalance（旧数据）→ 回退 SUM
     * 4. 计算新余额: CREDIT 加, DEBIT 减
     * 5. 更新缓存
     */
    private BigDecimal nextRunningBalance(String accountId, Direction dir, BigDecimal amount,
                                          Map<String, BigDecimal> cache) {
        BigDecimal lastBalance = cache.get(accountId);
        if (lastBalance == null) {
            lastBalance = journalEntryRepository.findTopByAccountIdOrderByIdDesc(accountId)
                    .map(JournalEntry::getRunningBalance)
                    .orElse(null);
            if (lastBalance == null) {
                // 旧数据无 running_balance → 回退 SUM
                lastBalance = journalEntryRepository.getAccountBalance(accountId);
                if (lastBalance == null) {
                    lastBalance = BigDecimal.ZERO;
                }
            }
        }

        BigDecimal newBalance;
        if (dir == Direction.CREDIT) {
            newBalance = lastBalance.add(amount);
        } else {
            newBalance = lastBalance.subtract(amount);
        }

        cache.put(accountId, newBalance);
        return newBalance;
    }

    private List<JournalEntry> buildEntriesForSingleTx(Long voucherId, TransactionDto dto,
                                                        Map<String, BigDecimal> rbCache) {
        String cashAccount = "9999999999999";
        List<JournalEntry> entries = new ArrayList<>();

        if (TransactionType.DEPOSIT.toString().equals(dto.getTransactionType())) {
            entries.add(buildEntry(voucherId, dto.getAccountId(), Direction.CREDIT,
                    dto.getAmount(), dto.getDescription(), rbCache));
            entries.add(buildEntry(voucherId, cashAccount, Direction.DEBIT, dto.getAmount(),
                    "银行现金入库", rbCache));
        } else if (TransactionType.WITHDRAWAL.toString().equals(dto.getTransactionType())) {
            entries.add(buildEntry(voucherId, dto.getAccountId(), Direction.DEBIT,
                    dto.getAmount(), dto.getDescription(), rbCache));
            entries.add(buildEntry(voucherId, cashAccount, Direction.CREDIT, dto.getAmount(),
                    "银行现金出库", rbCache));
        }
        return entries;
    }

    /** 用 DTO 构建分录 */
    private JournalEntry buildEntry(Long voucherId, TransactionDto dto, Map<String, BigDecimal> rbCache) {
        Direction dir = dto.getDirection() != null ? dto.getDirection() : Direction.DEBIT;
        return buildEntry(voucherId, dto.getAccountId(), dir, dto.getAmount(), dto.getDescription(), rbCache);
    }

    /** 构建单条分录，含 running_balance */
    private JournalEntry buildEntry(Long voucherId, String accountId, Direction dir, BigDecimal amount,
                                     String desc, Map<String, BigDecimal> rbCache) {
        BigDecimal runningBalance = nextRunningBalance(accountId, dir, amount, rbCache);
        return JournalEntry.builder()
                .transactionId(voucherId)
                .accountId(accountId)
                .direction(dir)
                .amount(amount)
                .runningBalance(runningBalance)
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
