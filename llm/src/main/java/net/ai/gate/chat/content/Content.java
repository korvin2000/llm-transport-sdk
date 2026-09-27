package net.ai.gate.chat.content;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.ai.gate.json.JsonNull;
import net.ai.gate.json.JsonValue;
import org.jspecify.annotations.Nullable;

/// Sealed, immutable content parts of messages. Media from a [Path] is read when a request is encoded, not before.
/// Wire-derived: variants may be added in minor releases, and [Unknown] preserves parts no variant models.
public sealed interface Content permits Content.Text, Content.Image, Content.Document, Content.Audio,
        Content.Reasoning, Content.Refusal, Content.Unknown, ToolCall, ToolResult {

    static Text text(String text) { return new Text(text, List.of()); }
    static Image image(Path file) { return new Image(new Source.Local(file), mediaType(String.valueOf(file.getFileName())), null); }
    static Image image(URI url) { return new Image(new Source.Remote(url), mediaType(String.valueOf(url.getPath())), null); }
    static Image image(byte[] data, String mediaType) { return new Image(new Source.Inline(data), mediaType, null); }
    static Document document(Path file) { return new Document(new Source.Local(file), mediaType(String.valueOf(file.getFileName())), file.getFileName().toString()); }
    static Document document(byte[] data, String mediaType) { return new Document(new Source.Inline(data), mediaType, null); }
    static Audio audio(byte[] data, String format) { return new Audio(data, format, null); }
    static Reasoning reasoning(String text) { return new Reasoning(text, null, false, JsonNull.INSTANCE); }

    /// A file uploaded to a provider; valid only for that provider and account.
    static Content fileRef(String providerFileId, String mediaType) {
        var source = new Source.Ref(providerFileId);
        return mediaType.startsWith("image/") ? new Image(source, mediaType, null) : new Document(source, mediaType, null);
    }

    private static String mediaType(String fileName) {
        var name = fileName.toLowerCase(Locale.ROOT);
        var extension = name.substring(name.lastIndexOf('.') + 1);
        return Map.of("png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg", "gif", "image/gif", "webp", "image/webp",
                "pdf", "application/pdf", "txt", "text/plain", "md", "text/markdown", "csv", "text/csv", "json", "application/json")
                .getOrDefault(extension, "application/octet-stream");
    }

    /// Where media bytes come from.
    sealed interface Source {
        /// Read at encode time.
        record Local(Path file) implements Source {
            public byte[] read() {
                try { return Files.readAllBytes(file); } catch (IOException e) { throw new UncheckedIOException(e); }
            }
        }
        /// Passed to APIs that accept URLs.
        record Remote(URI url) implements Source { }
        record Inline(byte[] data) implements Source {
            public Inline { data = data.clone(); }
            @Override public byte[] data() { return data.clone(); }
            @Override public boolean equals(Object o) { return o instanceof Inline i && Arrays.equals(data, i.data); }
            @Override public int hashCode() { return Arrays.hashCode(data); }
            @Override public String toString() { return "Inline[" + data.length + " bytes]"; }
        }
        /// A provider file id.
        record Ref(String fileId) implements Source { }
    }

    final class Text implements Content {
        private final String text;
        private final List<Citation> citations;
        private Text(String text, List<Citation> citations) { this.text = text; this.citations = List.copyOf(citations); }
        public static Text of(String text, List<Citation> citations) { return new Text(text, citations); }
        public String text() { return text; }
        public List<Citation> citations() { return citations; }
        @Override public boolean equals(Object o) { return o instanceof Text t && text.equals(t.text) && citations.equals(t.citations); }
        @Override public int hashCode() { return Objects.hash(text, citations); }
        @Override public String toString() { return "Text[" + text.length() + " chars]"; }
    }

    final class Image implements Content {
        private final Source source;
        private final String mediaType;
        private final @Nullable String detail;
        private Image(Source source, String mediaType, @Nullable String detail) { this.source = source; this.mediaType = mediaType; this.detail = detail; }
        /// For codecs and serializers: any source with an explicit media type.
        public static Image of(Source source, String mediaType, @Nullable String detail) { return new Image(source, mediaType, detail); }
        public Source source() { return source; }
        public String mediaType() { return mediaType; }
        /// Resolution hint where APIs accept one: `low`, `high`, `auto`.
        public Optional<String> detail() { return Optional.ofNullable(detail); }
        public Image withDetail(String value) { return new Image(source, mediaType, value); }
        @Override public boolean equals(Object o) { return o instanceof Image i && source.equals(i.source) && mediaType.equals(i.mediaType) && Objects.equals(detail, i.detail); }
        @Override public int hashCode() { return Objects.hash(source, mediaType, detail); }
        @Override public String toString() { return "Image[" + mediaType + ", " + source + "]"; }
    }

    final class Document implements Content {
        private final Source source;
        private final String mediaType;
        private final @Nullable String title;
        private Document(Source source, String mediaType, @Nullable String title) { this.source = source; this.mediaType = mediaType; this.title = title; }
        /// For codecs and serializers: any source with an explicit media type.
        public static Document of(Source source, String mediaType, @Nullable String title) { return new Document(source, mediaType, title); }
        public Source source() { return source; }
        public String mediaType() { return mediaType; }
        public Optional<String> title() { return Optional.ofNullable(title); }
        @Override public boolean equals(Object o) { return o instanceof Document d && source.equals(d.source) && mediaType.equals(d.mediaType) && Objects.equals(title, d.title); }
        @Override public int hashCode() { return Objects.hash(source, mediaType, title); }
        @Override public String toString() { return "Document[" + mediaType + ", " + source + "]"; }
    }

    final class Audio implements Content {
        private final byte[] data;
        private final String format;
        private final @Nullable String transcript;
        private Audio(byte[] data, String format, @Nullable String transcript) { this.data = data.clone(); this.format = format; this.transcript = transcript; }
        public static Audio of(byte[] data, String format, @Nullable String transcript) { return new Audio(data, format, transcript); }
        public byte[] data() { return data.clone(); }
        public String format() { return format; }
        /// For model outputs, where the API returns one.
        public Optional<String> transcript() { return Optional.ofNullable(transcript); }
        @Override public boolean equals(Object o) { return o instanceof Audio a && Arrays.equals(data, a.data) && format.equals(a.format) && Objects.equals(transcript, a.transcript); }
        @Override public int hashCode() { return Objects.hash(Arrays.hashCode(data), format, transcript); }
        @Override public String toString() { return "Audio[" + format + ", " + data.length + " bytes]"; }
    }

    /// Model reasoning. `signature()` is opaque replay data, valid only for the model that produced it;
    /// `redacted()` reasoning has no readable text and replays to its origin only.
    final class Reasoning implements Content {
        private final @Nullable String text, signature;
        private final boolean redacted;
        private final JsonValue providerData;
        private Reasoning(@Nullable String text, @Nullable String signature, boolean redacted, JsonValue providerData) {
            this.text = text; this.signature = signature; this.redacted = redacted; this.providerData = providerData;
        }
        public static Reasoning of(@Nullable String text, @Nullable String signature, boolean redacted, JsonValue providerData) {
            return new Reasoning(text, signature, redacted, providerData);
        }
        public Optional<String> text() { return Optional.ofNullable(text); }
        public Optional<String> signature() { return Optional.ofNullable(signature); }
        public boolean redacted() { return redacted; }
        public JsonValue providerData() { return providerData; }
        @Override public boolean equals(Object o) {
            return o instanceof Reasoning r && Objects.equals(text, r.text) && Objects.equals(signature, r.signature)
                    && redacted == r.redacted && providerData.equals(r.providerData);
        }
        @Override public int hashCode() { return Objects.hash(text, signature, redacted, providerData); }
        @Override public String toString() { return "Reasoning[" + (text == null ? "no text" : text.length() + " chars") + (signature == null ? "" : ", signed") + "]"; }
    }

    final class Refusal implements Content {
        private final String text;
        private Refusal(String text) { this.text = text; }
        public static Refusal of(String text) { return new Refusal(text); }
        public String text() { return text; }
        @Override public boolean equals(Object o) { return o instanceof Refusal r && text.equals(r.text); }
        @Override public int hashCode() { return text.hashCode(); }
        @Override public String toString() { return "Refusal[" + text + "]"; }
    }

    /// A part no variant models, preserved as received and replayed only to its origin.
    final class Unknown implements Content {
        private final String type;
        private final JsonValue raw;
        private Unknown(String type, JsonValue raw) { this.type = type; this.raw = raw; }
        public static Unknown of(String type, JsonValue raw) { return new Unknown(type, raw); }
        public String type() { return type; }
        public JsonValue raw() { return raw; }
        @Override public boolean equals(Object o) { return o instanceof Unknown u && type.equals(u.type) && raw.equals(u.raw); }
        @Override public int hashCode() { return Objects.hash(type, raw); }
        @Override public String toString() { return "Unknown[" + type + "]"; }
    }

    /// A source cited by a text part; indexes are character offsets into that text.
    record Citation(String title, URI source, int startIndex, int endIndex) { }
}
