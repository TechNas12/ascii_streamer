/**
 * ASC1 Binary Protocol Parser
 *
 * Header Layout (Big-Endian, 18 bytes):
 *  0..3   : Magic "ASC1"
 *  4..11  : PTS (Float64)
 *  12..13 : Cols (Uint16)
 *  14..15 : Rows (Uint16)
 *  16     : Mode (Uint8: 0 = mono, 1 = RGB)
 *  17     : Generation (Uint8: epoch counter)
 *  18..   : ASCII chars (cols * rows)
 *  Followed by RGB (cols * rows * 3) if Mode == 1
 */
const ASC1Protocol = {
    HEADER_SIZE: 18,
    MAGIC: 0x41534331, // 'ASC1'

    parse(arrayBuffer) {
        if (!arrayBuffer || arrayBuffer.byteLength < this.HEADER_SIZE) {
            return null;
        }

        const view = new DataView(arrayBuffer);
        const magic = view.getUint32(0, false);
        if (magic !== this.MAGIC) {
            console.warn("Invalid packet magic: 0x" + magic.toString(16));
            return null;
        }

        const pts = view.getFloat64(4, false);
        const cols = view.getUint16(12, false);
        const rows = view.getUint16(14, false);
        const mode = view.getUint8(16);
        const generation = view.getUint8(17); // Fix 3: Byte offset 17 is generation

        const n = cols * rows;
        if (arrayBuffer.byteLength < this.HEADER_SIZE + n) {
            return null;
        }

        const charData = new Uint8Array(arrayBuffer, this.HEADER_SIZE, n);
        let rgbData = null;

        if (mode === 1 && arrayBuffer.byteLength >= this.HEADER_SIZE + n + (n * 3)) {
            rgbData = new Uint8Array(arrayBuffer, this.HEADER_SIZE + n, n * 3);
        }

        return {
            pts,
            cols,
            rows,
            mode,
            generation,
            charData,
            rgbData
        };
    }
};

window.ASC1Protocol = ASC1Protocol;
