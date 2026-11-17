package com.asciistreamer.session;

import com.asciistreamer.model.Playhead;
import com.asciistreamer.model.VideoMetadata;
import com.asciistreamer.processing.AsciiMapper;
import com.asciistreamer.processing.BinaryProtocol;
import com.asciistreamer.processing.VideoDecoder;
import org.bytedeco.javacv.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages the streaming lifecycle for a single client session.
 * All decoder operations are serialized on a dedicated single-thread scheduled executor.
 */
public class StreamingSession {

    private static final Logger log = LoggerFactory.getLogger(StreamingSession.class);

    public enum State {
        INIT, PLAYING, PAUSED, ENDED, ERROR, CLOSED
    }

    private final String sessionId;
    private final String videoPath;
    private final double targetLeadSec;
    private final ScheduledExecutorService sessionExecutor;

    private volatile WebSocketSession wsSession;
    private volatile State state = State.INIT;
    private volatile byte generation = 0;
    private volatile boolean colorEnabled = true;
    private volatile double fontAspect = 0.5;

    // Fix 1: Single immutable Playhead reference. Atomic reads and writes prevent torn state.
    private volatile Playhead playhead = new Playhead(0, System.nanoTime());

    private final VideoDecoder decoder;
    private final AsciiMapper mapper;
    private ScheduledFuture<?> nextTick;
    private volatile double lastDecodedPts = 0.0;
    private int cols;
    private int rows;

    public StreamingSession(String sessionId, String videoPath, int initialCols, double fontAspect, double targetLeadSec) {
        this.sessionId = sessionId;
        this.videoPath = videoPath;
        this.targetLeadSec = targetLeadSec;
        this.fontAspect = fontAspect;
        this.cols = initialCols;
        this.rows = (int) Math.round(initialCols * (9.0 / 16.0) * fontAspect);

        this.sessionExecutor = Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, "stream-session-" + sessionId)
        );

        this.decoder = new VideoDecoder(videoPath, this.cols, this.rows);
        this.mapper = new AsciiMapper();
    }

    public void initDecoder() throws Exception {
        decoder.open();
    }

    public String getVideoPath() {
        return videoPath;
    }

    public void setWebSocketSession(WebSocketSession wsSession) {
        this.wsSession = wsSession;
    }

    public VideoMetadata getMetadata() {
        return new VideoMetadata(
                sessionId,
                decoder.getFps(),
                decoder.getDurationSeconds(),
                decoder.getTotalFrames(),
                decoder.getOriginalWidth(),
                decoder.getOriginalHeight(),
                cols,
                rows
        );
    }

    // --- Fix 1: Playhead management & extrapolation ---

    /**
     * Atomically replaces the playhead snapshot.
     */
    public void rebase(double seconds) {
        long micros = (long) (Math.max(0, seconds) * 1_000_000L);
        this.playhead = new Playhead(micros, System.nanoTime());
    }

    /**
     * Called directly on the WebSocket thread. Never touches the session executor!
     * Silently drops heartbeats from previous generations.
     */
    public void onTimeUpdate(double playheadSeconds, byte heartbeatGeneration) {
        if (heartbeatGeneration != this.generation) {
            return; // Stale heartbeat from before a seek/resize
        }
        rebase(playheadSeconds);
    }

    /**
     * Reads the immutable Playhead snapshot once and extrapolates forward.
     */
    private double extrapolatePlayhead() {
        Playhead ph = this.playhead; // single volatile read
        if (state != State.PLAYING) {
            return ph.micros() / 1_000_000.0;
        }
        long elapsedNanos = System.nanoTime() - ph.nanos();
        return (ph.micros() + (elapsedNanos / 1000L)) / 1_000_000.0;
    }

    // --- Command scheduling onto the single-thread executor ---

    public void play() {
        sessionExecutor.submit(() -> {
            if (state == State.PLAYING) return;
            state = State.PLAYING;
            rebase(lastDecodedPts);
            startPacer();
        });
    }

    public void pause() {
        sessionExecutor.submit(() -> {
            state = State.PAUSED;
            cancelNextTick();
        });
    }

    public void seek(double timestamp, byte clientGeneration) {
        sessionExecutor.submit(() -> executeSeek(timestamp, clientGeneration));
    }

    public void resize(int newCols, double newFontAspect, byte clientGeneration) {
        sessionExecutor.submit(() -> executeResize(newCols, newFontAspect, clientGeneration));
    }

    public void configure(Boolean color, String charset, Boolean invert) {
        sessionExecutor.submit(() -> {
            if (color != null) this.colorEnabled = color;
            if (charset != null) mapper.setCharset(charset);
            if (invert != null) mapper.setInvert(invert);
        });
    }

    // --- Internal executor-bound methods ---

    private void executeSeek(double timestamp, byte clientGeneration) {
        cancelNextTick();
        this.generation = clientGeneration; // Fix 3: adopt client's generation
        rebase(timestamp);                  // Fix 1: rebase playhead to seek target
        this.lastDecodedPts = timestamp;

        try {
            decoder.seekToTimestamp(timestamp);
            if (state == State.PLAYING) {
                startPacer();
            }
        } catch (Throwable t) {
            handleFatalError("Seek failed", t);
        }
    }

    private void executeResize(int newCols, double newFontAspect, byte clientGeneration) {
        cancelNextTick();
        this.generation = clientGeneration; // Fix 3: adopt client's generation
        this.fontAspect = newFontAspect;
        int newRows = (int) Math.round(newCols * (9.0 / 16.0) * newFontAspect);

        try {
            decoder.reconfigureDimensions(newCols, newRows, lastDecodedPts);
            this.cols = newCols;
            this.rows = newRows;
            rebase(lastDecodedPts); // Fix 1: rebase playhead

            // Send updated metadata
            sendTextMessage(String.format("{\"type\":\"metadata\",\"cols\":%d,\"rows\":%d,\"fps\":%.2f,\"duration\":%.2f}",
                    cols, rows, decoder.getFps(), decoder.getDurationSeconds()));

            if (state == State.PLAYING) {
                startPacer();
            }
        } catch (Throwable t) {
            handleFatalError("Resize failed", t);
        }
    }

    private void startPacer() {
        cancelNextTick();
        nextTick = sessionExecutor.schedule(this::pacerTick, 0, TimeUnit.MILLISECONDS);
    }

    private void cancelNextTick() {
        if (nextTick != null && !nextTick.isDone()) {
            nextTick.cancel(false);
            nextTick = null;
        }
    }

    /**
     * Fix 2: Protect pacerTick with try/catch Throwable.
     * Never let an exception escape, or the ScheduledExecutorService will silently drop all future ticks.
     */
    private void pacerTick() {
        try {
            if (state != State.PLAYING) return;

            Frame frame = decoder.grabNextFrame();
            if (frame == null) {
                state = State.ENDED;
                sendTextMessage("{\"type\":\"ended\"}");
                return;
            }

            double framePts = frame.timestamp / 1_000_000.0;
            this.lastDecodedPts = framePts;

            // Map frame to ASCII and stride-corrected RGB
            AsciiMapper.ProcessedFrame processed = mapper.process(frame, cols, rows, colorEnabled);

            // Fix 3: Write packet with current generation at byte offset 17
            byte[] packet = BinaryProtocol.buildPacket(
                    framePts,
                    cols,
                    rows,
                    this.generation,
                    processed.asciiChars(),
                    processed.rgbData(),
                    colorEnabled
            );

            if (wsSession != null && wsSession.isOpen()) {
                wsSession.sendMessage(new BinaryMessage(ByteBuffer.wrap(packet)));
            }

            // Server-side pacing with lookahead regulator
            double clientPts = extrapolatePlayhead();
            double lead = framePts - clientPts;
            long delayMs = Math.max(0, (long) ((lead - targetLeadSec) * 1000.0));

            nextTick = sessionExecutor.schedule(this::pacerTick, delayMs, TimeUnit.MILLISECONDS);

        } catch (Throwable t) {
            handleFatalError("pacerTick failure", t);
        }
    }

    private void handleFatalError(String context, Throwable t) {
        log.error("Fatal error in session {}: {}", sessionId, context, t);
        state = State.ERROR;
        cancelNextTick();

        String safeMsg = (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName())
                .replace("\"", "'")
                .replace("\n", " ");

        sendTextMessage("{\"type\":\"error\",\"message\":\"" + safeMsg + "\"}");
        closeGracefully();
    }

    private void sendTextMessage(String payload) {
        if (wsSession != null && wsSession.isOpen()) {
            try {
                wsSession.sendMessage(new TextMessage(payload));
            } catch (Exception e) {
                log.warn("Failed to send text message in session {}: {}", sessionId, e.getMessage());
            }
        }
    }

    public void closeGracefully() {
        state = State.CLOSED;
        cancelNextTick();
        sessionExecutor.submit(() -> {
            decoder.close();
            try {
                if (wsSession != null && wsSession.isOpen()) {
                    wsSession.close();
                }
            } catch (Exception ignored) {}
        });
        sessionExecutor.shutdown();
    }
}
