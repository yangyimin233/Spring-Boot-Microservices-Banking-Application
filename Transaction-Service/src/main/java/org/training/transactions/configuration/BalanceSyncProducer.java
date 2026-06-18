package org.training.transactions.configuration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 余额同步消息生产者 — 事务提交后发送 accountId 到 MQ
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BalanceSyncProducer {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 发送余额同步消息。
     * 调用时机: @Transactional 事务提交后。
     */
    public void sendBalanceSync(String accountId) {
        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.BALANCE_SYNC_QUEUE, accountId);
            log.debug("余额同步消息已发送: accountId={}", accountId);
        } catch (Exception e) {
            // MQ 挂了不影响交易，消息丢了由对账兜底
            log.warn("余额同步消息发送失败(将依赖对账修复): accountId={}", accountId, e);
        }
    }
}
