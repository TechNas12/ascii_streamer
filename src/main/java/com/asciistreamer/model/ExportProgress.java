package com.asciistreamer.model;

public record ExportProgress(
    String jobId,
    int percent,
    String status,
    String downloadUrl,
    String error
) {}
