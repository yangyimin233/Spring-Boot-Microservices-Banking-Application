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
        // 1. 获取当前 Spring MVC 正在处理的 HTTP 请求上下文
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();

            // 2. 提取出前端传过来的 Bearer Token
            String authorizationToken = request.getHeader("Authorization");

            if (authorizationToken != null) {
                // 3. 把 Token 塞进 Feign 即将发出的请求头里！
                template.header("Authorization", authorizationToken);
                log.debug("Feign 拦截器已成功转发 Token 到下游服务");
            } else {
                log.warn("当前请求未携带 Token，Feign 将发起无鉴权请求");
            }
        }
    }
}