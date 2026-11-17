package com.asciistreamer.config;

import com.asciistreamer.websocket.StreamWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * WebSocket configuration that registers /ws/stream and wraps all sessions in
 * ConcurrentWebSocketSessionDecorator to guarantee thread-safe writes with buffer protection.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final StreamWebSocketHandler streamWebSocketHandler;

    public WebSocketConfig(StreamWebSocketHandler streamWebSocketHandler) {
        this.streamWebSocketHandler = streamWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        WebSocketHandler wrappedHandler = new WebSocketHandlerDecorator(streamWebSocketHandler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                // Wrap in ConcurrentWebSocketSessionDecorator: 10000ms send timeout, 16MB buffer limit for Ultra-HD frames
                ConcurrentWebSocketSessionDecorator concurrentSession =
                        new ConcurrentWebSocketSessionDecorator(session, 10000, 16 * 1024 * 1024);
                super.afterConnectionEstablished(concurrentSession);
            }
        };

        registry.addHandler(wrappedHandler, "/ws/stream")
                .setAllowedOrigins("*");
    }
}
