package com.asciistreamer.model;

/**
 * Immutable snapshot of the client's playhead position and monotonic timestamp.
 * Storing this in a single volatile reference prevents torn reads between micros and nanos.
 */
public record Playhead(long micros, long nanos) {
}
