package org.training.fundtransfer.configuration;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;

/**
 * Feign 令牌中继拦截器
 * 作用：在 Feign 发起内部调用前，将原请求的 Authorization 头复制到新请求中
 */
@Slf4j
@Configuration
public class FeignTokenInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        // Mark all Feign calls as internal to bypass downstream auth
        template.header("X-Internal-Call", "true");

        // Forward the Authorization token if present
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            String authorizationToken = request.getHeader("Authorization");
            if (authorizationToken != null) {
                template.header("Authorization", authorizationToken);
                log.debug("Feign 拦截器已成功转发 Token 到下游服务");
            }
        }
    }
}