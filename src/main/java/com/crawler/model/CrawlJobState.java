package com.crawler.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Set;

public class CrawlJobState {
    private final String jobId;
    private final String originUrl;
    private final int maxDepth;
    private final int queueCapacity;
    private final int workerCount;
    private final long createdAtEpochMs;
    private final long updatedAtEpochMs;
    private final long pagesFetched;
    private final boolean stopRequested;
    private final boolean finished;
    private final Set<String> visited;
    private final List<CrawlTask> frontier;

    @JsonCreator
    public CrawlJobState(
            @JsonProperty("jobId") String jobId,
            @JsonProperty("originUrl") String originUrl,
            @JsonProperty("maxDepth") int maxDepth,
            @JsonProperty("queueCapacity") int queueCapacity,
            @JsonProperty("workerCount") int workerCount,
            @JsonProperty("createdAtEpochMs") long createdAtEpochMs,
            @JsonProperty("updatedAtEpochMs") long updatedAtEpochMs,
            @JsonProperty("pagesFetched") long pagesFetched,
            @JsonProperty("stopRequested") boolean stopRequested,
            @JsonProperty("finished") boolean finished,
            @JsonProperty("visited") Set<String> visited,
            @JsonProperty("frontier") List<CrawlTask> frontier
    ) {
        this.jobId = jobId;
        this.originUrl = originUrl;
        this.maxDepth = maxDepth;
        this.queueCapacity = queueCapacity;
        this.workerCount = workerCount;
        this.createdAtEpochMs = createdAtEpochMs;
        this.updatedAtEpochMs = updatedAtEpochMs;
        this.pagesFetched = pagesFetched;
        this.stopRequested = stopRequested;
        this.finished = finished;
        this.visited = visited;
        this.frontier = frontier;
    }

    public String getJobId() {
        return jobId;
    }

    public String getOriginUrl() {
        return originUrl;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public int getWorkerCount() {
        return workerCount;
    }

    public long getCreatedAtEpochMs() {
        return createdAtEpochMs;
    }

    public long getUpdatedAtEpochMs() {
        return updatedAtEpochMs;
    }

    public long getPagesFetched() {
        return pagesFetched;
    }

    public boolean isStopRequested() {
        return stopRequested;
    }

    public boolean isFinished() {
        return finished;
    }

    public Set<String> getVisited() {
        return visited;
    }

    public List<CrawlTask> getFrontier() {
        return frontier;
    }
}
