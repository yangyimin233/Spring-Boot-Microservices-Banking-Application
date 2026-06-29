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
import org.training.transactions.external.SequenceService;
import org.training.transactions.model.Direction;
import org.training.transactions.model.TransactionStatus;
import org.training.transactions.model.TransactionType;
import org.training.transactions.model.dto.FundTransferRequest;
import org.training.transactions.model.dto.TransactionDto;
import org.training.transactions.model.entity.AccountBalance;
import org.training.transactions.model.entity.FundTransfer;
import org.training.transactions.model.entity.JournalEntry;
import org.training.transactions.model.entity.Transaction;
import org.training.transactions.model.response.FundTransferResponse;
import org.training.transactions.model.response.Response;
import org.training.transactions.model.response.TransactionRequest;
import org.training.transactions.model.TransferType;
import org.training.transactions.repository.AccountBalanceRepository;
import org.training.transactions.repository.FundTransferRepository;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 复式记账服务实现 — 双层锁防护
 * <p>
 * 外层: Redisson 分布式锁（减少无效 DB 竞争）
 * 内层: FOR UPDATE 行锁（InnoDB 绝对串行保证，与分录同库同事务原子提交）
 * <p>
 * account_balance 表跟分录在同一个本地事务里更新，余额永远精准。
 * Account-Service 的 availableBalance 降级为展示缓存，MQ 异步刷新。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionServiceImpl implements TransactionService {

    private static final String CASH_ACCOUNT = "9999999999999";

    private final TransactionRepository transactionRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final AccountBalanceRepository accountBalanceRepository;
    private final FundTransferRepository fundTransferRepository;
    private final AccountService accountService;
    private final SequenceService sequenceService;
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
                Response resp = self.doBookkeeping(transactionDto); // @Transactional
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

        Response resp = lockService.executeWithMultiLock(accountIds, 10, 30, () ->
                self.doInternalTransfer(transactionDtos, transactionReference));

        accountIds.forEach(syncProducer::sendBalanceSync);
        return resp;
    }

    /**
     * 转账 — 合并了原 Fund-Transfer 服务逻辑。
     * <p>
     * 校验 from/to 账户 → 构建分录 DTO → 调 internalTransaction（双层锁+记账）→ 保存转账日志。
     * 全部在同一微服务内完成，不再跨 Feign 调用。
     */
    public FundTransferResponse fundTransfer(FundTransferRequest request) {
        // 校验 fromAccount
        var fromResp = accountService.readByAccountNumber(request.getFromAccount());
        if (Objects.isNull(fromResp.getBody())) {
            throw new ResourceNotFound("From account not found", GlobalErrorCode.NOT_FOUND);
        }
        if (!"ACTIVE".equals(fromResp.getBody().getAccountStatus())) {
            throw new AccountStatusException("From account is not active");
        }

        // 校验 toAccount
        var toResp = accountService.readByAccountNumber(request.getToAccount());
        if (Objects.isNull(toResp.getBody())) {
            throw new ResourceNotFound("To account not found", GlobalErrorCode.NOT_FOUND);
        }

        // 构建双分录
        String referenceId = resolveReferenceId(null);
        List<TransactionDto> entries = List.of(
                TransactionDto.builder()
                        .accountId(request.getFromAccount())
                        .direction(Direction.DEBIT)
                        .amount(request.getAmount())
                        .description("转账至 " + request.getToAccount())
                        .build(),
                TransactionDto.builder()
                        .accountId(request.getToAccount())
                        .direction(Direction.CREDIT)
                        .amount(request.getAmount())
                        .description("来自 " + request.getFromAccount() + " 的转账")
                        .build());

        // 记复式账（双层锁 + FOR UPDATE + 分录 + 余额更新，同事务原子提交）
        internalTransaction(entries, referenceId);

        // 保存转账日志
        fundTransferRepository.save(FundTransfer.builder()
                .transactionReference(referenceId)
                .fromAccount(request.getFromAccount())
                .toAccount(request.getToAccount())
                .amount(request.getAmount())
                .status(TransactionStatus.SUCCESS)
                .transferType(TransferType.INTERNAL)
                .build());

        log.info("转账完成: {} → {} amount={} reference={}",
                request.getFromAccount(), request.getToAccount(), request.getAmount(), referenceId);

        return FundTransferResponse.builder()
                .transactionId(referenceId)
                .message("Fund transfer successful").build();
    }

    @Override
    public List<TransactionRequest> getTransaction(String accountId) {
        return journalEntryRepository.findByAccountId(accountId)
                .stream().map(entry -> toTransactionRequest(entry)).collect(Collectors.toList());
    }

    @Override
    public List<TransactionRequest> getTransactionByTransactionReference(String transactionReference) {
        Transaction voucher = transactionRepository.findTransactionByReferenceId(transactionReference)
                .orElseThrow(() -> new ResourceNotFound("Transaction not found", GlobalErrorCode.NOT_FOUND));
        return journalEntryRepository.findByTransactionId(voucher.getId())
                .stream().map(this::toTransactionRequest).collect(Collectors.toList());
    }

    @Override
    public BigDecimal getAccountBalance(String accountId) {
        return accountBalanceRepository.findByAccountId(accountId)
                .map(AccountBalance::getBalance)
                .orElseGet(() -> {
                    BigDecimal sum = journalEntryRepository.getAccountBalance(accountId);
                    return sum != null ? sum : BigDecimal.ZERO;
                });
    }

    // ==================== @Transactional 门面 ====================

    /**
     * 单笔记账 — FOR UPDATE 锁余额 + 写分录，同事务原子提交。
     */
    @Transactional
    public Response doBookkeeping(TransactionDto dto) {
        String referenceId = resolveReferenceId(dto);
        Optional<Transaction> existing = checkIdempotent(referenceId);
        if (existing.isPresent()) {
            return idempotentResponse();
        }

        boolean isDeposit = TransactionType.DEPOSIT.toString().equals(dto.getTransactionType());
        String accountId = dto.getAccountId();

        // FOR UPDATE 锁住客户账户 + 现金账户的行
        AccountBalance customerBal = resolveBalanceForUpdate(accountId);
        AccountBalance cashBal = resolveBalanceForUpdate(CASH_ACCOUNT);

        // 余额检查（DEBIT 扣款时才需要）
        if (!isDeposit && customerBal.getBalance().compareTo(dto.getAmount()) < 0) {
            throw new InsufficientBalance("Insufficient balance in the account");
        }

        // 计算新余额
        BigDecimal newCustomerBal = isDeposit
                ? customerBal.getBalance().add(dto.getAmount())
                : customerBal.getBalance().subtract(dto.getAmount());
        BigDecimal newCashBal = isDeposit
                ? cashBal.getBalance().add(dto.getAmount())
                : cashBal.getBalance().subtract(dto.getAmount());

        // 创建凭证
        String voucherNo = generateVoucherNo();
        Transaction voucher = Transaction.builder()
                .voucherNo(voucherNo)
                .transactionType(TransactionType.valueOf(dto.getTransactionType()))
                .referenceId(referenceId)
                .status(TransactionStatus.COMPLETED)
                .build();
        transactionRepository.save(voucher);

        // 创建分录（含 running_balance）
        List<JournalEntry> entries = new ArrayList<>();
        Direction customerDir = isDeposit ? Direction.CREDIT : Direction.DEBIT;
        Direction cashDir = isDeposit ? Direction.DEBIT : Direction.CREDIT;
        entries.add(entry(voucher.getId(), accountId, customerDir, dto.getAmount(),
                dto.getDescription(), newCustomerBal));
        entries.add(entry(voucher.getId(), CASH_ACCOUNT, cashDir, dto.getAmount(),
                isDeposit ? "银行现金入库" : "银行现金出库", newCashBal));
        journalEntryRepository.saveAll(entries);

        // 持久化余额
        customerBal.setBalance(newCustomerBal);
        cashBal.setBalance(newCashBal);

        validateBalance(voucher.getId());

        log.info("凭证 {} 记账完成 | {} | 余额={}", voucherNo, dto.getTransactionType(), newCustomerBal);
        return Response.builder().responseCode(ok).message("Transaction completed successfully").build();
    }

    /**
     * 内部转账 — FOR UPDATE 锁所有账户余额 + 写分录，同事务原子提交。
     */
    @Transactional
    public Response doInternalTransfer(List<TransactionDto> transactionDtos, String transactionReference) {
        Optional<Transaction> existing = checkIdempotent(transactionReference);
        if (existing.isPresent()) {
            return idempotentResponse();
        }

        List<String> accountIds = transactionDtos.stream()
                .map(TransactionDto::getAccountId)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        // FOR UPDATE 锁所有涉及账户
        List<AccountBalance> balanceRows = accountBalanceRepository.findByAccountIdsForUpdate(accountIds);
        Map<String, AccountBalance> balanceMap = balanceRows.stream()
                .collect(Collectors.toMap(AccountBalance::getAccountId, Function.identity()));

        // 确保所有账户都有 balance 行（新账户处理）
        for (String id : accountIds) {
            balanceMap.putIfAbsent(id, resolveBalanceForUpdate(id));
        }

        // 检查所有 DEBIT 账户余额
        for (TransactionDto dto : transactionDtos) {
            if (dto.getDirection() == Direction.DEBIT) {
                AccountBalance bal = balanceMap.get(dto.getAccountId());
                if (bal.getBalance().compareTo(dto.getAmount()) < 0) {
                    throw new InsufficientBalance("Insufficient balance for account: " + dto.getAccountId());
                }
            }
        }

        // 更新所有余额
        for (TransactionDto dto : transactionDtos) {
            AccountBalance bal = balanceMap.get(dto.getAccountId());
            BigDecimal newBal = dto.getDirection() == Direction.CREDIT
                    ? bal.getBalance().add(dto.getAmount())
                    : bal.getBalance().subtract(dto.getAmount());
            bal.setBalance(newBal);
        }

        // 创建凭证
        String voucherNo = generateVoucherNo();
        Transaction voucher = Transaction.builder()
                .voucherNo(voucherNo)
                .transactionType(TransactionType.INTERNAL_TRANSFER)
                .referenceId(transactionReference)
                .status(TransactionStatus.COMPLETED)
                .build();
        transactionRepository.save(voucher);

        // 创建分录（含 running_balance）
        List<JournalEntry> entries = new ArrayList<>();
        for (TransactionDto dto : transactionDtos) {
            BigDecimal newBal = balanceMap.get(dto.getAccountId()).getBalance();
            entries.add(entry(voucher.getId(), dto.getAccountId(),
                    dto.getDirection() != null ? dto.getDirection() : Direction.DEBIT,
                    dto.getAmount(), dto.getDescription(), newBal));
        }
        journalEntryRepository.saveAll(entries);

        validateBalance(voucher.getId());

        log.info("凭证 {} 内部转账完成 | reference={} | 分录数={}", voucherNo, transactionReference, entries.size());
        return Response.builder().responseCode(ok).message("Transaction completed successfully").build();
    }

    // ==================== 私有辅助方法 ====================

    /** FOR UPDATE 锁住余额行，不存在则创建并初始化为 0 */
    private AccountBalance resolveBalanceForUpdate(String accountId) {
        Optional<AccountBalance> existing = accountBalanceRepository.findByAccountIdForUpdate(accountId);
        if (existing.isPresent()) {
            return existing.get();
        }
        // 新账户: 查分录历史余额做初始化
        BigDecimal initBal = journalEntryRepository.findTopByAccountIdOrderByIdDesc(accountId)
                .map(JournalEntry::getRunningBalance)
                .orElse(null);
        if (initBal == null) {
            initBal = journalEntryRepository.getAccountBalance(accountId);
            if (initBal == null) initBal = BigDecimal.ZERO;
        }
        AccountBalance row = AccountBalance.builder()
                .accountId(accountId).balance(initBal).build();
        accountBalanceRepository.saveAndFlush(row);
        return accountBalanceRepository.findByAccountIdForUpdate(accountId).orElseThrow();
    }

    private String resolveReferenceId(TransactionDto dto) {
        if (dto != null) {
            String ref = dto.getReferenceId();
            if (ref != null && !ref.isEmpty()) return ref;
        }
        try {
            Map<String, Object> seq = sequenceService.generateTransactionReference();
            long seqNum = ((Number) seq.get("accountNumber")).longValue();
            return "TX" + LocalDate.now().format(VOUCHER_FMT) + String.format("%08d", seqNum);
        } catch (Exception e) {
            log.warn("Sequence-Generator 不可用，降级为 UUID", e);
            return UUID.randomUUID().toString();
        }
    }

    private Optional<Transaction> checkIdempotent(String referenceId) {
        return transactionRepository.findByReferenceId(referenceId);
    }

    private Response idempotentResponse() {
        return Response.builder().responseCode(ok)
                .message("Transaction completed successfully (idempotent)").build();
    }

    private JournalEntry entry(Long txId, String accountId, Direction dir, BigDecimal amount,
                                String desc, BigDecimal runningBalance) {
        return JournalEntry.builder()
                .transactionId(txId).accountId(accountId).direction(dir)
                .amount(amount).description(desc).runningBalance(runningBalance).build();
    }

    private TransactionRequest toTransactionRequest(JournalEntry entry) {
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
    }

    private String generateVoucherNo() {
        String date = LocalDate.now().format(VOUCHER_FMT);
        long count = transactionRepository.count() + 1;
        return "TX" + date + String.format("%06d", count);
    }

    private void validateBalance(Long transactionId) {
        List<JournalEntry> entries = journalEntryRepository.findByTransactionId(transactionId);
        BigDecimal debitSum = entries.stream().filter(e -> e.getDirection() == Direction.DEBIT)
                .map(JournalEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditSum = entries.stream().filter(e -> e.getDirection() == Direction.CREDIT)
                .map(JournalEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (debitSum.compareTo(creditSum) != 0) {
            log.error("借贷不平衡! 凭证ID={}, DEBIT={}, CREDIT={}", transactionId, debitSum, creditSum);
            throw new IllegalStateException("借贷不平衡: DEBIT=" + debitSum + ", CREDIT=" + creditSum);
        }
    }
}
