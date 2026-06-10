package org.training.fundtransfer.configuration;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * Nacos 动态开关配置
 * <p>
 * @RefreshScope 确保 Nacos 控制台修改配置后，此 Bean 被重新创建，新值立即生效。
 * 所有 service.switch.* 配置项都从这里读取，作为 AOP 切面的决策依据。
 */
@Data
@Component
@RefreshScope
public class ServiceSwitchProperties {

    /** 转账服务开关 — 对应 Nacos 配置: service.switch.fundTransfer */
    @Value("${service.switch.fundTransfer:true}")
    private String fundTransfer;

    /**
     * 根据配置 key 获取开关状态
     */
    public boolean isEnabled(String configKey) {
        if ("service.switch.fundTransfer".equals(configKey)) {
            return "true".equalsIgnoreCase(fundTransfer);
        }
        // 未匹配的 key 默认开启（正常执行）
        return true;
    }
}
