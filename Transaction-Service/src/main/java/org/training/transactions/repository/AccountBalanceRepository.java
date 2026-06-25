package org.training.transactions.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.training.transactions.model.entity.AccountBalance;

import javax.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface AccountBalanceRepository extends JpaRepository<AccountBalance, Long> {

    Optional<AccountBalance> findByAccountId(String accountId);

    /** FOR UPDATE — 行锁住余额行，事务提交后释放 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM AccountBalance b WHERE b.accountId = :accountId")
    Optional<AccountBalance> findByAccountIdForUpdate(@Param("accountId") String accountId);

    /** FOR UPDATE 批量锁多行 — 按 accountId 排序防死锁 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM AccountBalance b WHERE b.accountId IN :accountIds ORDER BY b.accountId")
    List<AccountBalance> findByAccountIdsForUpdate(@Param("accountIds") List<String> accountIds);
}
