package org.training.sequence.generator.reporitory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.training.sequence.generator.model.entity.Sequence;

import javax.persistence.LockModeType;
import java.util.Optional;

public interface SequenceRepository extends JpaRepository<Sequence, Long> {

    @Query("SELECT COUNT(s) from Sequence s")
    int countAll();

    Sequence findFirstByOrderBySequenceIdDesc();

    /** FOR UPDATE 行锁 — 保证并发下序号不重复 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Sequence s WHERE s.sequenceId = :id")
    Optional<Sequence> findByIdForUpdate(@Param("id") Long id);
}
