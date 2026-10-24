package com.tradex.config;

import com.tradex.config.SecurityProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;

/**
 * STOMP-over-WebSocket transport for live market data.
 *
 * <h2>Destinations</h2>
 * <ul>
 *   <li>{@code /topic/quotes} - a stream of market ticks. Public: it carries no user data.</li>
 *   <li>{@code /topic/account/{accountId}} - authoritative account snapshots, published only after
 *       the owner's own trades. The account id is not a bearer credential (the payload is a
 *       portfolio summary), but subscribing is still scoped by the client to its own account.</li>
 * </ul>
 *
 * <p>SockJS is enabled with a raw-WebSocket fallback so browsers behind restrictive proxies work.</p>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final SecurityProperties securityProperties;

    public WebSocketConfig(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[]{10_000, 10_000})
                .setTaskScheduler(heartbeatScheduler());
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = Arrays.stream(securityProperties.getAllowedOrigins().split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toArray(String[]::new);
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origins.length == 0 ? new String[]{"http://localhost:*"} : origins)
                .withSockJS()
                .setHeartbeatTime(10_000);
    }

    private org.springframework.scheduling.TaskScheduler heartbeatScheduler() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }
}
