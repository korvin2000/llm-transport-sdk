package net.ai.gate.internal.auth.store;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import net.ai.gate.auth.Credential;
import net.ai.gate.auth.CredentialStore;
import net.ai.gate.error.AuthenticationException;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.LlmException;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonObject;
import net.ai.gate.json.JsonValue;

/// `CredentialStore.file(path)`: one JSON document, owner-only permissions where the file system supports them,
/// updates serialized in-process and across processes by a lock file, written by atomic replacement. Not encrypted.
public final class FileStore implements CredentialStore {
    private static final String SCHEMA = "ai-gate.credentials/1";
    /// One monitor per normalized path, shared by every `FileStore` on it in this JVM: two instances must not both
    /// enter `update` and race the cross-process file lock, which throws `OverlappingFileLockException` on overlap
    /// instead of waiting for a lock already held by this same JVM.
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();
    private static final System.Logger LOG = System.getLogger("net.ai.gate.auth");

    private final Path path, lock;
    private final Object intraProcessLock;

    public FileStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
        this.lock = this.path.resolveSibling(this.path.getFileName() + ".lock");
        this.intraProcessLock = LOCKS.computeIfAbsent(this.path, _ -> new Object());
    }

    @Override public Optional<Credential> read(String key) { return Optional.ofNullable(load().get(key)); }

    @Override public List<Entry> list() {
        return load().entrySet().stream().map(e -> new Entry(e.getKey(), e.getValue().type())).toList();
    }

    @Override public Optional<Credential> update(String key, Function<Optional<Credential>, Optional<Credential>> change) {
        synchronized (intraProcessLock) {
            try {
                Files.createDirectories(path.getParent());
                try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE); var _ = channel.lock()) {
                    var all = load();
                    var result = change.apply(Optional.ofNullable(all.get(key)));
                    result.ifPresentOrElse(v -> all.put(key, v), () -> all.remove(key));
                    save(all);
                    return result;
                }
            } catch (IOException e) {
                throw failure("update", e);
            }
        }
    }

    private Map<String, Credential> load() {
        var all = new TreeMap<String, Credential>();
        if (!Files.exists(path)) return all;
        try {
            var document = (JsonObject) Json.parse(Files.readString(path));
            var schema = document.string("schema");
            if (!SCHEMA.equals(schema)) throw new IllegalArgumentException("unsupported schema '" + schema + "'");
            document.object("credentials").members().forEach((k, v) -> all.put(k, CredentialJson.read((JsonObject) v)));
            return all;
        } catch (IOException | RuntimeException e) {
            throw failure("read", e);
        }
    }

    private void save(Map<String, Credential> all) throws IOException {
        var credentials = new LinkedHashMap<String, JsonValue>();
        all.forEach((k, v) -> credentials.put(k, CredentialJson.write(v)));
        var temp = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            ownerOnly(temp);
            Files.writeString(temp, Json.object("schema", SCHEMA, "credentials", JsonObject.of(credentials)).toPrettyJson(), StandardCharsets.UTF_8);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void ownerOnly(Path file) throws IOException {
        var views = file.getFileSystem().supportedFileAttributeViews();
        if (views.contains("posix")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } else if (views.contains("acl")) {
            try {
                var view = Files.getFileAttributeView(file, AclFileAttributeView.class);
                var owner = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.getOwner())
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
                view.setAcl(List.of(owner));
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.DEBUG, () -> "Cannot restrict permissions on " + file, e);
            }
        }
    }

    private AuthenticationException failure(String action, Exception cause) {
        return new AuthenticationException(LlmException.Details.builder(ErrorCode.CREDENTIAL_STORE,
                "Cannot " + action + " credential file " + path + ": " + cause.getMessage()).build(), cause);
    }
}
