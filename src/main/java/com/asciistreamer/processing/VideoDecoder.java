package com.asciistreamer.processing;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Manages video decoding using JavaCV / FFmpeg with native scaling and timestamp seeking.
 * Note: FFmpegFrameGrabber is NOT thread-safe. All methods must be invoked strictly
 * from the session's dedicated single-thread executor.
 */
public class VideoDecoder implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VideoDecoder.class);

    private final String videoPath;
    private FFmpegFrameGrabber grabber;
    private int currentCols;
    private int currentRows;
    private int originalWidth;
    private int originalHeight;
    private double fps;
    private double durationSeconds;
    private long totalFrames;

    public VideoDecoder(String videoPath, int initialCols, int initialRows) {
        this.videoPath = videoPath;
        this.currentCols = initialCols;
        this.currentRows = initialRows;
    }

    /**
     * Initializes the grabber, probes metadata, and sets initial native dimensions.
     */
    public void open() throws Exception {
        File file = new File(videoPath);
        if (!file.exists()) {
            throw new IllegalArgumentException("Video file not found: " + videoPath);
        }

        grabber = new FFmpegFrameGrabber(file);
        // Start once to probe video streams and metadata
        grabber.start();

        this.originalWidth = grabber.getImageWidth();
        this.originalHeight = grabber.getImageHeight();
        this.fps = grabber.getFrameRate() > 0 ? grabber.getFrameRate() : 30.0;
        this.totalFrames = grabber.getLengthInVideoFrames();
        long durationMicros = grabber.getLengthInTime();
        this.durationSeconds = durationMicros > 0 ? durationMicros / 1_000_000.0 : (totalFrames / fps);

        log.info("Opened video {}: {}x{}, {:.2f} FPS, {:.2f}s duration, {} frames",
                videoPath, originalWidth, originalHeight, fps, durationSeconds, totalFrames);

        // Stop to configure native scaling and restart with swscale applied
        grabber.stop();
        grabber.setImageWidth(currentCols);
        grabber.setImageHeight(currentRows);
        grabber.setPixelFormat(avutil.AV_PIX_FMT_RGB24);
        grabber.start();
    }

    public Frame grabNextFrame() throws Exception {
        if (grabber == null) return null;
        return grabber.grabImage();
    }

    /**
     * Seeks to a presentation timestamp in seconds.
     */
    public void seekToTimestamp(double timestampSeconds) throws Exception {
        if (grabber == null) return;
        long micros = (long) (Math.max(0, timestampSeconds) * 1_000_000L);
        grabber.setTimestamp(micros);
    }

    /**
     * Reconfigures native decode resolution.
     * Bug 4 fix: setImageWidth/Height cannot be changed on a running grabber.
     * Must stop, update dimensions, start, and restore current PTS.
     */
    public void reconfigureDimensions(int newCols, int newRows, double currentTimestampSeconds) throws Exception {
        if (newCols == currentCols && newRows == currentRows) return;

        this.currentCols = newCols;
        this.currentRows = newRows;

        log.info("Reconfiguring decoder dimensions to {}x{} at {:.2f}s", newCols, newRows, currentTimestampSeconds);
        grabber.stop();
        grabber.setImageWidth(newCols);
        grabber.setImageHeight(newRows);
        grabber.setPixelFormat(avutil.AV_PIX_FMT_RGB24);
        grabber.start();

        seekToTimestamp(currentTimestampSeconds);
    }

    public int getCurrentCols() {
        return currentCols;
    }

    public int getCurrentRows() {
        return currentRows;
    }

    public int getOriginalWidth() {
        return originalWidth;
    }

    public int getOriginalHeight() {
        return originalHeight;
    }

    public double getFps() {
        return fps;
    }

    public double getDurationSeconds() {
        return durationSeconds;
    }

    public long getTotalFrames() {
        return totalFrames;
    }

    @Override
    public void close() {
        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
                log.warn("Error stopping grabber: {}", e.getMessage());
            }
            try {
                grabber.release();
            } catch (Exception e) {
                log.warn("Error releasing grabber: {}", e.getMessage());
            }
            grabber = null;
        }
    }
}
