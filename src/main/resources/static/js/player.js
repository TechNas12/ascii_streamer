/**
 * High-Performance HTML5 Canvas ASCII Rendering Engine.
 * Features:
 *  - Color-run batching (groups contiguous identical-color characters to slash draw calls)
 *  - Full-row batching for monochrome mode (1 draw call per line instead of per character)
 *  - 4-bit quantized color caching
 *  - HiDPI / Retina devicePixelRatio scaling for razor-sharp characters
 *  - Sub-pixel font scaling down to 2px for Ultra-HD ASCII resolutions (320-480+ cols)
 */
class AsciiPlayer {
    constructor(canvas, aspectBox) {
        this.canvas = canvas;
        this.ctx = canvas.getContext('2d', { alpha: false });
        this.aspectBox = aspectBox;

        this.fontSize = 10;
        this.fontFamily = '"JetBrains Mono", "Courier New", monospace';
        this.charWidth = 6;
        this.charHeight = 12;
        this.fontAspectRatio = 0.5;

        this.lastRenderedPts = -1;
        this.colorCache = new Map();
        this.dpr = Math.min(window.devicePixelRatio || 1, 2); // Cap at 2 for performance

        this.measureFontGeometry();
    }

    measureFontGeometry() {
        const ruler = document.getElementById('fontRuler');
        if (ruler) {
            const rect = ruler.getBoundingClientRect();
            if (rect.width > 0 && rect.height > 0) {
                this.fontAspectRatio = rect.width / rect.height;
            }
        }
        this.updateCharMetrics();
    }

    setFontSize(sizePx) {
        this.fontSize = Math.max(2, Math.min(36, sizePx));
        this.updateCharMetrics();
    }

    updateCharMetrics() {
        this.ctx.font = `${this.fontSize}px ${this.fontFamily}`;
        const metrics = this.ctx.measureText('M');
        this.charWidth = metrics.width || (this.fontSize * 0.6);
        this.charHeight = this.charWidth / this.fontAspectRatio;
    }

    getFontAspectRatio() {
        return this.fontAspectRatio;
    }

    /**
     * Quantize RGB to 4 bits per channel and cache fillStyle strings.
     */
    getQuantizedFillStyle(r, g, b) {
        const qr = (r >> 4) << 4;
        const qg = (g >> 4) << 4;
        const qb = (b >> 4) << 4;
        const key = (qr << 16) | (qg << 8) | qb;

        let style = this.colorCache.get(key);
        if (!style) {
            style = `rgb(${qr},${qg},${qb})`;
            this.colorCache.set(key, style);
        }
        return style;
    }

    /**
     * Ultra-fast batched frame renderer.
     */
    renderFrame(frame) {
        if (!frame || frame.pts === this.lastRenderedPts) {
            return;
        }
        this.lastRenderedPts = frame.pts;

        const { cols, rows, charData, rgbData, mode } = frame;

        // Logical CSS dimensions vs physical canvas backing buffer
        const cssWidth = Math.ceil(cols * this.charWidth);
        const cssHeight = Math.ceil(rows * this.charHeight);

        const physWidth = Math.round(cssWidth * this.dpr);
        const physHeight = Math.round(cssHeight * this.dpr);

        if (this.canvas.width !== physWidth || this.canvas.height !== physHeight) {
            this.canvas.width = physWidth;
            this.canvas.height = physHeight;
            this.canvas.style.width = '100%';
            this.canvas.style.height = '100%';
        }

        // Reset transform and clear
        this.ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
        this.ctx.fillStyle = '#0a0c10';
        this.ctx.fillRect(0, 0, cssWidth, cssHeight);

        this.ctx.font = `${this.fontSize}px ${this.fontFamily}`;
        this.ctx.textBaseline = 'top';

        const isColor = (mode === 1 && rgbData !== null);

        if (!isColor) {
            // --- MONOCHROME ROW BATCHING ---
            // Concatenates entire row into 1 string: reduces draw calls to rows (e.g. 135 calls instead of 64,800!)
            this.ctx.fillStyle = '#e2e8f0';
            let ptr = 0;
            for (let r = 0; r < rows; r++) {
                let rowStr = '';
                for (let c = 0; c < cols; c++) {
                    rowStr += String.fromCharCode(charData[ptr++]);
                }
                this.ctx.fillText(rowStr, 0, r * this.charHeight);
            }
        } else {
            // --- COLOR-RUN BATCHING ---
            // Aggregates contiguous cells of identical quantized color into single fillText calls
            let ptr = 0;
            for (let r = 0; r < rows; r++) {
                const y = r * this.charHeight;
                let runChars = '';
                let runStartX = 0;
                let currentStyle = null;

                for (let c = 0; c < cols; c++) {
                    const charCode = charData[ptr];
                    const rVal = rgbData[ptr * 3];
                    const gVal = rgbData[ptr * 3 + 1];
                    const bVal = rgbData[ptr * 3 + 2];
                    const style = this.getQuantizedFillStyle(rVal, gVal, bVal);

                    if (style === currentStyle) {
                        runChars += String.fromCharCode(charCode);
                    } else {
                        if (runChars.length > 0) {
                            this.ctx.fillStyle = currentStyle;
                            this.ctx.fillText(runChars, runStartX, y);
                        }
                        currentStyle = style;
                        runChars = String.fromCharCode(charCode);
                        runStartX = c * this.charWidth;
                    }
                    ptr++;
                }

                if (runChars.length > 0) {
                    this.ctx.fillStyle = currentStyle;
                    this.ctx.fillText(runChars, runStartX, y);
                }
            }
        }
    }

    autoFit(cols, rows) {
        if (!this.aspectBox) return;
        const rect = this.aspectBox.getBoundingClientRect();
        if (rect.width <= 0 || cols <= 0) return;

        const desiredCharWidth = rect.width / cols;
        const calculatedFontSize = Math.round(desiredCharWidth / 0.6);
        const clampedSize = Math.max(2, Math.min(28, calculatedFontSize));

        this.setFontSize(clampedSize);
        return clampedSize;
    }

    clear() {
        this.ctx.fillStyle = '#000000';
        this.ctx.fillRect(0, 0, this.canvas.width, this.canvas.height);
        this.lastRenderedPts = -1;
    }
}

window.AsciiPlayer = AsciiPlayer;
