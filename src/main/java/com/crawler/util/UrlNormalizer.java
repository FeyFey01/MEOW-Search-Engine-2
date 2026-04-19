package com.crawler.util;

import java.net.URI;
import java.net.URISyntaxException;

public final class UrlNormalizer {
    private UrlNormalizer() {
    }

    public static String normalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(rawUrl.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return null;
            }

            scheme = scheme.toLowerCase();
            host = host.toLowerCase();

            int port = uri.getPort();
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                path = "/";
            }

            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            return new URI(scheme, uri.getUserInfo(), host, port, path, uri.getQuery(), null)
                    .toString();
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
