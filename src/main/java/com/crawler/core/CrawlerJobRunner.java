package com.crawler.core;

import com.crawler.model.CrawlJobState;
import com.crawler.model.CrawlTask;
import com.crawler.model.PageRecord;
import com.crawler.persistence.JsonStores;
import com.crawler.util.UrlNormalizer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class CrawlerJobRunner {
    private static final Pattern WORD_PATTERN = Pattern.compile("[A-Za-z0-9]+");

    private final String jobId;
    private final String originUrl;
    private final int maxDepth;
    private final int workerCount;
    private final BlockingQueue<CrawlTask> frontier;
    private final Set<String> visited;
    private final Set<String> enqueued;
    private final JsonStores stores;
    private final Path jvmPidFile;

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final AtomicLong pagesFetched = new AtomicLong(0);
    private final long createdAtEpochMs;

    private final HttpClient httpClient =
            HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(8))
                    .build();

    private CrawlerJobRunner(
            String jobId,
            String originUrl,
            int maxDepth,
            int workerCount,
            int queueCapacity,
            long createdAtEpochMs,
            Set<String> visited,
            List<CrawlTask> pending,
            long pagesFetched,
            Path baseDir
    ) throws IOException {
        this.jobId = jobId;
        this.originUrl = originUrl;
        this.maxDepth = maxDepth;
        this.workerCount = workerCount;
        this.frontier = new ArrayBlockingQueue<>(queueCapacity);
        this.visited = ConcurrentHashMap.newKeySet();
        this.enqueued = ConcurrentHashMap.newKeySet();
        this.visited.addAll(visited);
        
        // Safely add pending tasks; drop extras if queue overflows
        int droppedCount = 0;
        for (CrawlTask task : pending) {
            boolean accepted = this.frontier.offer(task);
            if (accepted) {
                this.enqueued.add(task.getUrl());
            } else {
                droppedCount++;
            }
        }
        if (droppedCount > 0) {
            System.err.println("WARN: resume dropped " + droppedCount + " pending tasks (queue capacity=" + queueCapacity + ")");
        }
        this.pagesFetched.set(pagesFetched);
        this.createdAtEpochMs = createdAtEpochMs;
        this.stores = new JsonStores(baseDir, jobId);
        this.jvmPidFile = baseDir.resolve(jobId).resolve("jvm.pid");
    }

    public static CrawlerJobRunner startNew(
            String originUrl,
            int maxDepth,
            int workerCount,
            int queueCapacity,
            Path baseDir
    ) throws IOException {
        String normalized = UrlNormalizer.normalize(originUrl);
        if (normalized == null) {
            throw new IllegalArgumentException("Invalid origin URL: " + originUrl);
        }
        String jobId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        Set<String> visited = new HashSet<>();
        List<CrawlTask> pending = new ArrayList<>();
        pending.add(new CrawlTask(normalized, normalized, 0, normalized, now));

        return new CrawlerJobRunner(
                jobId, normalized, maxDepth, workerCount, queueCapacity, now, visited, pending, 0, baseDir);
    }

    public static CrawlerJobRunner resume(String jobId, Path baseDir) throws IOException {
        JsonStores stores = new JsonStores(baseDir, jobId);
        CrawlJobState state = stores.readState();
        if (state == null) {
            throw new IllegalArgumentException("No saved state for jobId=" + jobId);
        }

        return new CrawlerJobRunner(
                state.getJobId(),
                state.getOriginUrl(),
                state.getMaxDepth(),
                state.getWorkerCount(),
                state.getQueueCapacity(),
                state.getCreatedAtEpochMs(),
                state.getVisited(),
                state.getFrontier(),
                state.getPagesFetched(),
                baseDir);
    }

    public void runUntilDone() throws InterruptedException, IOException {
        writeJvmPidFile();
        persistState();

        ScheduledExecutorService checkpointScheduler = Executors.newSingleThreadScheduledExecutor();
        checkpointScheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        persistState();
                    } catch (IOException ignored) {
                    }
                },
                5,
                5,
                TimeUnit.SECONDS);

        ExecutorService workers = Executors.newFixedThreadPool(workerCount);
        for (int i = 0; i < workerCount; i++) {
            workers.submit(this::workerLoop);
        }

        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    try {
                                        requestStop();
                                        persistState();
                                        deleteJvmPidFile();
                                    } catch (IOException ignored) {
                                    }
                                }));

        while (true) {
            if (stopRequested.get()) {
                break;
            }
            if (frontier.isEmpty() && inFlight.get() == 0) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }

        // Freeze scheduler + workers first, then persist final snapshot.
        stopRequested.set(true);
        checkpointScheduler.shutdownNow();
        workers.shutdownNow();
        if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
            System.err.println("WARN: workers did not terminate within timeout after shutdownNow");
        }

        if (frontier.isEmpty() && inFlight.get() == 0) {
            finished.set(true);
        }

        persistState();
        deleteJvmPidFile();
    }

    private void writeJvmPidFile() {
        try {
            long pid = ProcessHandle.current().pid();
            Files.createDirectories(jvmPidFile.getParent());
            Files.writeString(
                    jvmPidFile,
                    Long.toString(pid),
                    StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private void deleteJvmPidFile() {
        try {
            Files.deleteIfExists(jvmPidFile);
        } catch (IOException ignored) {
        }
    }

    private void workerLoop() {
        try {
            while (!stopRequested.get() && !Thread.currentThread().isInterrupted()) {
                CrawlTask task = frontier.poll(1, TimeUnit.SECONDS);
                if (task == null) {
                    continue;
                }
                if (stopRequested.get() || Thread.currentThread().isInterrupted()) {
                    return;
                }

                String normalized = UrlNormalizer.normalize(task.getUrl());
                if (normalized == null) {
                    continue;
                }
                enqueued.remove(normalized);

                if (!visited.add(normalized)) {
                    continue;
                }

                inFlight.incrementAndGet();
                try {
                    processTask(task, normalized);
                } finally {
                    inFlight.decrementAndGet();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void processTask(CrawlTask task, String normalizedUrl) {
        if (stopRequested.get() || Thread.currentThread().isInterrupted()) {
            return;
        }
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(normalizedUrl))
                            .timeout(Duration.ofSeconds(12))
                            .header("User-Agent", "CrawlerHWBot/1.0")
                            .GET()
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (stopRequested.get() || Thread.currentThread().isInterrupted()) {
                return;
            }
            if (response.statusCode() >= 400) {
                return;
            }

            Document doc = Jsoup.parse(response.body(), normalizedUrl);
            String text = doc.text();
            Map<String, Integer> wordFrequency = computeWordFrequency(text);
            List<String> outgoing = new ArrayList<>();
            long now = System.currentTimeMillis();

            for (Element link : doc.select("a[href]")) {
                if (stopRequested.get() || Thread.currentThread().isInterrupted()) {
                    return;
                }
                String absolute = link.attr("abs:href");
                String target = UrlNormalizer.normalize(absolute);
                if (target == null) {
                    continue;
                }
                outgoing.add(target);

                stores.appendEdge(normalizedUrl, target, task.getOriginUrl(), task.getDepth() + 1, now);

                if (task.getDepth() < maxDepth
                        && !visited.contains(target)
                        && !stopRequested.get()
                        && enqueued.add(target)) {
                    CrawlTask next =
                            new CrawlTask(
                                    target,
                                    task.getOriginUrl(),
                                    task.getDepth() + 1,
                                    normalizedUrl,
                                    now);
                    boolean accepted = frontier.offer(next);
                    if (!accepted) {
                        enqueued.remove(target);
                    }
                }
            }

            if (stopRequested.get() || Thread.currentThread().isInterrupted()) {
                return;
            }
            stores.appendPage(
                    new PageRecord(
                            normalizedUrl,
                            task.getOriginUrl(),
                            task.getDepth(),
                            now,
                            text,
                            outgoing,
                            task.getDiscoveredBy()));
            for (Map.Entry<String, Integer> entry : wordFrequency.entrySet()) {
                if (stopRequested.get() || Thread.currentThread().isInterrupted()) {
                    return;
                }
                stores.appendWordIndexRecord(
                        entry.getKey(),
                        normalizedUrl,
                        task.getOriginUrl(),
                        task.getDepth(),
                        entry.getValue());
            }
            if (!stopRequested.get() && !Thread.currentThread().isInterrupted()) {
                pagesFetched.incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
        }
    }

    private Map<String, Integer> computeWordFrequency(String text) {
        Map<String, Integer> frequencies = new HashMap<>();
        Matcher matcher = WORD_PATTERN.matcher(text);
        while (matcher.find()) {
            String word = matcher.group().toLowerCase(Locale.ROOT);
            frequencies.merge(word, 1, Integer::sum);
        }
        return frequencies;
    }

    public void requestStop() {
        stopRequested.set(true);
    }

    public CrawlJobState currentStateSnapshot() {
        List<CrawlTask> queued = new ArrayList<>(frontier);
        Set<String> visitedSnapshot = new HashSet<>(visited);

        return new CrawlJobState(
                jobId,
                originUrl,
                maxDepth,
                frontier.remainingCapacity() + frontier.size(),
                workerCount,
                createdAtEpochMs,
                System.currentTimeMillis(),
                pagesFetched.get(),
                stopRequested.get(),
                finished.get(),
                visitedSnapshot,
                queued);
    }

    public void persistState() throws IOException {
        stores.persistState(currentStateSnapshot());
    }

    public String getJobId() {
        return jobId;
    }
}
