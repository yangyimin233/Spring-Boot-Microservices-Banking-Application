package org.training.transactions.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.training.transactions.model.entity.JournalEntry;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    List<JournalEntry> findByTransactionId(Long transactionId);

    List<JournalEntry> findByAccountId(String accountId);

    /** 计算某账户实时余额: SUM(CREDIT) - SUM(DEBIT) (负债视角，保留用于对比测试) */
    @Query("SELECT COALESCE(SUM(CASE WHEN j.direction = 'CREDIT' THEN j.amount ELSE 0 END), 0) - " +
           "COALESCE(SUM(CASE WHEN j.direction = 'DEBIT' THEN j.amount ELSE 0 END), 0) " +
           "FROM JournalEntry j WHERE j.accountId = :accountId")
    BigDecimal getAccountBalance(@Param("accountId") String accountId);

    /** 查某个账户最新一条分录（用于 O(1) 取 running_balance 计算新余额） */
    Optional<JournalEntry> findTopByAccountIdOrderByIdDesc(String accountId);

    /** 获取所有有分录的账户 ID（用于对账） */
    @Query("SELECT DISTINCT j.accountId FROM JournalEntry j")
    List<String> findAllAccountIds();

    /** 全局试算平衡: SUM(ALL DEBIT) 应该等于 SUM(ALL CREDIT) */
    @Query("SELECT COALESCE(SUM(CASE WHEN j.direction = 'DEBIT' THEN j.amount ELSE 0 END), 0) - " +
           "COALESCE(SUM(CASE WHEN j.direction = 'CREDIT' THEN j.amount ELSE 0 END), 0) " +
           "FROM JournalEntry j")
    BigDecimal getGlobalTrialBalance();
}
