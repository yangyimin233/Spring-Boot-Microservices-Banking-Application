package org.training.transactions.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.training.transactions.model.entity.FundTransfer;

import java.util.List;
import java.util.Optional;

public interface FundTransferRepository extends JpaRepository<FundTransfer, Long> {

    Optional<FundTransfer> findByTransactionReference(String referenceId);

    List<FundTransfer> findByFromAccount(String accountId);
}
