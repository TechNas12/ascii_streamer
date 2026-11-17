package com.asciistreamer.service;

import com.asciistreamer.model.ExportProgress;
import com.asciistreamer.model.ExportRequest;
import com.asciistreamer.processing.AsciiMapper;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service that exports an ASCII-rendered video file (.mp4) bundled with the original audio track.
 */
@Service
public class AsciiVideoExporter {

    private static final Logger log = LoggerFactory.getLogger(AsciiVideoExporter.class);

    private final ConcurrentHashMap<String, ExportProgress> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Path> jobFiles = new ConcurrentHashMap<>();
    private final ExecutorService exportExecutor = Executors.newFixedThreadPool(2, r -> new Thread(r, "ascii-export-thread"));

    @Value("${app.export-dir:./exports}")
    private String exportDir;

    public String submitExportJob(String sourceVideoPath, ExportRequest request) throws Exception {
        String jobId = UUID.randomUUID().toString();
        Path outDir = Paths.get(exportDir).toAbsolutePath().normalize();
        if (!Files.exists(outDir)) {
            Files.createDirectories(outDir);
        }

        Path outputFile = outDir.resolve("ascii_" + jobId + ".mp4");
        jobFiles.put(jobId, outputFile);

        ExportProgress initial = new ExportProgress(jobId, 0, "QUEUED", null, null);
        jobs.put(jobId, initial);

        exportExecutor.submit(() -> executeExport(jobId, sourceVideoPath, outputFile, request));

        return jobId;
    }

    public ExportProgress getJobStatus(String jobId) {
        return jobs.get(jobId);
    }

    public Path getExportedFilePath(String jobId) {
        return jobFiles.get(jobId);
    }

    private void executeExport(String jobId, String sourceVideoPath, Path outputFile, ExportRequest request) {
        log.info("Starting ASCII video export for job {} from {}", jobId, sourceVideoPath);
        jobs.put(jobId, new ExportProgress(jobId, 1, "RENDERING", null, null));

        File inputFile = new File(sourceVideoPath);
        if (!inputFile.exists()) {
            jobs.put(jobId, new ExportProgress(jobId, 0, "FAILED", null, "Source video file not found"));
            return;
        }

        int cols = request.getCols();
        double fontAspect = 0.5;
        int rows = (int) Math.round(cols * (9.0 / 16.0) * fontAspect);

        int outWidth = request.getOutputWidth();
        int outHeight = request.getOutputHeight();
        boolean color = request.getColor();

        AsciiMapper mapper = new AsciiMapper();
        mapper.setCharset(request.getCharset());
        mapper.setInvert(request.getInvert());

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputFile)) {
            // Configure grabber to downscale video natively to cols x rows
            grabber.setImageWidth(cols);
            grabber.setImageHeight(rows);
            grabber.setPixelFormat(avutil.AV_PIX_FMT_RGB24);
            grabber.start();

            double fps = grabber.getFrameRate() > 0 ? grabber.getFrameRate() : 30.0;
            long totalVideoFrames = grabber.getLengthInVideoFrames();
            if (totalVideoFrames <= 0) {
                totalVideoFrames = (long) (grabber.getLengthInTime() / 1_000_000.0 * fps);
            }
            if (totalVideoFrames <= 0) totalVideoFrames = 1000;

            int audioChannels = grabber.getAudioChannels();
            int sampleRate = grabber.getSampleRate();

            try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputFile.toFile(), outWidth, outHeight, audioChannels)) {
                recorder.setFormat("mp4");
                recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
                recorder.setFrameRate(fps);
                recorder.setVideoBitrate(5_000_000); // 5 Mbps clean H.264
                recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);

                // Configure audio track if original video contains audio
                if (audioChannels > 0) {
                    recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
                    recorder.setSampleRate(sampleRate > 0 ? sampleRate : 44100);
                    recorder.setAudioBitrate(192_000);
                }

                recorder.start();

                // Rasterization canvas
                BufferedImage raster = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_3BYTE_BGR);
                Graphics2D g = raster.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);

                float cellW = (float) outWidth / cols;
                float cellH = (float) outHeight / rows;
                int fontSize = Math.max(6, Math.round(cellH * 0.92f));
                Font font = new Font(Font.MONOSPACED, Font.BOLD, fontSize);
                g.setFont(font);

                Java2DFrameConverter converter = new Java2DFrameConverter();

                long videoFramesProcessed = 0;
                Frame frame;

                while ((frame = grabber.grab()) != null) {
                    if (frame.image != null) {
                        // Video frame: convert to ASCII and draw onto canvas
                        AsciiMapper.ProcessedFrame processed = mapper.process(frame, cols, rows, color);

                        // Clear background
                        g.setColor(Color.BLACK);
                        g.fillRect(0, 0, outWidth, outHeight);

                        byte[] chars = processed.asciiChars();
                        byte[] rgb = processed.rgbData();
                        int ptr = 0;

                        if (!color || rgb == null) {
                            // Monochrome: draw line-by-line
                            g.setColor(new Color(230, 235, 245));
                            for (int r = 0; r < rows; r++) {
                                StringBuilder sb = new StringBuilder(cols);
                                for (int c = 0; c < cols; c++) {
                                    sb.append((char) chars[ptr++]);
                                }
                                int y = Math.round((r + 1) * cellH - (cellH * 0.15f));
                                g.drawString(sb.toString(), 0, y);
                            }
                        } else {
                            // Color: draw runs of same color for speed
                            for (int r = 0; r < rows; r++) {
                                int y = Math.round((r + 1) * cellH - (cellH * 0.15f));
                                StringBuilder runStr = new StringBuilder();
                                float runStartX = 0;
                                Color currentColor = null;

                                for (int c = 0; c < cols; c++) {
                                    char ch = (char) chars[ptr];
                                    int red = rgb[ptr * 3] & 0xFF;
                                    int green = rgb[ptr * 3 + 1] & 0xFF;
                                    int blue = rgb[ptr * 3 + 2] & 0xFF;
                                    Color col = new Color(red, green, blue);

                                    if (currentColor == null) {
                                        currentColor = col;
                                        runStr.append(ch);
                                        runStartX = c * cellW;
                                    } else if (currentColor.equals(col)) {
                                        runStr.append(ch);
                                    } else {
                                        g.setColor(currentColor);
                                        g.drawString(runStr.toString(), runStartX, y);
                                        currentColor = col;
                                        runStr.setLength(0);
                                        runStr.append(ch);
                                        runStartX = c * cellW;
                                    }
                                    ptr++;
                                }
                                if (runStr.length() > 0 && currentColor != null) {
                                    g.setColor(currentColor);
                                    g.drawString(runStr.toString(), runStartX, y);
                                }
                            }
                        }

                        // Record converted frame
                        Frame recordFrame = converter.convert(raster);
                        recordFrame.timestamp = frame.timestamp;
                        recorder.record(recordFrame);

                        videoFramesProcessed++;
                        if (videoFramesProcessed % 15 == 0) {
                            int pct = (int) Math.min(99, (videoFramesProcessed * 100.0) / totalVideoFrames);
                            jobs.put(jobId, new ExportProgress(jobId, pct, "RENDERING", null, null));
                        }
                    } else if (frame.samples != null && audioChannels > 0) {
                        // Audio frame: forward original audio samples
                        recorder.recordSamples(frame.sampleRate, frame.audioChannels, frame.samples);
                    }
                }

                g.dispose();
                recorder.stop();
            }

            grabber.stop();
            log.info("ASCII video export completed for job {}", jobId);
            String downloadUrl = "/api/export/" + jobId + "/download";
            jobs.put(jobId, new ExportProgress(jobId, 100, "COMPLETED", downloadUrl, null));

        } catch (Throwable t) {
            log.error("Failed to render ASCII video for job {}", jobId, t);
            jobs.put(jobId, new ExportProgress(jobId, 0, "FAILED", null, t.getMessage()));
        }
    }
}
