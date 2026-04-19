package com.crawler.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class CrawlTask {
    private final String url;
    private final String originUrl;
    private final int depth;
    private final String discoveredBy;
    private final long discoveredAtEpochMs;

    @JsonCreator
    public CrawlTask(
            @JsonProperty("url") String url,
            @JsonProperty("originUrl") String originUrl,
            @JsonProperty("depth") int depth,
            @JsonProperty("discoveredBy") String discoveredBy,
            @JsonProperty("discoveredAtEpochMs") long discoveredAtEpochMs
    ) {
        this.url = url;
        this.originUrl = originUrl;
        this.depth = depth;
        this.discoveredBy = discoveredBy;
        this.discoveredAtEpochMs = discoveredAtEpochMs;
    }

    public String getUrl() {
        return url;
    }

    public String getOriginUrl() {
        return originUrl;
    }

    public int getDepth() {
        return depth;
    }

    public String getDiscoveredBy() {
        return discoveredBy;
    }

    public long getDiscoveredAtEpochMs() {
        return discoveredAtEpochMs;
    }
}
