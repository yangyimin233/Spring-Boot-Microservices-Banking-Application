package org.training.sequence.generator.service.implementation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.training.sequence.generator.model.entity.Sequence;
import org.training.sequence.generator.reporitory.SequenceRepository;
import org.training.sequence.generator.service.SequenceService;

/**
 * 号段服务 — 单行计数器模式
 * <p>
 * sequence 表:
 *   sequenceId=1 → accountNumber 列存账户编号计数器
 *   sequenceId=2 → accountNumber 列存全局流水号计数器
 * <p>
 * FOR UPDATE 行锁保证并发安全，每次取号递增 1。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SequenceServiceImpl implements SequenceService {

    private static final long ACCOUNT_SEQ_ID = 1L;
    private static final long TRANSACTION_SEQ_ID = 2L;

    private final SequenceRepository sequenceRepository;

    @Override
    @Transactional
    public Sequence create() {
        log.debug("生成账户编号");
        return increment(ACCOUNT_SEQ_ID);
    }

    @Override
    @Transactional
    public Sequence createTransactionReference() {
        log.debug("生成全局流水号");
        return increment(TRANSACTION_SEQ_ID);
    }

    private Sequence increment(Long seqId) {
        return sequenceRepository.findByIdForUpdate(seqId)
                .map(seq -> {
                    seq.setAccountNumber(seq.getAccountNumber() + 1);
                    return sequenceRepository.save(seq);
                }).orElseGet(() ->
                        sequenceRepository.save(Sequence.builder().accountNumber(1L).build()));
    }
}
