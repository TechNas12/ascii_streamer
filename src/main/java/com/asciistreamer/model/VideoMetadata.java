package com.asciistreamer.model;

public record VideoMetadata(
    String sessionId,
    double fps,
    double durationSeconds,
    long totalFrames,
    int originalWidth,
    int originalHeight,
    int defaultCols,
    int defaultRows
) {
}
