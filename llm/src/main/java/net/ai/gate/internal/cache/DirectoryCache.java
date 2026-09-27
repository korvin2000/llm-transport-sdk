package net.ai.gate.internal.cache;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

import net.ai.gate.cache.ResponseCache;

/// `ResponseCache.directory(path)`: one readable JSON file per entry — test cassettes to commit and replay in CI.
/// Entries are written to a temporary file and moved into place atomically; a failed write leaves nothing behind.
public final class DirectoryCache implements ResponseCache {
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{64}");

    private final Path directory;

    public DirectoryCache(Path directory) { this.directory = directory; }

    @Override public Optional<byte[]> get(String key) {
        try {
            return Optional.of(Files.readAllBytes(file(key)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override public void put(String key, byte[] entry) {
        var target = file(key);
        Path temp = null;
        try {
            Files.createDirectories(directory);
            temp = Files.createTempFile(directory, key, ".tmp");
            Files.write(temp, entry);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { /* best effort */ }
            throw new UncheckedIOException(e);
        }
    }

    @Override public void remove(String key) {
        try { Files.deleteIfExists(file(key)); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private Path file(String key) {
        if (!KEY.matcher(key).matches()) throw new IllegalArgumentException("Not a cache key: " + key);
        return directory.resolve(key + ".json");
    }
}
