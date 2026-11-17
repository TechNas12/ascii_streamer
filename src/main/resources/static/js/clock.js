/**
 * Client Timing Authority, Jitter Queue, and Heartbeat Controller.
 */
class StreamClock {
    constructor(onLeadUpdate) {
        this.onLeadUpdate = onLeadUpdate;
        this.jitterBuffer = [];
        this.currentPlayhead = 0.0;
        this.playheadBase = 0.0;
        this.playheadStartTime = 0.0;
        this.isPlaying = false;
        this.currentGeneration = 0; // Fix 3: Client owns the generation counter
        this.heartbeatTimer = null;
        this.ws = null;
    }

    setWebSocket(ws) {
        this.ws = ws;
    }

    startHeartbeats() {
        this.stopHeartbeats();
        this.heartbeatTimer = setInterval(() => {
            if (this.isPlaying && this.ws && this.ws.readyState === WebSocket.OPEN) {
                // Fix 1 & 3: Send playhead timestamp along with generation
                this.ws.send(JSON.stringify({
                    action: "timeupdate",
                    playhead: this.getPlayhead(),
                    generation: this.currentGeneration
                }));
            }
        }, 150);
    }

    stopHeartbeats() {
        if (this.heartbeatTimer) {
            clearInterval(this.heartbeatTimer);
            this.heartbeatTimer = null;
        }
    }

    play() {
        if (this.isPlaying) return;
        this.isPlaying = true;
        this.playheadStartTime = performance.now();
        this.playheadBase = this.currentPlayhead;
        this.startHeartbeats();
    }

    pause() {
        if (!this.isPlaying) return;
        this.currentPlayhead = this.getPlayhead();
        this.isPlaying = false;
        this.stopHeartbeats();
    }

    seek(targetTimestamp) {
        // Fix 3: Flush stale frames and bump generation
        this.jitterBuffer = [];
        this.currentGeneration = (this.currentGeneration + 1) & 0xFF;
        this.currentPlayhead = Math.max(0, targetTimestamp);
        this.playheadBase = this.currentPlayhead;
        this.playheadStartTime = performance.now();

        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            this.ws.send(JSON.stringify({
                action: "seek",
                timestamp: this.currentPlayhead,
                generation: this.currentGeneration
            }));
        }
    }

    onResize(newCols, fontAspect) {
        // Fix 3: Bump generation on resize to reject pre-resize frames
        this.jitterBuffer = [];
        this.currentGeneration = (this.currentGeneration + 1) & 0xFF;

        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            this.ws.send(JSON.stringify({
                action: "resize",
                cols: newCols,
                fontAspect: fontAspect,
                generation: this.currentGeneration
            }));
        }
    }

    getPlayhead() {
        if (!this.isPlaying) {
            return this.currentPlayhead;
        }
        const elapsedSec = (performance.now() - this.playheadStartTime) / 1000.0;
        return this.playheadBase + elapsedSec;
    }

    pushFrame(frame) {
        // Fix 3: Drop any frame whose generation does not match the active one
        if (frame.generation !== this.currentGeneration) {
            return;
        }

        this.jitterBuffer.push(frame);

        // Sort by PTS just in case packets arrive slightly out-of-order
        if (this.jitterBuffer.length > 1) {
            this.jitterBuffer.sort((a, b) => a.pts - b.pts);
        }

        // Calculate and report server lookahead lead
        if (this.onLeadUpdate && this.jitterBuffer.length > 0) {
            const latestPts = this.jitterBuffer[this.jitterBuffer.length - 1].pts;
            const leadMs = Math.round((latestPts - this.getPlayhead()) * 1000);
            this.onLeadUpdate(leadMs, this.jitterBuffer.length);
        }
    }

    /**
     * Dequeues the frame corresponding to the current playhead.
     * Skips late frames if network hiccup caused multiple frames to pass.
     */
    dequeueReadyFrame() {
        if (this.jitterBuffer.length === 0) return null;

        const currentPts = this.getPlayhead();
        let targetFrame = null;

        while (this.jitterBuffer.length > 0 && this.jitterBuffer[0].pts <= currentPts) {
            targetFrame = this.jitterBuffer.shift();
        }

        return targetFrame;
    }

    peekNextFramePts() {
        return this.jitterBuffer.length > 0 ? this.jitterBuffer[0].pts : null;
    }

    reset() {
        this.jitterBuffer = [];
        this.currentPlayhead = 0.0;
        this.playheadBase = 0.0;
        this.playheadStartTime = performance.now();
        this.currentGeneration = 0;
        this.stopHeartbeats();
        this.isPlaying = false;
    }
}

window.StreamClock = StreamClock;
