package org.training.transactions.external;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Map;

/**
 * Sequence-Generator Feign 客户端
 */
@FeignClient(name = "sequence-generator")
public interface SequenceService {

    /** 生成全局流水号（代替 UUID 做幂等键） */
    @PostMapping("/sequence/transaction")
    Map<String, Object> generateTransactionReference();
}
