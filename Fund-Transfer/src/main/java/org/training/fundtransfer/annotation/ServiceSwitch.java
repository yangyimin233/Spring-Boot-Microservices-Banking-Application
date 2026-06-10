package org.training.fundtransfer.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 服务开关注解
 * <p>
 * 结合 Nacos 动态配置，运行时控制方法走原逻辑还是降级逻辑。
 * 在 Nacos 控制台修改配置值后，无需重启即可实时生效。
 *
 * <pre>
 * Nacos 配置示例 (Data ID = fund-transfer-service.yml):
 *   service.switch.fundTransfer: true    ← 正常执行
 *   service.switch.fundTransfer: false   ← 走降级
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ServiceSwitch {

    /** Nacos 配置项的 key，如 service.switch.fundTransfer */
    String value();

    /** 降级时返回的消息 */
    String fallbackMessage() default "服务维护中，请稍后重试";
}
