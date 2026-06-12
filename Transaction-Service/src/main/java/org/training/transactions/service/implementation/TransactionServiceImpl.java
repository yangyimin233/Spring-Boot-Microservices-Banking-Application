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
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 复式记账服务实现 — Redisson 分布式锁版本
 * <p>
 * 核心原则:
 * 1. 每笔业务 = 一个凭证(Transaction) + N 条分录(JournalEntry)
 * 2. 有借必有贷，借贷必相等
 * 3. 余额 = SUM(DEBIT) - SUM(CREDIT)，由分录计算，不直接修改
 * <p>
 * 并发控制:
 * - 取款: Redisson 分布式锁保护 "查余额 → 记账 → 提交" 全流程
 * - 转账: 多账户排序加锁(RedissonMultiLock)，防止死锁
 * - 存款: 无超扣风险，不加锁
 * <p>
 * 锁与事务的关系（分步实施锁）:
 * 外门方法(无事务) → 获取锁 → 调用代理的 @Transactional 方法 → 事务提交 → finally 释放锁
 * 保证锁释放时 DB 已提交，其他线程能读到最新余额。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepository transactionRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final AccountService accountService;
    private final DistributedLockService lockService;

    /**
     * 自注入代理，使 @Transactional 自调用能经过 AOP 代理
     * 锁外层调用 → self.doXxx() → 事务生效
     */
    @Lazy
    @Autowired
    private TransactionServiceImpl self;

    @Value("${spring.application.ok}")
    private String ok;

    private static final DateTimeFormatter VOUCHER_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // ==================== 公开 API ====================

    /**
     * 单笔记账（存款/取款）
     * <p>
     * 分步锁:
     * 1. 加载账户信息（锁外）
     * 2. 校验账户状态（锁外）
     * 3. [取款] 获取 Redisson 分布式锁 → 查余额 → 记账 → 提交事务 → 释放锁
     * 4. [存款] 直接记账，无需锁
     */
    @Override
    public Response addTransaction(TransactionDto transactionDto) {
        // Step 1: 加载账户（锁外，减少锁持有时间）
        ResponseEntity<Account> response = accountService.readByAccountNumber(transactionDto.getAccountId());
        if (Objects.isNull(response.getBody())) {
            throw new ResourceNotFound("Requested account not found on the server", GlobalErrorCode.NOT_FOUND);
        }
        Account account = response.getBody();

        boolean isWithdrawal = transactionDto.getTransactionType().equals(TransactionType.WITHDRAWAL.toString());

        if (isWithdrawal) {
            // Step 2: 校验账户状态（锁外）
            if (!"ACTIVE".equals(account.getAccountStatus())) {
                throw new AccountStatusException("account is inactive or closed");
            }

            // Step 3: Redisson 分布式锁 → @Transactional 记账
            String lockKey = "withdraw:" + transactionDto.getAccountId();
            return lockService.executeWithLock(lockKey, 10, -1, () -> {
                // 锁内: 查余额 → 记账 → 事务提交
                return self.doBookkeeping(transactionDto);
            });
        }

        // Step 4: 存款 — 无超扣风险，直接记账
        return self.doBookkeeping(transactionDto);
    }

    /**
     * 内部转账 — Fund-Transfer 调用
     * <p>
     * 多账户排序加锁（防死锁）:
     * 1. 提取所有账户 ID，排序去重
     * 2. RedissonMultiLock 一次性获取全部锁
     * 3. 锁内执行 @Transactional 记账
     * 4. finally 释放全部锁
     */
    @Override
    public Response internalTransaction(List<TransactionDto> transactionDtos, String transactionReference) {
        // 提取 + 排序账户 ID，保证所有并发路径以相同顺序获取锁
        List<String> accountIds = transactionDtos.stream()
                .map(TransactionDto::getAccountId)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        return lockService.executeWithMultiLock(accountIds, 10, -1, () -> {
            return self.doInternalTransfer(transactionDtos, transactionReference);
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

    // ==================== @Transactional 门面（供 self 代理调用） ====================

    /**
     * 单笔记账（存款/取款共用）
     * <p>
     * 调用方必须先获取锁（取款场景），再通过代理调用本方法。
     * 本方法内: 查余额 → 创建凭证 → 分录落库 → 校验借贷平衡 → 触发余额重算。
     */
    @Transactional
    public Response doBookkeeping(TransactionDto transactionDto) {
        boolean isWithdrawal = transactionDto.getTransactionType().equals(TransactionType.WITHDRAWAL.toString());

        // 取款: 锁内再次校验余额（防御性编程）
        if (isWithdrawal) {
            checkSufficientBalance(transactionDto.getAccountId(), transactionDto.getAmount());
        }

        // 创建凭证
        String voucherNo = generateVoucherNo();
        Transaction voucher = Transaction.builder()
                .voucherNo(voucherNo)
                .transactionType(TransactionType.valueOf(transactionDto.getTransactionType()))
                .referenceId(UUID.randomUUID().toString())
                .status(TransactionStatus.COMPLETED)
                .build();
        transactionRepository.save(voucher);

        // 创建分录
        List<JournalEntry> entries = buildEntriesForSingleTx(voucher.getId(), transactionDto);
        journalEntryRepository.saveAll(entries);

        // 校验借贷平衡
        validateBalance(voucher.getId());

        log.info("凭证 {} 记账完成 | {} | 分录数={}", voucherNo, transactionDto.getTransactionType(), entries.size());

        // 事务内触发余额重算（Account-Service 从分录实时计算，保证一致性）
        accountService.recalculateBalance(transactionDto.getAccountId());

        return Response.builder()
                .responseCode(ok)
                .message("Transaction completed successfully").build();
    }

    /**
     * 内部转账记账
     * <p>
     * 调用方必须先获取多账户锁，再通过代理调用本方法。
     */
    @Transactional
    public Response doInternalTransfer(List<TransactionDto> transactionDtos, String transactionReference) {
        // 创建凭证
        String voucherNo = generateVoucherNo();
        Transaction voucher = Transaction.builder()
                .voucherNo(voucherNo)
                .transactionType(TransactionType.INTERNAL_TRANSFER)
                .referenceId(transactionReference)
                .status(TransactionStatus.COMPLETED)
                .build();
        transactionRepository.save(voucher);

        // 创建分录
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

        // 校验借贷平衡
        validateBalance(voucher.getId());

        log.info("凭证 {} 内部转账完成 | reference={} | 分录数={}", voucherNo, transactionReference, entries.size());

        // 触发相关账户余额重算
        List<String> accountIds = transactionDtos.stream()
                .map(TransactionDto::getAccountId)
                .distinct()
                .collect(Collectors.toList());
        accountIds.forEach(accountService::recalculateBalance);

        return Response.builder()
                .responseCode(ok)
                .message("Transaction completed successfully").build();
    }

    // ==================== 私有方法 ====================

    /** 余额不足校验 */
    private void checkSufficientBalance(String accountId, BigDecimal amount) {
        BigDecimal currentBalance = journalEntryRepository.getAccountBalance(accountId);
        if (currentBalance == null || currentBalance.compareTo(amount) < 0) {
            throw new InsufficientBalance("Insufficient balance in the account");
        }
    }

    /** 单笔记账的分录构建: 客户账户 + 内部现金科目 */
    private List<JournalEntry> buildEntriesForSingleTx(Long voucherId, TransactionDto dto) {
        String cashAccount = "9999999999999"; // 内部现金科目，代表银行的现金池
        List<JournalEntry> entries = new ArrayList<>();

        if (TransactionType.DEPOSIT.toString().equals(dto.getTransactionType())) {
            // 存款: 银行负债增加(CREDIT)，对应现金资产增加(DEBIT)
            entries.add(buildEntry(voucherId, dto.getAccountId(), Direction.CREDIT, dto.getAmount(), dto.getDescription()));
            entries.add(buildEntry(voucherId, cashAccount, Direction.DEBIT, dto.getAmount(), "银行现金入库"));
        } else if (TransactionType.WITHDRAWAL.toString().equals(dto.getTransactionType())) {
            // 取款: 银行负债减少(DEBIT)，对应现金资产减少(CREDIT)
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

    /** 核心校验: SUM(DEBIT) == SUM(CREDIT) */
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
