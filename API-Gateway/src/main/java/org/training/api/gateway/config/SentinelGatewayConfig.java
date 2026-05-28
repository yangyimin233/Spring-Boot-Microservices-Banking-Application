package org.training.api.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.util.HashSet;
import java.util.Set;

@Configuration
public class SentinelGatewayConfig {

    @PostConstruct
    public void initFlowRules() {
        Set<GatewayFlowRule> rules = new HashSet<>();

        rules.add(new GatewayFlowRule("fund-transfer-service")
                .setCount(10).setIntervalSec(1));

        rules.add(new GatewayFlowRule("account-service")
                .setCount(100).setIntervalSec(1));

        rules.add(new GatewayFlowRule("user-auth")
                .setCount(20).setIntervalSec(1));

        rules.add(new GatewayFlowRule("user-service")
                .setCount(50).setIntervalSec(1));

        rules.add(new GatewayFlowRule("transaction-service")
                .setCount(50).setIntervalSec(1));

        GatewayRuleManager.loadRules(rules);
    }
}
