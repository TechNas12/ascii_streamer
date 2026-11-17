package com.asciistreamer.processing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Encodes ASCII frames into the ASC1 binary protocol.
 *
 * Header Layout (Big-Endian, 18 bytes):
 *  0..3   : Magic "ASC1" (0x41, 0x53, 0x43, 0x31)
 *  4..11  : PTS (double, 8 bytes)
 *  12..13 : Cols (uint16, 2 bytes)
 *  14..15 : Rows (uint16, 2 bytes)
 *  16     : Mode (uint8, 1 byte: 0 = Mono, 1 = RGB)
 *  17     : Generation (uint8, 1 byte: wrapping epoch from client)
 *  18..   : ASCII chars (cols * rows bytes)
 *  Followed by RGB data (cols * rows * 3 bytes) if Mode == 1
 */
public class BinaryProtocol {
    public static final byte[] MAGIC = new byte[] { 0x41, 0x53, 0x43, 0x31 }; // "ASC1"
    public static final int HEADER_SIZE = 18;
    public static final byte MODE_MONO = 0x00;
    public static final byte MODE_COLOR = 0x01;

    public static byte[] buildPacket(double pts, int cols, int rows, byte generation,
                                     byte[] asciiChars, byte[] rgbData, boolean isColor) {
        int n = cols * rows;
        int totalSize = HEADER_SIZE + n + (isColor && rgbData != null ? n * 3 : 0);
        ByteBuffer buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN);

        // Header (0..17)
        buffer.put(MAGIC);                                  // 0..3
        buffer.putDouble(pts);                              // 4..11
        buffer.putShort((short) cols);                      // 12..13
        buffer.putShort((short) rows);                      // 14..15
        buffer.put(isColor && rgbData != null ? MODE_COLOR : MODE_MONO); // 16
        buffer.put(generation);                             // 17 (Generation byte)

        // Payload
        buffer.put(asciiChars, 0, Math.min(asciiChars.length, n));
        if (isColor && rgbData != null) {
            buffer.put(rgbData, 0, Math.min(rgbData.length, n * 3));
        }

        return buffer.array();
    }
}
