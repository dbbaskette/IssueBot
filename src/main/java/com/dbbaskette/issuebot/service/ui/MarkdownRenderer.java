package com.dbbaskette.issuebot.service.ui;

import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.DefaultUrlSanitizer;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Renders model-produced markdown (plan/spec documents) to HTML for the dashboard.
 *
 * <p>Security: the source is LLM output that ends up in a web page, so raw HTML is NOT trusted.
 * {@code escapeHtml(true)} makes any literal {@code <script>}/{@code <img onerror=...>} in the
 * markdown render as text rather than live markup. URL sanitizing is restricted to an explicit
 * {@code http/https/mailto} allow-list — note commonmark's DEFAULT sanitizer also permits
 * {@code data:}, which would let a {@code data:text/html;base64,...} link execute, so we pass a
 * custom {@link DefaultUrlSanitizer} that drops it (and {@code javascript:}). Callers emit the
 * result via {@code th:utext} (unescaped) — safe precisely because of these settings.
 */
@Component
public class MarkdownRenderer {

    // Explicit allow-list — deliberately EXCLUDES commonmark's default `data`, which is an XSS
    // vector (data:text/html). Anything not on this list is stripped from href/src.
    private static final List<String> SAFE_URL_PROTOCOLS = List.of("http", "https", "mailto");

    private final Parser parser;
    private final HtmlRenderer renderer;

    public MarkdownRenderer() {
        List<org.commonmark.Extension> extensions = List.of(TablesExtension.create());
        this.parser = Parser.builder().extensions(extensions).build();
        this.renderer = HtmlRenderer.builder()
                .extensions(extensions)
                .escapeHtml(true)     // literal HTML in the markdown is escaped, not executed
                .sanitizeUrls(true)
                .urlSanitizer(new DefaultUrlSanitizer(SAFE_URL_PROTOCOLS)) // http/https/mailto only
                .build();
    }

    /**
     * Render markdown to safe HTML. Returns null for null/blank input so templates can gate on it.
     */
    public String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return null;
        }
        Node document = parser.parse(markdown);
        return renderer.render(document);
    }
}
