package org.training.api.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        // Security auth is handled by JwtGatewayFilter (GlobalFilter),
        // Spring Security here only disables CSRF and permits all.
        http
                .authorizeExchange()
                .anyExchange().permitAll()
                .and()
                .csrf().disable();
        return http.build();
    }
}
