package net.ai.gate.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Optional;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;


import net.ai.gate.internal.validation.Checks;
import net.ai.gate.spi.http.HttpTransport;
import org.jspecify.annotations.Nullable;

/// Immutable options for the default JDK transport; rejected at build when a transport is injected. Redirects are
/// never followed, so credentials never leave the provider's origin.
public final class HttpOptions {
    private static final HttpOptions DEFAULTS = new Builder().build();

    private final @Nullable HttpTransport transport;
    private final HttpClient.Version httpVersion;
    private final @Nullable ProxySelector proxy;
    private final @Nullable Path trustStore, clientCertificate;
    private final char @Nullable [] trustStorePassword, clientCertificatePassword;
    private final boolean insecure;
    private final @Nullable String userAgentSuffix;
    private final WireLog wireLog;

    private HttpOptions(Builder b) {
        transport = b.transport; httpVersion = b.httpVersion; proxy = b.proxy; trustStore = b.trustStore;
        trustStorePassword = b.trustStorePassword; clientCertificate = b.clientCertificate;
        clientCertificatePassword = b.clientCertificatePassword; insecure = b.insecure; userAgentSuffix = b.userAgentSuffix;
        wireLog = b.wireLog;
    }

    public static HttpOptions defaults() { return DEFAULTS; }
    public static Builder builder() { return new Builder(); }

    /// An injected transport: borrowed, never closed by a runtime.
    public Optional<HttpTransport> transport() { return Optional.ofNullable(transport); }
    /// Default `HTTP_2` (HTTP/1.1 for cleartext loopback); `HTTP_3` opt-in.
    public HttpClient.Version httpVersion() { return httpVersion; }
    public Optional<ProxySelector> proxy() { return Optional.ofNullable(proxy); }
    public Optional<Path> trustStore() { return Optional.ofNullable(trustStore); }
    public Optional<Path> clientCertificate() { return Optional.ofNullable(clientCertificate); }
    public boolean insecureSkipTlsVerification() { return insecure; }
    public Optional<String> userAgentSuffix() { return Optional.ofNullable(userAgentSuffix); }

    /// The TLS context the trust store, client certificate and verification settings describe; empty for the JDK
    /// defaults. Reads the key stores (PKCS#12 or JKS, detected from the file).
    /// @throws UncheckedIOException when a store cannot be read
    /// @throws IllegalStateException when a store or its password is invalid
    public Optional<SSLContext> sslContext() {
        if (trustStore == null && clientCertificate == null && !insecure) return Optional.empty();
        try {
            TrustManager[] trust = null;
            if (insecure) trust = new TrustManager[] {TrustAll.INSTANCE};
            else if (trustStore != null) {
                var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                factory.init(KeyStore.getInstance(trustStore.toFile(), trustStorePassword));
                trust = factory.getTrustManagers();
            }
            KeyManager[] keys = null;
            if (clientCertificate != null) {
                var factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                factory.init(KeyStore.getInstance(clientCertificate.toFile(), clientCertificatePassword), clientCertificatePassword);
                keys = factory.getKeyManagers();
            }
            var context = SSLContext.getInstance("TLS");
            context.init(keys, trust, null);
            return Optional.of(context);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Invalid TLS settings: " + e.getMessage(), e);
        }
    }

    /// Accepts every certificate and host name — an extended manager, so the JDK adds no endpoint check.
    private static final class TrustAll extends X509ExtendedTrustManager {
        static final TrustAll INSTANCE = new TrustAll();

        @Override public void checkClientTrusted(X509Certificate[] chain, String auth) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String auth) { }
        @Override public void checkClientTrusted(X509Certificate[] chain, String auth, Socket socket) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String auth, Socket socket) { }
        @Override public void checkClientTrusted(X509Certificate[] chain, String auth, SSLEngine engine) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String auth, SSLEngine engine) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
    public WireLog wireLog() { return wireLog; }

    /// True when a JDK transport setting other than the defaults is present.
    public boolean customizesJdkTransport() {
        return httpVersion != HttpClient.Version.HTTP_2 || proxy != null || trustStore != null || clientCertificate != null
                || insecure || userAgentSuffix != null;
    }

    public Builder toBuilder() {
        var b = new Builder();
        b.transport = transport; b.httpVersion = httpVersion; b.proxy = proxy; b.trustStore = trustStore;
        b.trustStorePassword = trustStorePassword; b.clientCertificate = clientCertificate;
        b.clientCertificatePassword = clientCertificatePassword; b.insecure = insecure; b.userAgentSuffix = userAgentSuffix;
        b.wireLog = wireLog;
        return b;
    }

    @Override public String toString() {
        return "HttpOptions[" + (transport != null ? "injected transport" : httpVersion) + (insecure ? ", INSECURE TLS" : "")
                + ", wireLog=" + wireLog + "]";
    }

    /// Not thread-safe.
    public static final class Builder {
        private @Nullable HttpTransport transport;
        private HttpClient.Version httpVersion = HttpClient.Version.HTTP_2;
        private @Nullable ProxySelector proxy;
        private @Nullable Path trustStore, clientCertificate;
        private char @Nullable [] trustStorePassword, clientCertificatePassword;
        private boolean insecure;
        private @Nullable String userAgentSuffix;
        private WireLog wireLog = WireLog.OFF;

        private Builder() { }

        public Builder transport(HttpTransport value) { transport = value; return this; }
        public Builder httpVersion(HttpClient.Version version) { httpVersion = version; return this; }
        public Builder proxy(ProxySelector selector) { proxy = selector; return this; }
        public Builder trustStore(Path file, char[] password) { trustStore = file; trustStorePassword = password.clone(); return this; }
        /// Client identity for mTLS gateways, separate from token presentation.
        public Builder clientCertificate(Path pkcs12, char[] password) { clientCertificate = pkcs12; clientCertificatePassword = password.clone(); return this; }
        /// Explicitly named; logged as a warning at build and shown by `describe()`.
        public Builder insecureSkipTlsVerification() { insecure = true; return this; }
        public Builder userAgentSuffix(String suffix) { userAgentSuffix = Checks.notBlank(suffix, "User agent suffix"); return this; }
        public Builder wireLog(WireLog level) { wireLog = level; return this; }
        public HttpOptions build() { return new HttpOptions(this); }
    }
}
