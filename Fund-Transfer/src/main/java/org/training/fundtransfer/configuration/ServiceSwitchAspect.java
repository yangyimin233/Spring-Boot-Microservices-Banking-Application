package org.training.fundtransfer.configuration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.training.fundtransfer.annotation.ServiceSwitch;
import org.training.fundtransfer.model.dto.response.FundTransferResponse;

/**
 * 服务开关切面
 * <p>
 * 拦截 @ServiceSwitch 标注的方法，根据 Nacos 动态配置决定：
 * - true  → 执行原方法逻辑
 * - false → 返回降级消息（不执行原方法）
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ServiceSwitchAspect {

    private final ServiceSwitchProperties switchProperties;

    @Around("@annotation(serviceSwitch)")
    public Object around(ProceedingJoinPoint pjp, ServiceSwitch serviceSwitch) throws Throwable {
        String configKey = serviceSwitch.value();

        if (switchProperties.isEnabled(configKey)) {
            log.debug("[ServiceSwitch] {} = ON, 正常执行", configKey);
            return pjp.proceed();
        }

        log.warn("[ServiceSwitch] {} = OFF, 执行降级逻辑", configKey);
        String fallbackMessage = serviceSwitch.fallbackMessage();

        // 根据返回类型构造降级响应
        Class<?> returnType = ((org.aspectj.lang.reflect.MethodSignature) pjp.getSignature()).getReturnType();

        if (returnType == FundTransferResponse.class) {
            return FundTransferResponse.builder()
                    .message(fallbackMessage)
                    .build();
        }

        // 其他返回类型兜底
        throw new RuntimeException(fallbackMessage);
    }
}
