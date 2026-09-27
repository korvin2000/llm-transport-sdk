package net.ai.gate.internal.auth.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import net.ai.gate.auth.CredentialStore;
import net.ai.gate.error.AuthenticationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class CredentialLockTest {
    @TempDir Path directory;

    @Test
    void memoryStoreWaitersCanBeInterrupted() throws Exception {
        var store = CredentialStore.inMemory();
        check(store, store);
    }

    @Test
    void fileStoreWaitersAcrossInstancesCanBeInterrupted() throws Exception {
        var path = directory.resolve("credentials.json");
        check(CredentialStore.file(path), CredentialStore.file(path));
    }

    private static void check(CredentialStore owner, CredentialStore waiting) throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var called = new AtomicBoolean();
        var interrupted = new AtomicBoolean();
        var error = new AtomicReference<Throwable>();
        var holder = Thread.ofVirtual().start(() -> owner.update("provider", current -> {
            held.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test did not release lock");
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            return current;
        }));
        try {
            assertTrue(held.await(2, TimeUnit.SECONDS));
            var waiter = Thread.ofVirtual().start(() -> {
                entered.countDown();
                try {
                    waiting.update("provider", current -> { called.set(true); return current; });
                } catch (Throwable e) {
                    error.set(e);
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                waiter.interrupt();
                waiter.join(Duration.ofSeconds(1));
                assertFalse(waiter.isAlive(), "interrupt must end the lock wait before the owner releases it");
                assertInstanceOf(AuthenticationException.class, error.get());
                assertTrue(interrupted.get());
                assertFalse(called.get(), "an interrupted update must not run its mutation");
            } finally {
                release.countDown();
                waiter.join(Duration.ofSeconds(2));
            }
        } finally {
            release.countDown();
            holder.join(Duration.ofSeconds(2));
        }
    }
}
