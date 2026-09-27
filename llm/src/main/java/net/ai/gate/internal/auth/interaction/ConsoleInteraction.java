package net.ai.gate.internal.auth.interaction;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.Charset;

import net.ai.gate.auth.interaction.AuthInteraction;
import net.ai.gate.auth.interaction.AuthNotice;
import net.ai.gate.auth.interaction.AuthPrompt;

/// `AuthInteraction.console()`: prompts on standard output, answers from the console (secrets unechoed when a
/// console is attached), and a desktop browser for `OpenUrl` when one is available.
public final class ConsoleInteraction implements AuthInteraction {
    private final PrintStream out = System.out;
    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()));

    @Override public String prompt(AuthPrompt prompt) {
        return switch (prompt) {
            case AuthPrompt.SecretText s -> {
                var console = System.console();
                if (console == null) yield ask(s.message());
                var secret = console.readPassword("%s: ", s.message());
                if (secret == null) throw new IllegalStateException("Console input closed");
                yield new String(secret);
            }
            case AuthPrompt.Text t -> ask(t.message() + t.placeholder().map(p -> " [" + p + "]").orElse(""));
            case AuthPrompt.Code c -> ask(c.message());
            case AuthPrompt.Select s -> {
                for (int i = 0; i < s.options().size(); i++) out.printf("  %d) %s%n", i + 1, s.options().get(i).label());
                var answer = ask(s.message());
                try {
                    yield s.options().get(Integer.parseInt(answer.strip()) - 1).id();
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("Not an option: " + answer, e);
                }
            }
        };
    }

    @Override public void notify(AuthNotice notice) {
        switch (notice) {
            case AuthNotice.OpenUrl u -> {
                out.println("Open this URL to continue: " + u.url());
                u.instructions().ifPresent(out::println);
                browse(u.url());
            }
            case AuthNotice.DeviceCode d -> out.printf("Visit %s and enter the code %s (expires %s)%n", d.verificationUri(), d.userCode(), d.expiresAt());
            case AuthNotice.Info i -> {
                out.println(i.message());
                i.links().forEach(link -> out.println("  " + link));
            }
            case AuthNotice.Progress p -> out.println(p.message());
        }
    }

    private String ask(String message) {
        out.print(message + ": ");
        out.flush();
        try {
            var line = in.readLine();
            if (line == null) throw new IllegalStateException("Console input closed");
            return line;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Best effort: headless hosts, missing `java.desktop` and unsupported platforms keep the printed URL only.
    private static void browse(URI url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) Desktop.getDesktop().browse(url);
        } catch (IOException | UnsupportedOperationException | SecurityException | LinkageError ignored) {
            // the URL is already printed
        }
    }
}
