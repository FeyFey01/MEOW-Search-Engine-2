package com.crawler.persistence;

import com.crawler.model.CrawlJobState;
import com.crawler.model.PageRecord;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

public class JsonStores {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path jobDir;
    private final Path pagesFile;
    private final Path edgesFile;
    private final Path stateFile;
    private final InvertedIndexStore invertedIndexStore;

    private final Object pageLock = new Object();
    private final Object edgeLock = new Object();
    private final Object stateLock = new Object();

    public JsonStores(Path baseDir, String jobId) throws IOException {
        this.jobDir = baseDir.resolve(jobId);
        this.pagesFile = jobDir.resolve("pages.jsonl");
        this.edgesFile = jobDir.resolve("edges.jsonl");
        this.stateFile = jobDir.resolve("state.json");

        Files.createDirectories(jobDir);
        this.invertedIndexStore = new InvertedIndexStore(jobDir);
        if (!Files.exists(pagesFile)) {
            Files.createFile(pagesFile);
        }
        if (!Files.exists(edgesFile)) {
            Files.createFile(edgesFile);
        }
    }

    public void appendPage(PageRecord record) throws IOException {
        synchronized (pageLock) {
            String line = mapper.writeValueAsString(record) + System.lineSeparator();
            Files.write(pagesFile, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        }
    }

    public void appendEdge(
            String sourceUrl,
            String targetUrl,
            String originUrl,
            int targetDepth,
            long discoveredAt
    ) throws IOException {
        synchronized (edgeLock) {
            Map<String, Object> edge = new HashMap<>();
            edge.put("sourceUrl", sourceUrl);
            edge.put("targetUrl", targetUrl);
            edge.put("originUrl", originUrl);
            edge.put("targetDepth", targetDepth);
            edge.put("discoveredAtEpochMs", discoveredAt);
            String line = mapper.writeValueAsString(edge) + System.lineSeparator();
            Files.write(edgesFile, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        }
    }

    public void appendWordIndexRecord(String word, String url, String originUrl, int depth, int frequency)
            throws IOException {
        invertedIndexStore.append(word, url, originUrl, depth, frequency);
    }

    public void persistState(CrawlJobState state) throws IOException {
        synchronized (stateLock) {
            mapper.writerWithDefaultPrettyPrinter().writeValue(stateFile.toFile(), state);
        }
    }

    public CrawlJobState readState() throws IOException {
        if (!Files.exists(stateFile)) {
            return null;
        }
        return mapper.readValue(stateFile.toFile(), CrawlJobState.class);
    }

    public Path getJobDir() {
        return jobDir;
    }
}
