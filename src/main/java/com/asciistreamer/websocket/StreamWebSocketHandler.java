package com.asciistreamer.websocket;

import com.asciistreamer.model.StreamCommand;
import com.asciistreamer.model.VideoMetadata;
import com.asciistreamer.session.SessionManager;
import com.asciistreamer.session.StreamingSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;

/**
 * Handles incoming WebSocket connections and client command routing.
 */
@Component
public class StreamWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamWebSocketHandler.class);

    private final SessionManager sessionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StreamWebSocketHandler(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = extractSessionId(session.getUri());
        if (sessionId == null) {
            log.warn("WebSocket connection rejected: missing sessionId parameter");
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        StreamingSession streamingSession = sessionManager.getSession(sessionId);
        if (streamingSession == null) {
            log.warn("WebSocket connection rejected: invalid or expired sessionId {}", sessionId);
            session.sendMessage(new TextMessage("{\"type\":\"error\",\"message\":\"Session expired or not found\"}"));
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        session.getAttributes().put("sessionId", sessionId);
        streamingSession.setWebSocketSession(session);

        // Send initial metadata
        VideoMetadata meta = streamingSession.getMetadata();
        String metaJson = objectMapper.writeValueAsString(new MetadataMessage("metadata", meta));
        session.sendMessage(new TextMessage(metaJson));

        log.info("WebSocket connected for session: {}", sessionId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = (String) session.getAttributes().get("sessionId");
        if (sessionId == null) return;

        StreamingSession streamingSession = sessionManager.getSession(sessionId);
        if (streamingSession == null) return;

        StreamCommand cmd = objectMapper.readValue(message.getPayload(), StreamCommand.class);
        if (cmd.getAction() == null) return;

        byte gen = cmd.getGeneration() != null ? cmd.getGeneration().byteValue() : 0;

        switch (cmd.getAction()) {
            case "timeupdate" -> {
                // Handled directly on the WebSocket thread! Never enters executor queue.
                if (cmd.getPlayhead() != null) {
                    streamingSession.onTimeUpdate(cmd.getPlayhead(), gen);
                }
            }
            case "play" -> streamingSession.play();
            case "pause" -> streamingSession.pause();
            case "seek" -> {
                if (cmd.getTimestamp() != null) {
                    streamingSession.seek(cmd.getTimestamp(), gen);
                }
            }
            case "resize" -> {
                if (cmd.getCols() != null) {
                    double fontAspect = cmd.getFontAspect() != null ? cmd.getFontAspect() : 0.5;
                    streamingSession.resize(cmd.getCols(), fontAspect, gen);
                }
            }
            case "config" -> streamingSession.configure(cmd.getColor(), cmd.getCharset(), cmd.getInvert());
            default -> log.debug("Unknown command action: {}", cmd.getAction());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = (String) session.getAttributes().get("sessionId");
        if (sessionId != null) {
            log.info("WebSocket closed for session: {} (status: {})", sessionId, status);
            sessionManager.removeSession(sessionId);
        }
    }

    private String extractSessionId(URI uri) {
        if (uri == null || uri.getQuery() == null) return null;
        for (String param : uri.getQuery().split("&")) {
            String[] pair = param.split("=");
            if (pair.length == 2 && "sessionId".equalsIgnoreCase(pair[0])) {
                return pair[1];
            }
        }
        return null;
    }

    private record MetadataMessage(String type, VideoMetadata metadata) {}
}
