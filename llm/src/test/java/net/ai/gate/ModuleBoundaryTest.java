package net.ai.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// An external consumer on the module path sees exactly the exported API: value types from the sub-packages, the
/// facade, service-loaded bundles and sealed hierarchies that span packages — and none of the internals.
class ModuleBoundaryTest {
    private static Path jar;
    /// The jar plus the annotation modules its API mentions (`requires static transitive`): what a consumer puts on the module path.
    private static String modulePath;

    @BeforeAll
    static void locateJar() {
        jar = Path.of(System.getProperty("ai-gate.jar", ""));
        assertTrue(Files.isRegularFile(jar), "the jar is built before the tests run: " + jar);
        modulePath = jar + File.pathSeparator + System.getProperty("ai-gate.compileClasspath", "");
    }

    private static final String CONSUMER = """
            package probe;
            import net.ai.gate.Llm;
            import net.ai.gate.auth.Credential;
            import net.ai.gate.auth.Environment;
            import net.ai.gate.auth.oauth.OAuthCredential;
            import net.ai.gate.chat.Conversation;
            import net.ai.gate.chat.content.Content;
            import net.ai.gate.chat.content.ToolCall;
            import net.ai.gate.providers.Providers;
            import net.ai.gate.testing.FakeProvider;
            public class Consumer {
                public static void main(String[] args) {
                    var fake = FakeProvider.create().reply("from the module path");
                    try (var llm = Llm.builder().discoverProviders().provider(fake.provider()).environment(Environment.none())
                            .catalog(c -> c.offline()).build()) {
                        var text = llm.complete(llm.model("fake", "fake"), Conversation.of("hi")).text();
                        Content part = ToolCall.of("c1", "tool", "{}");
                        String kind = switch (part) { case ToolCall c -> "call"; default -> "other"; };
                        Credential credential = OAuthCredential.builder(net.ai.gate.auth.Secret.of("token-0123456789"), "issuer", "client").build();
                        String type = switch (credential) { case OAuthCredential o -> "oauth"; case net.ai.gate.auth.ApiKeyCredential k -> "key"; };
                        System.out.println(text + "|" + kind + "|" + type + "|" + Providers.presets().size() + "|"
                                + llm.models().all("anthropic").isEmpty() + "|" + Consumer.class.getModule().isNamed());
                    }
                }
            }
            """;

    private static int compile(Path sources, Path out, String... options) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var args = new java.util.ArrayList<>(List.of(options));
        args.addAll(List.of("-d", out.toString(), "--release", "26"));
        try (var files = Files.list(sources)) { files.map(Path::toString).forEach(args::add); } catch (IOException e) { throw new RuntimeException(e); }
        return compiler.run(null, null, null, args.toArray(String[]::new));
    }

    @Test
    void anExportedApiConsumerCompilesAndRunsOnTheModulePath(@TempDir Path dir) throws Exception {
        var src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(src.resolve("module-info.java"), "module consumer { requires net.ai.gate; }");
        Files.writeString(src.resolve("Consumer.java"), CONSUMER);
        var out = dir.resolve("out");
        assertEquals(0, compile(src, out, "--module-path", modulePath), "the consumer compiles against the exported API");

        var launcher = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(launcher, "--module-path", modulePath + File.pathSeparator + out, "-m", "consumer/probe.Consumer")
                .redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.trim().endsWith("from the module path|call|oauth|13|false|true"), output);
    }

    @Test
    void internalsAreNotVisibleToAModuleConsumer(@TempDir Path dir) throws IOException {
        var src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(src.resolve("module-info.java"), "module consumer { requires net.ai.gate; }");
        Files.writeString(src.resolve("Leak.java"), "package probe; public class Leak { Object o = net.ai.gate.internal.validation.Checks.class; }");
        assertFalse(compile(src, dir.resolve("out"), "--module-path", modulePath) == 0, "internal packages are not exported");
    }

    @Test
    void theSameConsumerWorksOnTheClassPath(@TempDir Path dir) throws Exception {
        var src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(src.resolve("Consumer.java"), CONSUMER);
        var out = dir.resolve("out");
        assertEquals(0, compile(src, out, "-cp", jar.toString()));
        var launcher = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(launcher, "-cp", jar + File.pathSeparator + out, "probe.Consumer").redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.trim().endsWith("from the module path|call|oauth|13|false|false"), output);
    }
}
