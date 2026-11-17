package com.asciistreamer.processing;

import org.bytedeco.javacv.Frame;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * High-performance ASCII mapper utilizing bulk memory copies, integer bit-shift luminance,
 * and direct array operations to eliminate JNI bounds-checking overhead.
 */
public class AsciiMapper {

    public static final String RAMP_STANDARD = " .:-=+*#%@";
    public static final String RAMP_EXTENDED = " .'`^\",:;Il!i><~+_-?][}{1)(|\\/tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$";
    public static final String RAMP_BLOCK = " ░▒▓█";

    private volatile String currentRamp = RAMP_STANDARD;
    private volatile byte[] rampBytes = RAMP_STANDARD.getBytes(StandardCharsets.US_ASCII);
    private volatile boolean invert = false;

    // Reusable row buffer for stride copying when RGB is disabled
    private byte[] rowBuffer = new byte[4096];

    public AsciiMapper() {
    }

    public void setCharset(String charsetName) {
        if ("extended".equalsIgnoreCase(charsetName)) {
            this.currentRamp = RAMP_EXTENDED;
        } else if ("block".equalsIgnoreCase(charsetName)) {
            this.currentRamp = RAMP_BLOCK;
        } else {
            this.currentRamp = RAMP_STANDARD;
        }
        this.rampBytes = this.currentRamp.getBytes(StandardCharsets.UTF_8);
    }

    public void setInvert(boolean invert) {
        this.invert = invert;
    }

    public record ProcessedFrame(byte[] asciiChars, byte[] rgbData) {}

    /**
     * Fast frame processing:
     *  1. Bulk copies native memory into Java heap arrays in bulk rows (or single memcpy if unpadded).
     *  2. Calculates luminance using fast integer arithmetic: (R * 77 + G * 150 + B * 29) >> 8.
     *  3. Eliminates individual ByteBuffer.get() calls and floating-point conversions.
     */
    public ProcessedFrame process(Frame frame, int cols, int rows, boolean includeRgb) {
        int n = cols * rows;
        if (frame == null || frame.image == null || frame.image.length == 0) {
            return new ProcessedFrame(new byte[n], includeRgb ? new byte[n * 3] : null);
        }

        ByteBuffer buffer = (ByteBuffer) frame.image[0];
        int stride = frame.imageStride;
        int usefulBytesPerRow = cols * 3;
        int totalRgbBytes = n * 3;

        byte[] asciiChars = new byte[n];
        byte[] compactRgb = includeRgb ? new byte[totalRgbBytes] : null;

        // --- STEP 1: BULK MEMORY COPY ---
        if (includeRgb && compactRgb != null) {
            if (stride == usefulBytesPerRow) {
                // Entire frame is unpadded: copy whole buffer in a single native memcpy!
                buffer.position(0);
                buffer.get(compactRgb, 0, Math.min(buffer.remaining(), totalRgbBytes));
            } else {
                // Padded rows: copy row-by-row in bulk (e.g. 135 memcpy calls instead of 194,400 calls)
                for (int r = 0; r < rows; r++) {
                    buffer.position(r * stride);
                    int destOffset = r * usefulBytesPerRow;
                    int bytesToRead = Math.min(buffer.remaining(), usefulBytesPerRow);
                    buffer.get(compactRgb, destOffset, bytesToRead);
                }
            }

            // --- STEP 2: FAST INTEGER LUMINANCE FROM HEAP ARRAY ---
            byte[] ramp = this.rampBytes;
            int rampLen = ramp.length;
            int rampMaxIdx = rampLen - 1;
            boolean inv = this.invert;

            for (int i = 0, p = 0; i < n; i++, p += 3) {
                int red = compactRgb[p] & 0xFF;
                int green = compactRgb[p + 1] & 0xFF;
                int blue = compactRgb[p + 2] & 0xFF;

                // Integer bit-shift luminance: 77/256 ~ 0.300, 150/256 ~ 0.586, 29/256 ~ 0.113
                int brightness = (red * 77 + green * 150 + blue * 29) >> 8;
                if (inv) {
                    brightness = 255 - brightness;
                }

                int charIndex = (brightness * rampMaxIdx) / 255;
                if (charIndex < 0) charIndex = 0;
                if (charIndex >= rampLen) charIndex = rampMaxIdx;

                asciiChars[i] = ramp[charIndex];
            }
        } else {
            // Monochrome only: process row-by-row using reusable buffer
            if (rowBuffer.length < usefulBytesPerRow) {
                rowBuffer = new byte[usefulBytesPerRow];
            }

            byte[] ramp = this.rampBytes;
            int rampLen = ramp.length;
            int rampMaxIdx = rampLen - 1;
            boolean inv = this.invert;
            int pixelIndex = 0;

            for (int r = 0; r < rows; r++) {
                buffer.position(r * stride);
                int bytesToRead = Math.min(buffer.remaining(), usefulBytesPerRow);
                buffer.get(rowBuffer, 0, bytesToRead);

                for (int c = 0, p = 0; c < cols; c++, p += 3) {
                    int red = rowBuffer[p] & 0xFF;
                    int green = rowBuffer[p + 1] & 0xFF;
                    int blue = rowBuffer[p + 2] & 0xFF;

                    int brightness = (red * 77 + green * 150 + blue * 29) >> 8;
                    if (inv) {
                        brightness = 255 - brightness;
                    }

                    int charIndex = (brightness * rampMaxIdx) / 255;
                    if (charIndex < 0) charIndex = 0;
                    if (charIndex >= rampLen) charIndex = rampMaxIdx;

                    asciiChars[pixelIndex++] = ramp[charIndex];
                }
            }
        }

        return new ProcessedFrame(asciiChars, compactRgb);
    }
}
