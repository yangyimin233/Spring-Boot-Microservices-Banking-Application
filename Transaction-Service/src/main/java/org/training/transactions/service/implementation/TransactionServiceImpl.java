package org.training.transactions.service.implementation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
 * 复式记账服务实现
 * <p>
 * 核心原则:
 * 1. 每笔业务 = 一个凭证(Transaction) + N 条分录(JournalEntry)
 * 2. 有借必有贷，借贷必相等
 * 3. 余额 = SUM(DEBIT) - SUM(CREDIT)，由分录计算，不直接修改
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepository transactionRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final AccountService accountService;

    @Value("${spring.application.ok}")
    private String ok;

    private static final DateTimeFormatter VOUCHER_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 单笔记账（存款/取款）
     * <p>
     * 存款: 客户账户 DEBIT(借) + 现金科目 CREDIT(贷)
     * 取款: 客户账户 CREDIT(贷) + 现金科目 DEBIT(借)
     */
    @Override
    @Transactional
    public Response addTransaction(TransactionDto transactionDto) {
        // 加载账户
        ResponseEntity<Account> response = accountService.readByAccountNumber(transactionDto.getAccountId());
        if (Objects.isNull(response.getBody())) {
            throw new ResourceNotFound("Requested account not found on the server", GlobalErrorCode.NOT_FOUND);
        }
        Account account = response.getBody();

        if (transactionDto.getTransactionType().equals(TransactionType.WITHDRAWAL.toString())) {
            if (!"ACTIVE".equals(account.getAccountStatus())) {
                throw new AccountStatusException("account is inactive or closed");
            }
            // 用实时余额做校验（journalEntryRepository 查的，不是 account.getAvailableBalance）
            BigDecimal currentBalance = journalEntryRepository.getAccountBalance(transactionDto.getAccountId());
            if (currentBalance == null || currentBalance.compareTo(transactionDto.getAmount()) < 0) {
                throw new InsufficientBalance("Insufficient balance in the account");
            }
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

        // 创建分录 — 借贷平衡
        List<JournalEntry> entries = buildEntriesForSingleTx(voucher.getId(), transactionDto);
        journalEntryRepository.saveAll(entries);

        // 校验借贷平衡
        validateBalance(voucher.getId());

        log.info("凭证 {} 记账完成 | {} | 分录数={}", voucherNo, transactionDto.getTransactionType(), entries.size());

        return Response.builder()
                .responseCode(ok)
                .message("Transaction completed successfully").build();
    }

    /**
     * 内部转账 — Fund-Transfer 调用
     * <p>
     * 从调用方传入多条分录 DTO（每条的 direction 已确定），
     * 本方法创建凭证 + 批量保存分录 + 自动验证借贷平衡。
     */
    @Override
    @Transactional
    public Response internalTransaction(List<TransactionDto> transactionDtos, String transactionReference) {
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

        return Response.builder()
                .responseCode(ok)
                .message("Transaction completed successfully").build();
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

    // ==================== 私有方法 ====================

    /** 单笔记账的分录构建: 客户账户 + 内部现金科目 */
    private List<JournalEntry> buildEntriesForSingleTx(Long voucherId, TransactionDto dto) {
        String cashAccount = "9999999999999"; // 内部现金科目，代表银行的现金池
        List<JournalEntry> entries = new ArrayList<>();

        if (TransactionType.DEPOSIT.toString().equals(dto.getTransactionType())) {
            // 存款: 银行收到现金(负债增加=CREDIT 内部现金)，客户账户增加(DEBIT)
            entries.add(buildEntry(voucherId, cashAccount, Direction.CREDIT, dto.getAmount(), "银行现金入库"));
            entries.add(buildEntry(voucherId, dto.getAccountId(), Direction.DEBIT, dto.getAmount(), dto.getDescription()));
        } else if (TransactionType.WITHDRAWAL.toString().equals(dto.getTransactionType())) {
            // 取款: 客户账户减少(CREDIT)，银行付出现金(DEBIT)
            entries.add(buildEntry(voucherId, dto.getAccountId(), Direction.CREDIT, dto.getAmount(), dto.getDescription()));
            entries.add(buildEntry(voucherId, cashAccount, Direction.DEBIT, dto.getAmount(), "银行现金出库"));
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
