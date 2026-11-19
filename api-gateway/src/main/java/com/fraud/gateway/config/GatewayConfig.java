package com.fraud.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * GatewayConfig — Cross-cutting concerns for the API Gateway
 *
 * Responsibilities:
 *  1. Global request/response logging filter
 *  (CORS is managed via Spring Cloud Gateway globalcors configuration in YAML)
 */
@Configuration
public class GatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfig.class);

    /**
     * Global logging filter — logs every request that passes through the gateway.
     * Useful for distributed tracing and debugging.
     */
    @Bean
    public GlobalFilter globalLoggingFilter() {
        return (exchange, chain) -> {
            // Pre-filter: log incoming request
            log.info("[GATEWAY] Incoming request → Method: {} | Path: {}",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getURI().getPath());

            long startTime = System.currentTimeMillis();

            // Post-filter: log response status and elapsed time
            return chain.filter(exchange).then(Mono.fromRunnable(() -> {
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("[GATEWAY] Response ← Status: {} | Elapsed: {}ms",
                        exchange.getResponse().getStatusCode(),
                        elapsed);
            }));
        };
    }
}
