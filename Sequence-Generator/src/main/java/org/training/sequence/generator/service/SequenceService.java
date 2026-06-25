package org.training.sequence.generator.service;

import org.training.sequence.generator.model.entity.Sequence;

public interface SequenceService {

    /** 生成账户编号 */
    Sequence create();

    /** 生成全局流水号（幂等键/交易参考号） */
    Sequence createTransactionReference();
}
