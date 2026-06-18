package org.training.transactions.configuration;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置 — 余额同步队列
 */
@Configuration
public class RabbitMQConfig {

    public static final String BALANCE_SYNC_QUEUE = "balance.sync.queue";

    /**
     * 持久化队列，服务重启后消息不丢
     */
    @Bean
    public Queue balanceSyncQueue() {
        return new Queue(BALANCE_SYNC_QUEUE, true);
    }
}
