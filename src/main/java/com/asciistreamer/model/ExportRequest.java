package com.asciistreamer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ExportRequest {
    private String sessionId;
    private Integer cols = 160;
    private Boolean color = true;
    private String charset = "standard";
    private Boolean invert = false;
    private Integer outputWidth = 1280;
    private Integer outputHeight = 720;

    public ExportRequest() {}

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Integer getCols() {
        return cols != null ? cols : 160;
    }

    public void setCols(Integer cols) {
        this.cols = cols;
    }

    public Boolean getColor() {
        return color != null ? color : true;
    }

    public void setColor(Boolean color) {
        this.color = color;
    }

    public String getCharset() {
        return charset != null ? charset : "standard";
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    public Boolean getInvert() {
        return invert != null ? invert : false;
    }

    public void setInvert(Boolean invert) {
        this.invert = invert;
    }

    public Integer getOutputWidth() {
        return outputWidth != null ? outputWidth : 1280;
    }

    public void setOutputWidth(Integer outputWidth) {
        this.outputWidth = outputWidth;
    }

    public Integer getOutputHeight() {
        return outputHeight != null ? outputHeight : 720;
    }

    public void setOutputHeight(Integer outputHeight) {
        this.outputHeight = outputHeight;
    }
}
