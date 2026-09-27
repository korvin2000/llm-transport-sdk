package net.ai.gate.internal.auth.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import net.ai.gate.auth.ApiKeyCredential;
import net.ai.gate.auth.Secret;
import net.ai.gate.error.AuthenticationException;
import net.ai.gate.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `FileStore`: cross-instance/cross-thread locking, schema validation and owner-only permissions.
class FileStoreTest {
    private static final int THREADS = 2, PER_THREAD = 50;

    @Test
    void twoInstancesAndTwoThreadsUpdateWithoutLosingOrThrowing(@TempDir Path dir) throws InterruptedException {
        var path = dir.resolve("credentials.json");
        var stores = new FileStore[] { new FileStore(path), new FileStore(path) };
        var errors = new CopyOnWriteArrayList<Throwable>();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        for (int t = 0; t < THREADS; t++) {
            var store = stores[t];
            var thread = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < PER_THREAD; i++) {
                        var index = i;
                        store.update("thread-" + thread + "-key-" + index, _ -> Optional.of(ApiKeyCredential.of("sk-" + thread + "-" + index)));
                        store.update("shared", current -> {
                            var count = current.map(c -> Integer.parseInt(((ApiKeyCredential) c).settings().get("count"))).orElse(0);
                            return Optional.of(new ApiKeyCredential(Secret.of("sk-shared-counter"), Map.of("count", String.valueOf(count + 1))));
                        });
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(1, TimeUnit.MINUTES), "updates did not finish in time");
        assertTrue(errors.isEmpty(), errors.toString());

        var reader = new FileStore(path);
        for (int t = 0; t < THREADS; t++)
            for (int i = 0; i < PER_THREAD; i++)
                assertEquals(Optional.of(ApiKeyCredential.of("sk-" + t + "-" + i)), reader.read("thread-" + t + "-key-" + i));
        assertEquals(String.valueOf(THREADS * PER_THREAD), ((ApiKeyCredential) reader.read("shared").orElseThrow()).settings().get("count"));
    }

    @Test
    void rotationSurvivesReopen(@TempDir Path dir) {
        var path = dir.resolve("credentials.json");
        var store = new FileStore(path);
        store.update("openai", _ -> Optional.of(ApiKeyCredential.of("sk-first-0123456789")));
        store.update("openai", _ -> Optional.of(ApiKeyCredential.of("sk-second-0123456789")));

        var reopened = new FileStore(path);
        assertEquals(Optional.of(ApiKeyCredential.of("sk-second-0123456789")), reopened.read("openai"));
    }

    @Test
    void wrongSchemaFailsPredictably(@TempDir Path dir) throws IOException {
        var path = dir.resolve("credentials.json");
        Files.writeString(path, "{\"schema\":\"other/9\",\"credentials\":{}}");

        var error = assertThrows(AuthenticationException.class, () -> new FileStore(path).read("k"));
        assertEquals(ErrorCode.CREDENTIAL_STORE, error.code());
        assertTrue(error.getMessage().contains(path.toString()), error.getMessage());
    }

    @Test
    void garbageContentFailsPredictably(@TempDir Path dir) throws IOException {
        var path = dir.resolve("credentials.json");
        Files.writeString(path, "not json at all {{{");

        var error = assertThrows(AuthenticationException.class, () -> new FileStore(path).list());
        assertEquals(ErrorCode.CREDENTIAL_STORE, error.code());
        assertTrue(error.getMessage().contains(path.toString()), error.getMessage());
    }

    @Test
    void posixPermissionsAreOwnerOnly(@TempDir Path dir) throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        var path = dir.resolve("credentials.json");
        new FileStore(path).update("k", _ -> Optional.of(ApiKeyCredential.of("sk-test-0123456789")));

        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path));
    }

    @Test
    void aclIsOwnerOnlyWhereSupportedWithoutPosix(@TempDir Path dir) throws IOException {
        var views = FileSystems.getDefault().supportedFileAttributeViews();
        assumeTrue(views.contains("acl") && !views.contains("posix"));
        var path = dir.resolve("credentials.json");
        new FileStore(path).update("k", _ -> Optional.of(ApiKeyCredential.of("sk-test-0123456789")));

        var view = Files.getFileAttributeView(path, AclFileAttributeView.class);
        var acl = view.getAcl();
        assertEquals(1, acl.size());
        assertEquals(view.getOwner(), acl.get(0).principal());
    }
}
