package com.asciistreamer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Incoming WebSocket commands from the client.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StreamCommand {
    private String action;
    private Double timestamp;
    private Integer generation;
    private Integer cols;
    private Double fontAspect;
    private Double playhead;
    private Boolean color;
    private String charset;
    private Boolean invert;

    public StreamCommand() {
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Double getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Double timestamp) {
        this.timestamp = timestamp;
    }

    public Integer getGeneration() {
        return generation;
    }

    public void setGeneration(Integer generation) {
        this.generation = generation;
    }

    public Integer getCols() {
        return cols;
    }

    public void setCols(Integer cols) {
        this.cols = cols;
    }

    public Double getFontAspect() {
        return fontAspect;
    }

    public void setFontAspect(Double fontAspect) {
        this.fontAspect = fontAspect;
    }

    public Double getPlayhead() {
        return playhead;
    }

    public void setPlayhead(Double playhead) {
        this.playhead = playhead;
    }

    public Boolean getColor() {
        return color;
    }

    public void setColor(Boolean color) {
        this.color = color;
    }

    public String getCharset() {
        return charset;
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    public Boolean getInvert() {
        return invert;
    }

    public void setInvert(Boolean invert) {
        this.invert = invert;
    }
}
