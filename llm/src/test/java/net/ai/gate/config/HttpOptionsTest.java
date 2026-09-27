package net.ai.gate.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import net.ai.gate.spi.http.HttpTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpOptionsTest {
    @Test
    void tlsSettingsBecomeAnSslContext(@TempDir Path directory) throws Exception {
        assertTrue(HttpOptions.defaults().sslContext().isEmpty(), "JDK defaults");

        var store = directory.resolve("trust.p12");
        var password = "changeit".toCharArray();
        var keys = KeyStore.getInstance("PKCS12");
        keys.load(null, password);
        try (var out = Files.newOutputStream(store)) { keys.store(out, password); }
        var options = HttpOptions.builder().trustStore(store, password).build();
        assertEquals("TLS", options.sslContext().orElseThrow().getProtocol());
        HttpTransport.jdk(options).close();

        assertTrue(HttpOptions.builder().insecureSkipTlsVerification().build().sslContext().isPresent());
        var wrong = HttpOptions.builder().trustStore(store, "wrong".toCharArray()).build();
        assertThrows(RuntimeException.class, wrong::sslContext, "a wrong password fails at build, not per request");
    }
}
