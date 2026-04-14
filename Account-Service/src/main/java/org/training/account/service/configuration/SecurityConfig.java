package org.training.account.service.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf().disable()
                .authorizeHttpRequests()
                .anyRequest().authenticated()
                .and()

                // 👇👇👇 新增的全局安全异常处理模块 👇👇👇
                .exceptionHandling()
                // 1. 处理 401: 没带证件或证件造假 (AuthenticationEntryPoint)
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setContentType("application/json;charset=UTF-8");
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 401 状态码

                    // 构建你想要的统一 JSON 格式（可以替换成项目里的 Response 对象）
                    Map<String, Object> result = new HashMap<>();
                    result.put("responseCode", "UNAUTHORIZED");
                    result.put("message", "安保拦截：未提供有效的身份凭证 (Token缺失或无效)，请先登录！");

                    // 将 JSON 写入响应体
                    response.getWriter().write(new ObjectMapper().writeValueAsString(result));
                })
                // 2. 处理 403: 越权访问 (AccessDeniedHandler)
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setContentType("application/json;charset=UTF-8");
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN); // 403 状态码

                    Map<String, Object> result = new HashMap<>();
                    result.put("responseCode", "FORBIDDEN");
                    result.put("message", accessDeniedException.getMessage());

                    response.getWriter().write(new ObjectMapper().writeValueAsString(result));
                })
                .and()

                .oauth2ResourceServer()
                .jwt();

        return http.build();
    }
}