package org.training.transactions.configuration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.api.RedissonMultiLock;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Redisson 分布式锁服务
 * <p>
 * 封装 Redisson 锁的获取/释放，提供:
 * 1. tryLock — 尝试获取锁，支持等待超时 + 持有超时
 * 2. unlock — 安全释放锁
 * 3. executeWithLock — try-finally 模式，自动释放
 * <p>
 * 注意: leaseTime 不设或设 -1 时，Redisson watchdog 默认每 10s 续期到 30s，
 * 确保长事务场景下锁不会意外过期。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DistributedLockService {

    private static final String LOCK_PREFIX = "tx_lock:";

    private final RedissonClient redissonClient;

    /**
     * 尝试获取分布式锁
     *
     * @param key      锁标识（自动加前缀）
     * @param waitSec  最大等待秒数
     * @param leaseSec 锁持有超时秒数，-1 启用 watchdog 自动续期
     * @return 是否成功获取
     */
    public boolean tryLock(String key, long waitSec, long leaseSec) {
        RLock lock = redissonClient.getLock(LOCK_PREFIX + key);
        try {
            boolean acquired = lock.tryLock(waitSec, leaseSec, TimeUnit.SECONDS);
            if (acquired) {
                log.debug("分布式锁获取成功: key={}, thread={}", key, Thread.currentThread().getName());
            } else {
                log.warn("分布式锁获取超时: key={}, waitSec={}", key, waitSec);
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("分布式锁等待被中断: key={}", key, e);
            return false;
        }
    }

    /**
     * 释放锁
     *
     * @param key 锁标识
     */
    public void unlock(String key) {
        RLock lock = redissonClient.getLock(LOCK_PREFIX + key);
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("分布式锁释放成功: key={}, thread={}", key, Thread.currentThread().getName());
            } else {
                log.debug("当前线程未持有锁，跳过释放: key={}", key);
            }
        } catch (Exception e) {
            log.warn("分布式锁释放异常: key={}", key, e);
        }
    }

    /**
     * 带锁执行业务逻辑，自动释放
     * <p>
     * 使用示例:
     * <pre>{@code
     * String result = lockService.executeWithLock("account_123", 5, -1, () -> {
     *     // 锁内业务逻辑
     *     return doSomething();
     * });
     * }</pre>
     *
     * @param key      锁标识
     * @param waitSec  最大等待秒数
     * @param leaseSec 锁持有超时秒数，-1 启用 watchdog
     * @param supplier 业务逻辑
     * @return 业务返回值
     * @throws IllegalStateException 如果获取锁失败
     */
    public <T> T executeWithLock(String key, long waitSec, long leaseSec, Supplier<T> supplier) {
        if (!tryLock(key, waitSec, leaseSec)) {
            throw new IllegalStateException("获取分布式锁失败: " + key);
        }
        try {
            return supplier.get();
        } finally {
            unlock(key);
        }
    }

    /**
     * 多账户排序加锁，防止死锁
     * <p>
     * 对多个账户的锁按 key 排序后统一获取，确保所有并发路径以相同顺序获取锁。
     * 内部使用 RedissonMultiLock 原子性获取全部锁。
     *
     * @param keys     锁标识列表（调用方需保证已排序去重）
     * @param waitSec  最大等待秒数
     * @param leaseSec 锁持有超时秒数，-1 启用 watchdog
     * @param supplier 业务逻辑
     * @return 业务返回值
     * @throws IllegalStateException 如果获取锁失败
     */
    public <T> T executeWithMultiLock(List<String> keys, long waitSec, long leaseSec, Supplier<T> supplier) {
        if (keys == null || keys.isEmpty()) {
            return supplier.get();
        }

        List<RLock> locks = keys.stream()
                .map(k -> redissonClient.getLock(LOCK_PREFIX + k))
                .collect(Collectors.toList());

        RLock multiLock = locks.size() == 1
                ? locks.get(0)
                : new RedissonMultiLock(locks.toArray(new RLock[0]));

        boolean acquired = false;
        try {
            acquired = multiLock.tryLock(waitSec, leaseSec, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("多账户分布式锁等待被中断: " + keys, e);
        }

        if (!acquired) {
            throw new IllegalStateException("获取多账户分布式锁失败: " + keys);
        }

        log.debug("多账户锁获取成功: keys={}, thread={}", keys, Thread.currentThread().getName());

        try {
            return supplier.get();
        } finally {
            try {
                if (multiLock.isHeldByCurrentThread()) {
                    multiLock.unlock();
                    log.debug("多账户锁释放成功: keys={}, thread={}", keys, Thread.currentThread().getName());
                }
            } catch (Exception e) {
                log.warn("多账户锁释放异常: keys={}", keys, e);
            }
        }
    }
}
