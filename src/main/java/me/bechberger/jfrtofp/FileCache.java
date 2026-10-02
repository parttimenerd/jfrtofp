package me.bechberger.jfrtofp;

import me.bechberger.jfrtofp.converter.JFRConverter;
import me.bechberger.jfrtofp.processor.Config;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/** Caches JFR/CJFR → JSON.gz conversions on disk. Thread-safe. */
public final class FileCache {

    private static final int BUFFER_SIZE = 8192;

    private final Path location;
    private final String extension;
    private final AtomicLong maxSize;

    public FileCache(Path location, long maxSize, String extension) {
        this.location = location != null ? location : defaultLocation();
        this.maxSize = new AtomicLong(maxSize);
        this.extension = extension;
        try {
            Files.createDirectories(this.location);
        } catch (IOException ignored) {}
    }

    public void close() {
        try {
            deleteRecursively(location);
        } catch (IOException ignored) {}
    }

    public synchronized Path get(Path jfrFile, Config config) throws IOException {
        Path cached = cachePath(jfrFile, config);
        if (!Files.exists(cached)) {
            create(jfrFile, config, cached);
        }
        return cached;
    }

    public boolean has(Path jfrFile, Config config) {
        return Files.exists(cachePath(jfrFile, config));
    }

    public void setMaxSize(long size) {
        maxSize.set(size);
        try { ensureFreeSpace(0); } catch (IOException ignored) {}
    }

    public long getMaxSize() {
        return maxSize.get();
    }

    private void create(Path jfrFile, Config config, Path dest) throws IOException {
        try (OutputStream raw = Files.newOutputStream(dest)) {
            boolean gzip = extension.endsWith(".gz");
            OutputStream out = gzip ? new GZIPOutputStream(raw) : raw;
            try {
                JFRConverter.convert(jfrFile, config.toConverterConfig(), out);
            } finally {
                if (gzip) out.close();
            }
            ensureFreeSpace(0);
        } catch (Throwable e) {
            Files.deleteIfExists(dest);
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Conversion failed: " + e.getMessage(), e);
        }
    }

    private void ensureFreeSpace(long amount) throws IOException {
        long limit = Math.max(0, maxSize.get() - amount);
        while (cacheSize() > limit) {
            Path oldest = null;
            long oldestTime = Long.MAX_VALUE;
            try (Stream<Path> stream = Files.list(location)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    long t = Files.getLastModifiedTime(p).toMillis();
                    if (t < oldestTime) { oldestTime = t; oldest = p; }
                }
            }
            if (oldest == null) break;
            Files.deleteIfExists(oldest);
        }
    }

    private long cacheSize() throws IOException {
        try (Stream<Path> stream = Files.list(location)) {
            return stream.mapToLong(p -> p.toFile().length()).sum();
        }
    }

    private Path cachePath(Path jfrFile, Config config) {
        return location.resolve(hash(jfrFile, config) + extension);
    }

    private String hash(Path jfrFile, Config config) {
        return hashFile(jfrFile) + hashConfig(config);
    }

    private String hashFile(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[BUFFER_SIZE];
            try (var in = Files.newInputStream(file)) {
                int n;
                while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
            }
            return Base64.getEncoder().encodeToString(digest.digest()).replace("/", "_");
        } catch (IOException | NoSuchAlgorithmException e) {
            return Long.toHexString(file.hashCode());
        }
    }

    private String hashConfig(Config config) {
        StringBuilder sb = new StringBuilder();
        sb.append(config.getNonProjectPackagePrefixes()).append(';');
        sb.append(config.isEnableMarkers()).append(';');
        sb.append(config.getInitialVisibleThreads()).append(';');
        sb.append(config.getInitialSelectedThreads()).append(';');
        sb.append(config.isIncludeGCThreads()).append(';');
        sb.append(config.getIgnoredEvents()).append(';');
        sb.append(config.getMinRequiredItemsPerThread()).append(';');
        sb.append(config.getSourceUrl()).append(';');
        return Integer.toHexString(sb.toString().hashCode());
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) Files.deleteIfExists(p);
        }
        Files.deleteIfExists(dir);
    }

    private static Path defaultLocation() {
        try {
            return Files.createTempDirectory("jfrtofp");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
