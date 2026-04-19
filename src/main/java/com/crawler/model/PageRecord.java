package com.crawler.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class PageRecord {
    private final String url;
    private final String originUrl;
    private final int depth;
    private final long fetchedAtEpochMs;
    private final String text;
    private final List<String> outgoingLinks;
    private final String discoveredBy;

    @JsonCreator
    public PageRecord(
            @JsonProperty("url") String url,
            @JsonProperty("originUrl") String originUrl,
            @JsonProperty("depth") int depth,
            @JsonProperty("fetchedAtEpochMs") long fetchedAtEpochMs,
            @JsonProperty("text") String text,
            @JsonProperty("outgoingLinks") List<String> outgoingLinks,
            @JsonProperty("discoveredBy") String discoveredBy
    ) {
        this.url = url;
        this.originUrl = originUrl;
        this.depth = depth;
        this.fetchedAtEpochMs = fetchedAtEpochMs;
        this.text = text;
        this.outgoingLinks = outgoingLinks;
        this.discoveredBy = discoveredBy;
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

    public long getFetchedAtEpochMs() {
        return fetchedAtEpochMs;
    }

    public String getText() {
        return text;
    }

    public List<String> getOutgoingLinks() {
        return outgoingLinks;
    }

    public String getDiscoveredBy() {
        return discoveredBy;
    }
}
