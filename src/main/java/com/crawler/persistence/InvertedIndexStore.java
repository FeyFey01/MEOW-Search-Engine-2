package com.crawler.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class InvertedIndexStore {
    private final Path dataFile;
    private final Object writeLock = new Object();

    public InvertedIndexStore(Path jobDir) throws IOException {
        this.dataFile = jobDir.resolve("p.data");
        if (!Files.exists(dataFile)) {
            Files.createFile(dataFile);
        }
    }

    public void append(String word, String url, String originUrl, int depth, int frequency) throws IOException {
        synchronized (writeLock) {
            String line =
                    String.join(
                                    " ",
                                    word,
                                    url,
                                    originUrl,
                                    String.valueOf(depth),
                                    String.valueOf(frequency))
                            + System.lineSeparator();
            Files.write(dataFile, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        }
    }
}
