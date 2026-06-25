package org.training.transactions.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.training.transactions.model.entity.ReconciliationLog;

public interface ReconciliationLogRepository extends JpaRepository<ReconciliationLog, Long> {
}
