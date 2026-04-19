package com.crawler.cli;

import com.crawler.core.CrawlerJobRunner;
import com.crawler.model.CrawlJobState;
import com.crawler.persistence.JsonStores;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CrawlerCli {
    private static final Path DEFAULT_DATA_DIR = Paths.get("data");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String command = args[0];
        switch (command) {
            case "index":
                runIndex(args);
                break;
            case "resume":
                runResume(args);
                break;
            case "status":
                runStatus(args);
                break;
            case "verify":
                runVerify(args);
                break;
            default:
                printUsage();
                break;
        }
    }

    private static void runIndex(String[] args) throws Exception {
        if (args.length < 3) {
            printUsage();
            return;
        }

        String url = args[1];
        int depth = Integer.parseInt(args[2]);
        int workers = args.length >= 4 ? Integer.parseInt(args[3]) : 8;
        int queueCapacity = args.length >= 5 ? Integer.parseInt(args[4]) : 1000;

        CrawlerJobRunner runner =
                CrawlerJobRunner.startNew(url, depth, workers, queueCapacity, DEFAULT_DATA_DIR);
        System.out.println("job_id=" + runner.getJobId());
        runner.runUntilDone();
        System.out.println("crawl_finished job_id=" + runner.getJobId());
    }

    private static void runResume(String[] args) throws Exception {
        if (args.length < 2) {
            printUsage();
            return;
        }

        String jobId = args[1];
        CrawlerJobRunner runner = CrawlerJobRunner.resume(jobId, DEFAULT_DATA_DIR);
        System.out.println("resuming job_id=" + runner.getJobId());
        runner.runUntilDone();
        System.out.println("crawl_finished job_id=" + runner.getJobId());
    }

    private static void runStatus(String[] args) throws Exception {
        if (args.length < 2) {
            printUsage();
            return;
        }

        String jobId = args[1];
        JsonStores stores = new JsonStores(DEFAULT_DATA_DIR, jobId);
        CrawlJobState state = stores.readState();
        if (state == null) {
            System.out.println("No state found for job_id=" + jobId);
            return;
        }

        System.out.println("job_id=" + state.getJobId());
        System.out.println("origin=" + state.getOriginUrl());
        System.out.println("max_depth=" + state.getMaxDepth());
        System.out.println("workers=" + state.getWorkerCount());
        System.out.println("queue_capacity=" + state.getQueueCapacity());
        System.out.println("visited_count=" + state.getVisited().size());
        System.out.println("pending_count=" + state.getFrontier().size());
        System.out.println("pages_fetched=" + state.getPagesFetched());
        System.out.println("stop_requested=" + state.isStopRequested());
        System.out.println("finished=" + state.isFinished());
        System.out.println("updated_at=" + state.getUpdatedAtEpochMs());
    }

    private static void runVerify(String[] args) throws Exception {
        if (args.length < 2) {
            printUsage();
            return;
        }

        String jobId = args[1];
        JsonStores stores = new JsonStores(DEFAULT_DATA_DIR, jobId);
        Path jobDir = stores.getJobDir();
        Path pagesFile = jobDir.resolve("pages.jsonl");
        Path edgesFile = jobDir.resolve("edges.jsonl");
        Path indexFile = jobDir.resolve("p.data");

        Set<String> pageUrls = new HashSet<>();
        Map<String, Integer> pageDepths = new HashMap<>();
        Set<String> duplicatePages = new HashSet<>();
        int invalidPageDepthRows = 0;
        int pagesWithoutWords = 0;
        int invalidIndexRows = 0;
        int indexRows = 0;
        int badEdgeRows = 0;
        int edgesFromUnknownPage = 0;
        int edgesDepthMismatch = 0;

        for (String line : Files.readAllLines(pagesFile, StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) {
                continue;
            }
            JsonNode node = MAPPER.readTree(line);
            String url = node.path("url").asText("");
            int depth = node.path("depth").asInt(-1);
            if (url.trim().isEmpty()) {
                continue;
            }
            if (!pageUrls.add(url)) {
                duplicatePages.add(url);
            }
            if (depth < 0) {
                invalidPageDepthRows++;
            }
            pageDepths.putIfAbsent(url, depth);
        }

        Set<String> indexedUrls = new HashSet<>();
        for (String line : Files.readAllLines(indexFile, StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) {
                continue;
            }
            indexRows++;
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 5) {
                invalidIndexRows++;
                continue;
            }
            String word = parts[0];
            String url = parts[1];
            String origin = parts[2];
            int depth;
            int frequency;
            try {
                depth = Integer.parseInt(parts[3]);
                frequency = Integer.parseInt(parts[4]);
            } catch (NumberFormatException e) {
                invalidIndexRows++;
                continue;
            }

            if (word.trim().isEmpty()
                    || url.trim().isEmpty()
                    || origin.trim().isEmpty()
                    || depth < 0
                    || frequency <= 0) {
                invalidIndexRows++;
                continue;
            }
            indexedUrls.add(url);
        }

        for (String pageUrl : pageUrls) {
            if (!indexedUrls.contains(pageUrl)) {
                pagesWithoutWords++;
            }
        }

        for (String line : Files.readAllLines(edgesFile, StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) {
                continue;
            }
            JsonNode node;
            try {
                node = MAPPER.readTree(line);
            } catch (Exception e) {
                badEdgeRows++;
                continue;
            }

            String sourceUrl = node.path("sourceUrl").asText("");
            int targetDepth = node.path("targetDepth").asInt(-1);
            if (sourceUrl.trim().isEmpty() || targetDepth < 0) {
                badEdgeRows++;
                continue;
            }
            if (!pageDepths.containsKey(sourceUrl)) {
                edgesFromUnknownPage++;
                continue;
            }
            int expectedDepth = pageDepths.get(sourceUrl) + 1;
            if (targetDepth != expectedDepth) {
                edgesDepthMismatch++;
            }
        }

        List<String> failures =
                Arrays.asList(
                        duplicatePages.isEmpty() ? "" : "duplicate_pages=" + duplicatePages.size(),
                        invalidPageDepthRows == 0 ? "" : "invalid_page_depth_rows=" + invalidPageDepthRows,
                        invalidIndexRows == 0 ? "" : "invalid_index_rows=" + invalidIndexRows,
                        pagesWithoutWords == 0 ? "" : "pages_without_words=" + pagesWithoutWords,
                        badEdgeRows == 0 ? "" : "bad_edge_rows=" + badEdgeRows,
                        edgesFromUnknownPage == 0 ? "" : "edges_from_unknown_pages=" + edgesFromUnknownPage,
                        edgesDepthMismatch == 0 ? "" : "edges_depth_mismatch=" + edgesDepthMismatch);

        int failureCount = 0;
        for (String failure : failures) {
            if (!failure.trim().isEmpty()) {
                failureCount++;
            }
        }

        System.out.println("verify_job_id=" + jobId);
        System.out.println("pages_total=" + pageUrls.size());
        System.out.println("edges_file=" + edgesFile.getFileName());
        System.out.println("index_rows=" + indexRows);
        System.out.println("status=" + (failureCount == 0 ? "PASS" : "FAIL"));
        for (String failure : failures) {
            if (!failure.trim().isEmpty()) {
                System.out.println("issue_" + failure);
            }
        }
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  index <url> <k> [workers] [queue_capacity]");
        System.out.println("  resume <job_id>");
        System.out.println("  status <job_id>");
        System.out.println("  verify <job_id>");
    }
}
