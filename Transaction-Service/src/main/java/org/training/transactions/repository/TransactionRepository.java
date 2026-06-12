package org.training.transactions.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.training.transactions.model.entity.Transaction;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByReferenceId(String referenceId);

    /** 查询某账户关联的所有凭证（通过分录关联） */
    @Query("SELECT DISTINCT t FROM Transaction t JOIN JournalEntry j ON t.id = j.transactionId WHERE j.accountId = :accountId ORDER BY t.createdAt DESC")
    List<Transaction> findTransactionsByAccountId(@Param("accountId") String accountId);

    /** 按参照号查凭证 */
    Optional<Transaction> findTransactionByReferenceId(String referenceId);
}
