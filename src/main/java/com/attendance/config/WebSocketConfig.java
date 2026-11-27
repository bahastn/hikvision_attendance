package com.attendance.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.*;

import java.security.Principal;
import java.util.Arrays;
import java.util.Set;

/**
 * Live-update WebSocket (STOMP over SockJS).
 * <p>
 * Messages are sent to each user's private queue ({@code /user/queue/...}) and
 * {@link #clientInbound()} only lets a client subscribe to its own three queues.
 * Clients may not subscribe to raw {@code /queue}/{@code /topic} destinations,
 * use wildcards, or SEND anything, so one user cannot read or inject another
 * user's events.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** The only destinations a client may subscribe to (mapped to the user's private queue). */
    private static final Set<String> ALLOWED_SUBSCRIPTIONS =
            Set.of("/user/queue/stats", "/user/queue/detection", "/user/queue/camera");

    @Value("${app.security.websocket-allowed-origins:}")
    private String allowedOrigins;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Only the private per-user queue is used; no shared /topic broadcasts.
        config.enableSimpleBroker("/queue");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        StompWebSocketEndpointRegistration endpoint = registry.addEndpoint("/ws-attendance");
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        if (origins.length > 0) {
            endpoint.setAllowedOriginPatterns(origins);
        }
        endpoint.withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(clientInbound());
    }

    private ChannelInterceptor clientInbound() {
        return new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null || accessor.getCommand() == null) {
                    return message;
                }
                StompCommand command = accessor.getCommand();
                switch (command) {
                    case CONNECT -> {
                        // The handshake carries the HTTP session's user; reject anonymous connections.
                        Principal user = accessor.getUser();
                        if (user == null) {
                            throw new org.springframework.security.access.AccessDeniedException(
                                    "WebSocket connection requires a signed-in user");
                        }
                    }
                    case SUBSCRIBE -> {
                        String destination = accessor.getDestination();
                        if (accessor.getUser() == null || destination == null
                                || !ALLOWED_SUBSCRIPTIONS.contains(destination)) {
                            throw new org.springframework.security.access.AccessDeniedException(
                                    "Subscription to '" + destination + "' is not allowed");
                        }
                    }
                    case SEND -> throw new org.springframework.security.access.AccessDeniedException(
                            "Clients may not send messages");
                    default -> {
                        // CONNECT/SUBSCRIBE/SEND handled above; others (UNSUBSCRIBE, DISCONNECT…) are harmless.
                    }
                }
                return message;
            }
        };
    }
}
