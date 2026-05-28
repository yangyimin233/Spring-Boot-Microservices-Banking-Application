package org.training.api.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class SentinelBlockHandler {

    @PostConstruct
    public void initBlockHandlers() {
        GatewayCallbackManager.setBlockHandler((exchange, ex) -> {
            Map<String, Object> body = new HashMap<>();
            body.put("code", 429);
            body.put("message", "Too many requests. Please try again later.");
            return ServerResponse.status(429)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body);
        });
    }
}
