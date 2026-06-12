package org.training.transactions.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.training.transactions.model.entity.JournalEntry;

import java.math.BigDecimal;
import java.util.List;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    List<JournalEntry> findByTransactionId(Long transactionId);

    List<JournalEntry> findByAccountId(String accountId);

    /** 计算某账户的实时余额: SUM(DEBIT) - SUM(CREDIT) */
    @Query("SELECT COALESCE(SUM(CASE WHEN j.direction = 'DEBIT' THEN j.amount ELSE 0 END), 0) - " +
           "COALESCE(SUM(CASE WHEN j.direction = 'CREDIT' THEN j.amount ELSE 0 END), 0) " +
           "FROM JournalEntry j WHERE j.accountId = :accountId")
    BigDecimal getAccountBalance(@Param("accountId") String accountId);
}
