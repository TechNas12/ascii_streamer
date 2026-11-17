/**
 * Main Application Orchestrator: File Upload, WebSocket, UI Controls, and Animation Loop.
 */
document.addEventListener('DOMContentLoaded', () => {
    // Elements
    const canvas = document.getElementById('asciiCanvas');
    const aspectBox = document.getElementById('aspectBox');
    const dropOverlay = document.getElementById('dropOverlay');
    const loadingOverlay = document.getElementById('loadingOverlay');
    const loadingText = document.getElementById('loadingText');
    const videoFileInput = document.getElementById('videoFileInput');

    const playPauseBtn = document.getElementById('playPauseBtn');
    const playIcon = document.getElementById('playIcon');
    const playText = document.getElementById('playText');
    const stepBackBtn = document.getElementById('stepBackBtn');
    const stepForwardBtn = document.getElementById('stepForwardBtn');
    const fullscreenBtn = document.getElementById('fullscreenBtn');

    const seekBar = document.getElementById('seekBar');
    const currentTimeLabel = document.getElementById('currentTime');
    const totalTimeLabel = document.getElementById('totalTime');

    const colsSlider = document.getElementById('colsSlider');
    const colsVal = document.getElementById('colsVal');
    const gridDimensions = document.getElementById('gridDimensions');
    const presetButtons = document.querySelectorAll('.preset-buttons .btn-chip');

    const fontSlider = document.getElementById('fontSlider');
    const fontVal = document.getElementById('fontVal');
    const autoFitBtn = document.getElementById('autoFitBtn');

    const colorToggle = document.getElementById('colorToggle');
    const invertToggle = document.getElementById('invertToggle');
    const charsetSelect = document.getElementById('charsetSelect');

    const connectionBadge = document.getElementById('connectionBadge');
    const leadBadge = document.getElementById('leadBadge');

    const downloadVideoBtn = document.getElementById('downloadVideoBtn');
    const exportModal = document.getElementById('exportModal');
    const exportProgressBar = document.getElementById('exportProgressBar');
    const exportProgressPct = document.getElementById('exportProgressPct');
    const exportStatusText = document.getElementById('exportStatusText');
    const exportSuccessActions = document.getElementById('exportSuccessActions');
    const directDownloadLink = document.getElementById('directDownloadLink');
    const closeModalBtn = document.getElementById('closeModalBtn');
    const finishModalBtn = document.getElementById('finishModalBtn');
    let exportPollingInterval = null;

    // State
    let currentSessionId = null;
    let ws = null;
    let videoDuration = 0;
    let currentCols = 160;
    let currentRows = 45;
    let isUserSeeking = false;
    let resizeDebounceTimer = null;

    // Instances
    const player = new AsciiPlayer(canvas, aspectBox);
    const clock = new StreamClock((leadMs, queueLen) => {
        leadBadge.textContent = `Lead: ${leadMs}ms (${queueLen}f)`;
        if (leadMs < 100) {
            leadBadge.style.color = '#f59e0b';
        } else {
            leadBadge.style.color = '#38bdf8';
        }
    });

    // --- Upload & Session Initialization ---

    function handleFileSelected(file) {
        if (!file) return;

        dropOverlay.classList.remove('active');
        loadingOverlay.classList.add('active');
        loadingText.textContent = `Uploading ${file.name}...`;

        const formData = new FormData();
        formData.append('file', file);

        fetch('/api/upload', {
            method: 'POST',
            body: formData
        })
        .then(res => {
            if (!res.ok) throw new Error(`Upload failed with status ${res.status}`);
            return res.json();
        })
        .then(metadata => {
            loadingText.textContent = "Connecting stream...";
            initStreamingSession(metadata);
        })
        .catch(err => {
            console.error("Upload error:", err);
            loadingOverlay.classList.remove('active');
            dropOverlay.classList.add('active');
            alert("Upload failed: " + err.message);
        });
    }

    videoFileInput.addEventListener('change', (e) => {
        if (e.target.files.length > 0) {
            handleFileSelected(e.target.files[0]);
        }
    });

    // Drag and drop handlers
    aspectBox.addEventListener('dragover', (e) => {
        e.preventDefault();
        aspectBox.style.borderColor = 'var(--accent)';
    });

    aspectBox.addEventListener('dragleave', (e) => {
        e.preventDefault();
        aspectBox.style.borderColor = 'var(--border-color)';
    });

    aspectBox.addEventListener('drop', (e) => {
        e.preventDefault();
        aspectBox.style.borderColor = 'var(--border-color)';
        if (e.dataTransfer.files.length > 0) {
            handleFileSelected(e.dataTransfer.files[0]);
        }
    });

    // --- WebSocket Streaming Setup ---

    function initStreamingSession(metadata) {
        if (ws) {
            ws.close();
        }

        currentSessionId = metadata.sessionId;
        videoDuration = metadata.durationSeconds;
        currentCols = metadata.defaultCols || 160;
        currentRows = metadata.defaultRows || 45;

        colsSlider.value = currentCols;
        colsVal.textContent = currentCols;
        updateDimensionsLabel(currentCols, currentRows);

        totalTimeLabel.textContent = formatTime(videoDuration);
        seekBar.max = videoDuration;
        seekBar.disabled = false;
        playPauseBtn.disabled = false;
        stepBackBtn.disabled = false;
        stepForwardBtn.disabled = false;
        downloadVideoBtn.disabled = false;

        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const wsUrl = `${protocol}//${window.location.host}/ws/stream?sessionId=${currentSessionId}`;

        ws = new WebSocket(wsUrl);
        ws.binaryType = 'arraybuffer';
        clock.setWebSocket(ws);

        ws.onopen = () => {
            connectionBadge.textContent = 'Live';
            connectionBadge.className = 'badge badge-online';
            loadingOverlay.classList.remove('active');
            player.autoFit(currentCols, currentRows);
            syncConfig();
        };

        ws.onmessage = (event) => {
            if (event.data instanceof ArrayBuffer) {
                // Binary frame packet
                const frame = ASC1Protocol.parse(event.data);
                if (frame) {
                    clock.pushFrame(frame);
                }
            } else {
                // Text control JSON
                try {
                    const msg = JSON.parse(event.data);
                    if (msg.type === 'metadata') {
                        if (msg.cols && msg.rows) {
                            currentCols = msg.cols;
                            currentRows = msg.rows;
                            updateDimensionsLabel(currentCols, currentRows);
                        }
                    } else if (msg.type === 'ended') {
                        pause();
                    } else if (msg.type === 'error') {
                        alert("Stream error: " + msg.message);
                    }
                } catch (e) {
                    console.error("Failed to parse text message:", e);
                }
            }
        };

        ws.onclose = () => {
            connectionBadge.textContent = 'Disconnected';
            connectionBadge.className = 'badge badge-offline';
            pause();
        };

        ws.onerror = (err) => {
            console.error("WebSocket error:", err);
        };
    }

    // --- Animation & Presentation Loop ---

    function animationLoop() {
        requestAnimationFrame(animationLoop);

        // Demand-driven frame presentation from client timing authority
        const frame = clock.dequeueReadyFrame();
        if (frame) {
            player.renderFrame(frame);

            if (!isUserSeeking) {
                const currentPts = clock.getPlayhead();
                seekBar.value = currentPts;
                currentTimeLabel.textContent = formatTime(currentPts);
            }
        }
    }
    requestAnimationFrame(animationLoop);

    // --- Playback Controls ---

    function play() {
        if (!ws || ws.readyState !== WebSocket.OPEN) return;
        clock.play();
        playIcon.textContent = '⏸';
        playText.textContent = 'Pause';
        ws.send(JSON.stringify({ action: 'play' }));
    }

    function pause() {
        if (!ws) return;
        clock.pause();
        playIcon.textContent = '▶';
        playText.textContent = 'Play';
        if (ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ action: 'pause' }));
        }
    }

    function togglePlay() {
        if (clock.isPlaying) {
            pause();
        } else {
            play();
        }
    }

    playPauseBtn.addEventListener('click', togglePlay);

    // Seeking
    seekBar.addEventListener('input', () => {
        isUserSeeking = true;
        currentTimeLabel.textContent = formatTime(parseFloat(seekBar.value));
    });

    seekBar.addEventListener('change', () => {
        const targetTs = parseFloat(seekBar.value);
        clock.seek(targetTs);
        isUserSeeking = false;
    });

    stepBackBtn.addEventListener('click', () => {
        const target = Math.max(0, clock.getPlayhead() - 5.0);
        seekBar.value = target;
        currentTimeLabel.textContent = formatTime(target);
        clock.seek(target);
    });

    stepForwardBtn.addEventListener('click', () => {
        const target = Math.min(videoDuration, clock.getPlayhead() + 5.0);
        seekBar.value = target;
        currentTimeLabel.textContent = formatTime(target);
        clock.seek(target);
    });

    // --- Size Controls (Cols slider & presets) ---

    function updateDimensionsLabel(cols, rows) {
        gridDimensions.textContent = `${cols} cols × ${rows} rows`;
    }

    function triggerResize(newCols) {
        currentCols = newCols;
        colsVal.textContent = newCols;
        currentRows = Math.round(newCols * (9.0 / 16.0) * player.getFontAspectRatio());
        updateDimensionsLabel(currentCols, currentRows);

        // Highlight matching preset chip
        presetButtons.forEach(btn => {
            btn.classList.toggle('active', parseInt(btn.dataset.cols) === newCols);
        });

        // Bug 4: Debounce resize commands to server
        clearTimeout(resizeDebounceTimer);
        resizeDebounceTimer = setTimeout(() => {
            clock.onResize(newCols, player.getFontAspectRatio());
            player.autoFit(currentCols, currentRows);
        }, 300);
    }

    colsSlider.addEventListener('input', () => {
        triggerResize(parseInt(colsSlider.value));
    });

    presetButtons.forEach(btn => {
        btn.addEventListener('click', () => {
            const cols = parseInt(btn.dataset.cols);
            colsSlider.value = cols;
            triggerResize(cols);
        });
    });

    // Font Scale
    fontSlider.addEventListener('input', () => {
        const size = parseInt(fontSlider.value);
        fontVal.textContent = `${size}px`;
        player.setFontSize(size);
    });

    autoFitBtn.addEventListener('click', () => {
        const fittedSize = player.autoFit(currentCols, currentRows);
        if (fittedSize) {
            fontSlider.value = fittedSize;
            fontVal.textContent = `${fittedSize}px`;
        }
    });

    // Visual options
    function syncConfig() {
        if (!ws || ws.readyState !== WebSocket.OPEN) return;
        ws.send(JSON.stringify({
            action: 'config',
            color: colorToggle.checked,
            invert: invertToggle.checked,
            charset: charsetSelect.value
        }));
    }

    colorToggle.addEventListener('change', syncConfig);
    invertToggle.addEventListener('change', syncConfig);
    charsetSelect.addEventListener('change', syncConfig);

    // Fullscreen
    fullscreenBtn.addEventListener('click', () => {
        if (!document.fullscreenElement) {
            aspectBox.requestFullscreen().catch(err => console.warn(err));
        } else {
            document.exitFullscreen().catch(err => console.warn(err));
        }
    });

    // Keyboard shortcuts
    window.addEventListener('keydown', (e) => {
        if (e.target.tagName === 'INPUT' || e.target.tagName === 'SELECT') return;

        if (e.code === 'Space') {
            e.preventDefault();
            togglePlay();
        } else if (e.code === 'ArrowLeft') {
            e.preventDefault();
            stepBackBtn.click();
        } else if (e.code === 'ArrowRight') {
            e.preventDefault();
            stepForwardBtn.click();
        } else if (e.code === 'KeyF') {
            e.preventDefault();
            fullscreenBtn.click();
        } else if (e.code === 'KeyR') {
            e.preventDefault();
            autoFitBtn.click();
        }
    });

    // Auto-fit on window resize
    window.addEventListener('resize', () => {
        player.measureFontGeometry();
        player.autoFit(currentCols, currentRows);
    });

    // --- Export / Download ASCII Video with Audio ---
    downloadVideoBtn.addEventListener('click', () => {
        if (!currentSessionId) return;

        exportModal.classList.add('active');
        exportProgressBar.style.width = '0%';
        exportProgressPct.textContent = '0%';
        exportStatusText.textContent = 'Submitting export job...';
        exportSuccessActions.style.display = 'none';

        fetch('/api/export', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                sessionId: currentSessionId,
                cols: currentCols,
                color: colorToggle.checked,
                charset: charsetSelect.value,
                invert: invertToggle.checked,
                outputWidth: 1280,
                outputHeight: 720
            })
        })
        .then(res => {
            if (!res.ok) throw new Error('Failed to start export');
            return res.json();
        })
        .then(data => {
            pollExportProgress(data.jobId);
        })
        .catch(err => {
            exportStatusText.textContent = 'Export error: ' + err.message;
        });
    });

    function pollExportProgress(jobId) {
        if (exportPollingInterval) clearInterval(exportPollingInterval);

        exportPollingInterval = setInterval(() => {
            fetch(`/api/export/${jobId}/status`)
                .then(res => {
                    if (!res.ok) throw new Error('Failed to fetch status');
                    return res.json();
                })
                .then(progress => {
                    if (progress.status === 'RENDERING') {
                        exportProgressBar.style.width = `${progress.percent}%`;
                        exportProgressPct.textContent = `${progress.percent}%`;
                        exportStatusText.textContent = `Encoding ASCII frames with original audio... (${progress.percent}%)`;
                    } else if (progress.status === 'COMPLETED') {
                        clearInterval(exportPollingInterval);
                        exportProgressBar.style.width = '100%';
                        exportProgressPct.textContent = '100%';
                        exportStatusText.textContent = 'Rendering complete! Downloading video...';
                        exportSuccessActions.style.display = 'flex';
                        directDownloadLink.href = progress.downloadUrl;
                        // Trigger download
                        const dl = document.createElement('a');
                        dl.href = progress.downloadUrl;
                        dl.download = 'ascii_video.mp4';
                        document.body.appendChild(dl);
                        dl.click();
                        document.body.removeChild(dl);
                    } else if (progress.status === 'FAILED') {
                        clearInterval(exportPollingInterval);
                        exportStatusText.textContent = 'Export failed: ' + (progress.error || 'Unknown error');
                    }
                })
                .catch(err => {
                    console.error("Error polling export status:", err);
                });
        }, 750);
    }

    function closeExportModal() {
        exportModal.classList.remove('active');
        if (exportPollingInterval) {
            clearInterval(exportPollingInterval);
            exportPollingInterval = null;
        }
    }

    closeModalBtn.addEventListener('click', closeExportModal);
    finishModalBtn.addEventListener('click', closeExportModal);

    // Helpers
    function formatTime(seconds) {
        if (isNaN(seconds) || seconds < 0) seconds = 0;
        const mins = Math.floor(seconds / 60);
        const secs = Math.floor(seconds % 60);
        return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
    }
});
