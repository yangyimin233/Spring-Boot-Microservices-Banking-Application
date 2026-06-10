package org.training.fundtransfer.configuration;

import com.alibaba.csp.sentinel.adapter.spring.webmvc.callback.BlockExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.Map;

/**
 * Sentinel 服务层配置
 * <p>
 * Feign 调用由 feign.sentinel.enabled=true 自动保护，每个 Feign 方法自动成为 Sentinel 资源。
 * 资源命名规则: GET:http://account-service/accounts/internal 等
 * <p>
 * 在 Sentinel Dashboard 中直接对这些资源配置线程数限流即可实现壁舱隔离，代码零侵入。
 */
@Configuration
public class SentinelConfig {

    /**
     * 被 Sentinel 拦截/降级时统一返回 JSON 格式错误
     */
    @Bean
    public BlockExceptionHandler sentinelBlockExceptionHandler() {
        return (request, response, ex) -> {
            Map<String, Object> body = new HashMap<>();
            body.put("code", 429);
            body.put("message", "服务繁忙，请稍后重试");
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(new ObjectMapper().writeValueAsString(body));
        };
    }
}
