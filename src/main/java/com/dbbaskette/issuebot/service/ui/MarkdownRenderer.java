package com.dbbaskette.issuebot.service.ui;

import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Renders model-produced markdown (plan/spec documents) to HTML for the dashboard.
 *
 * <p>Security: the source is LLM output that ends up in a web page, so raw HTML is NOT trusted.
 * {@code escapeHtml(true)} makes any literal {@code <script>}/{@code <img onerror=...>} in the
 * markdown render as text rather than live markup, and {@code sanitizeUrls(true)} strips
 * {@code javascript:}-style link/image URLs. Callers still emit the result via {@code th:utext}
 * (unescaped) — that's safe precisely because those two settings make the produced HTML safe.
 */
@Component
public class MarkdownRenderer {

    private final Parser parser;
    private final HtmlRenderer renderer;

    public MarkdownRenderer() {
        List<org.commonmark.Extension> extensions = List.of(TablesExtension.create());
        this.parser = Parser.builder().extensions(extensions).build();
        this.renderer = HtmlRenderer.builder()
                .extensions(extensions)
                .escapeHtml(true)     // literal HTML in the markdown is escaped, not executed
                .sanitizeUrls(true)   // drop javascript:/data: link & image URLs
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
