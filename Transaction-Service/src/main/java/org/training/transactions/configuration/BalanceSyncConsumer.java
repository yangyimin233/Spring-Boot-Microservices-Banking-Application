package org.training.transactions.configuration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.training.transactions.external.AccountService;

/**
 * 余额同步消费者 — 从 MQ 收到消息后调 Account-Service recalculateBalance
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BalanceSyncConsumer {

    private final AccountService accountService;

    @RabbitListener(queues = RabbitMQConfig.BALANCE_SYNC_QUEUE)
    public void handleBalanceSync(String accountId) {
        try {
            accountService.recalculateBalance(accountId);
            log.info("余额同步完成: accountId={}", accountId);
        } catch (Exception e) {
            // 失败由 RabbitMQ 自动重试（默认 3 次，间隔指数增长）
            log.warn("余额同步失败(将由 RabbitMQ 重试): accountId={}", accountId, e);
            throw e;
        }
    }
}
