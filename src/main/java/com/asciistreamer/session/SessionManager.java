package com.asciistreamer.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the registry of active streaming sessions.
 */
@Service
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final ConcurrentHashMap<String, StreamingSession> sessions = new ConcurrentHashMap<>();

    @Value("${app.default-cols:112}")
    private int defaultCols;

    @Value("${app.default-font-aspect:0.5}")
    private double defaultFontAspect;

    @Value("${app.target-lead-sec:0.4}")
    private double targetLeadSec;

    public StreamingSession createSession(String videoPath) throws Exception {
        String sessionId = UUID.randomUUID().toString();
        StreamingSession session = new StreamingSession(
                sessionId,
                videoPath,
                defaultCols,
                defaultFontAspect,
                targetLeadSec
        );
        session.initDecoder();
        sessions.put(sessionId, session);
        log.info("Created streaming session: {}", sessionId);
        return session;
    }

    public StreamingSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public void removeSession(String sessionId) {
        StreamingSession session = sessions.remove(sessionId);
        if (session != null) {
            log.info("Removing and closing session: {}", sessionId);
            session.closeGracefully();
        }
    }
}
