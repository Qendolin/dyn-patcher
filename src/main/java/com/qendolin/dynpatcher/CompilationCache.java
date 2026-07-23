package com.qendolin.dynpatcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

/**
 * Manages a persistent cache of compiled Mixin class files.
 */
public class CompilationCache {

    private static final String INDEX_FILENAME = "index.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type INDEX_DATA_TYPE = new TypeToken<IndexData>() {}.getType();

    // ── Index data model ─────────────────────────────────────────────────────

    public static final class IndexData {
        public String modVersion;
        public Map<String, CacheEntry> entries = new HashMap<>();
    }

    public static final class CacheEntry {
        public String contentHash;
        public long lastModified;
        public List<String> classes;

        public CacheEntry() {}

        public CacheEntry(String contentHash, long lastModified, List<String> classes) {
            this.contentHash = contentHash;
            this.lastModified = lastModified;
            this.classes = classes;
        }
    }

    public record ResolveResult(List<String> classes, String missReason, String hitReason) {
        public boolean isHit() {
            return classes != null;
        }
    }

    // ── State ────────────────────────────────────────────────────────────────

    private final Path cacheDir;
    private final Path patchesDir;
    private final String currentModVersion;
    private IndexData indexData = new IndexData();

    public CompilationCache(Path cacheDir, Path patchesDir, String currentModVersion) {
        this.cacheDir = cacheDir;
        this.patchesDir = patchesDir.toAbsolutePath();
        this.currentModVersion = currentModVersion;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Loads {@code index.json} from disk. If mod version differs or file is corrupted, clears the cache.
     */
    public void load() {
        Path indexFile = cacheDir.resolve(INDEX_FILENAME);
        indexData = new IndexData();
        indexData.modVersion = currentModVersion;

        if (!Files.exists(indexFile)) return;

        try (Reader reader = Files.newBufferedReader(indexFile)) {
            IndexData loaded = GSON.fromJson(reader, INDEX_DATA_TYPE);
            if (loaded != null && currentModVersion.equals(loaded.modVersion)) {
                indexData = loaded;
                if (indexData.entries == null) indexData.entries = new HashMap<>();
            } else {
                String oldVer = (loaded != null) ? loaded.modVersion : "unknown";
                DynamicPatcher.LOGGER.info(
                        "[DynPatcher] Mod version changed (cached: '{}', current: '{}'). Clearing compilation cache...",
                        oldVer, currentModVersion);
                clearCacheDirectory();
            }
        } catch (Exception e) {
            DynamicPatcher.LOGGER.warn(
                    "[DynPatcher] Cache index is unreadable; rebuilding cache. Reason: {}", e.getMessage());
            clearCacheDirectory();
        }
    }

    /**
     * Validates the cache entry for {@code javaFile}.
     */
    public ResolveResult resolve(Path javaFile) {
        String key = toRelativeKey(javaFile);
        CacheEntry entry = indexData.entries.get(key);
        if (entry == null || entry.classes == null || entry.classes.isEmpty()) {
            return new ResolveResult(null, "no cache entry found", null);
        }

        try {
            long currentMtime = Files.getLastModifiedTime(javaFile).toMillis();

            String hitReason;
            if (entry.lastModified != currentMtime) {
                String currentHash = computeHash(javaFile);
                if (!currentHash.equals(entry.contentHash)) {
                    invalidate(key, entry);
                    return new ResolveResult(null, "source file content was modified", null);
                }
                entry.lastModified = currentMtime;
                hitReason = "timestamp updated, content SHA-256 hash matched (" + currentHash.substring(0, 8) + ")";
            } else {
                hitReason = "timestamp matched (" + currentMtime + ")";
            }

            for (String fqcn : entry.classes) {
                if (!Files.exists(classFilePath(fqcn))) {
                    invalidate(key, entry);
                    return new ResolveResult(null, "missing cached bytecode for class " + fqcn, null);
                }
            }

            return new ResolveResult(entry.classes, null, hitReason);

        } catch (Exception e) {
            invalidate(key, entry);
            return new ResolveResult(null, "validation error: " + e.getMessage(), null);
        }
    }

    public void store(Path javaFile, List<String> fqcns, Map<String, byte[]> processedBytes) throws IOException {
        String key = toRelativeKey(javaFile);

        CacheEntry old = indexData.entries.get(key);
        if (old != null && old.classes != null) {
            for (String oldFqcn : old.classes) {
                Files.deleteIfExists(classFilePath(oldFqcn));
            }
        }

        for (String fqcn : fqcns) {
            byte[] bytes = processedBytes.get(fqcn);
            if (bytes == null) continue;
            Path dest = classFilePath(fqcn);
            Files.createDirectories(dest.getParent());
            Files.write(dest, bytes);
        }

        try {
            long mtime = Files.getLastModifiedTime(javaFile).toMillis();
            String hash = computeHash(javaFile);
            indexData.entries.put(key, new CacheEntry(hash, mtime, new ArrayList<>(fqcns)));
        } catch (Exception e) {
            DynamicPatcher.LOGGER.warn("[DynPatcher] Could not record cache entry for '{}': {}", key, e.getMessage());
        }
    }

    public void flush() throws IOException {
        indexData.modVersion = currentModVersion;
        try (Writer writer = Files.newBufferedWriter(cacheDir.resolve(INDEX_FILENAME))) {
            GSON.toJson(indexData, writer);
        }
    }

    private void clearCacheDirectory() {
        if (!Files.exists(cacheDir)) return;
        try (Stream<Path> stream = Files.walk(cacheDir)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(p -> !p.equals(cacheDir))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            DynamicPatcher.LOGGER.warn("[DynPatcher] Failed to fully clear cache directory: {}", e.getMessage());
        }
    }

    private void invalidate(String key, CacheEntry entry) {
        indexData.entries.remove(key);
        if (entry != null && entry.classes != null) {
            for (String fqcn : entry.classes) {
                try {
                    Files.deleteIfExists(classFilePath(fqcn));
                } catch (IOException ignored) {}
            }
        }
    }

    private Path classFilePath(String fqcn) {
        return cacheDir.resolve(fqcn.replace('.', '/') + ".class");
    }

    private String toRelativeKey(Path javaFile) {
        return patchesDir.relativize(javaFile.toAbsolutePath()).toString().replace('\\', '/');
    }

    private static String computeHash(Path file) throws Exception {
        byte[] content = Files.readAllBytes(file);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
