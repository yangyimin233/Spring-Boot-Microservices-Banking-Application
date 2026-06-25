package org.training.transactions.job;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.training.transactions.external.AccountService;
import org.training.transactions.model.entity.ReconciliationLog;
import org.training.transactions.repository.JournalEntryRepository;
import org.training.transactions.repository.ReconciliationLogRepository;

import java.math.BigDecimal;
import java.util.List;

/**
 * 日终对账任务
 * <p>
 * 以分录 SUM（真相源）为准，对比 Account-Service 的 availableBalance（缓存）。
 * 不一致则调 recalculateBalance 修正，并写入对账日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationJob {

    private final JournalEntryRepository journalEntryRepository;
    private final AccountService accountService;
    private final ReconciliationLogRepository reconciliationLogRepository;

    /**
     * 全量对账 — 每天凌晨 1:00 执行
     */
    @XxlJob("balanceFullReconciliation")
    public void fullReconciliation() {
        log.info("========== 全量对账开始 ==========");
        // Step 0: 全局试算平衡（日终轧账）— 整体 DEBIT 必须等于 CREDIT
        BigDecimal trialBalance = journalEntryRepository.getGlobalTrialBalance();
        if (trialBalance != null && trialBalance.compareTo(BigDecimal.ZERO) != 0) {
            log.error("全局试算不平! SUM(DEBIT) - SUM(CREDIT) = {}", trialBalance);
            XxlJobHelper.handleFail("全局试算不平! 差异=" + trialBalance + "，请人工排查");
            return;
        }
        XxlJobHelper.log("全局试算平衡: SUM(DEBIT) = SUM(CREDIT) ✅");

        int fixed = 0, ok = 0, error = 0;

        // 获取所有有分录的账户
        List<String> accountIds = journalEntryRepository.findAllAccountIds();
        XxlJobHelper.log("待对账账户数: {}", accountIds.size());

        for (String accountId : accountIds) {
            try {
                // 分录余额（真相）
                BigDecimal entryBalance = journalEntryRepository.getAccountBalance(accountId);

                // 缓存余额（Account-Service）
                BigDecimal cacheBalance = null;
                try {
                    var resp = accountService.readByAccountNumber(accountId);
                    if (resp.getBody() != null) {
                        cacheBalance = resp.getBody().getAvailableBalance();
                    }
                } catch (Exception e) {
                    log.warn("读取 Account-Service 余额失败: accountId={}", accountId);
                }

                BigDecimal diff = entryBalance != null && cacheBalance != null
                        ? entryBalance.subtract(cacheBalance) : null;

                if (diff != null && diff.compareTo(BigDecimal.ZERO) != 0) {
                    // 不一致 → 修正
                    accountService.recalculateBalance(accountId);
                    reconciliationLogRepository.save(ReconciliationLog.builder()
                            .accountId(accountId)
                            .entryBalance(entryBalance)
                            .cacheBalance(cacheBalance)
                            .diff(diff)
                            .result("FIXED")
                            .remark("对账修正: " + diff)
                            .build());
                    fixed++;
                    log.warn("对账修正: accountId={}, entry={}, cache={}, diff={}",
                            accountId, entryBalance, cacheBalance, diff);
                } else {
                    ok++;
                }
            } catch (Exception e) {
                log.error("对账失败: accountId={}", accountId, e);
                reconciliationLogRepository.save(ReconciliationLog.builder()
                        .accountId(accountId)
                        .result("ERROR")
                        .remark(e.getMessage())
                        .build());
                error++;
            }
        }

        log.info("========== 全量对账完成: 正常={}, 修正={}, 异常={} ==========", ok, fixed, error);
        XxlJobHelper.handleSuccess(String.format("正常:%d 修正:%d 异常:%d", ok, fixed, error));
    }

    /**
     * 快速抽查 — 每 5 分钟跑一次，抽样 100 个账户
     */
    @XxlJob("balanceQuickCheck")
    public void quickCheck() {
        List<String> accountIds = journalEntryRepository.findAllAccountIds();
        if (accountIds.isEmpty()) {
            XxlJobHelper.handleSuccess("无数据");
            return;
        }

        int sampleSize = Math.min(100, accountIds.size());
        int mismatch = 0;

        for (int i = 0; i < sampleSize; i++) {
            String accountId = accountIds.get(i);
            try {
                BigDecimal entryBalance = journalEntryRepository.getAccountBalance(accountId);
                try {
                    var resp = accountService.readByAccountNumber(accountId);
                    if (resp.getBody() != null) {
                        BigDecimal cacheBalance = resp.getBody().getAvailableBalance();
                        if (entryBalance != null && entryBalance.compareTo(cacheBalance) != 0) {
                            mismatch++;
                            log.warn("抽查异常: accountId={}, entry={}, cache={}", accountId, entryBalance, cacheBalance);
                        }
                    }
                } catch (Exception ignored) {
                    // Account-Service 不可用，跳过
                }
            } catch (Exception e) {
                log.error("抽查失败: accountId={}", accountId, e);
            }
        }

        if (mismatch > 0) {
            XxlJobHelper.handleFail("抽查 " + sampleSize + " 个账户, " + mismatch + " 个不一致");
        } else {
            XxlJobHelper.handleSuccess("抽查 " + sampleSize + " 个账户, 全部一致");
        }
    }
}
