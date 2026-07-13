package com.dbbaskette.issuebot.service.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownRendererTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    @Test
    void rendersCommonMarkdownStructures() {
        String html = renderer.toHtml("""
                # Title
                Some **bold** and a [link](https://example.com).

                - one
                - two

                ```java
                int x = 1;
                ```
                """);

        assertThat(html).contains("<h1>Title</h1>");
        assertThat(html).contains("<strong>bold</strong>");
        // (commonmark adds rel="nofollow" to links — good.)
        assertThat(html).contains("href=\"https://example.com\"").contains(">link</a>");
        assertThat(html).contains("<li>one</li>");
        assertThat(html).contains("<pre>").contains("int x = 1;");
    }

    @Test
    void rendersGfmTables() {
        String html = renderer.toHtml("""
                | File | Change |
                |------|--------|
                | Foo.java | add method |
                """);
        assertThat(html).contains("<table>").contains("<th>File</th>").contains("<td>Foo.java</td>");
    }

    // === security: the source is LLM output shown in a web page ===

    @Test
    void escapesRawHtml_soInjectedScriptCannotExecute() {
        String html = renderer.toHtml("# Hi\n\n<script>alert('xss')</script>\n\n<img src=x onerror=alert(1)>");

        // The literal tags are escaped to text, never emitted as live markup. (The strings
        // "onerror" / "script" survive as ESCAPED text — that's safe; a browser renders them
        // as characters, not as a tag. The safety property is the absence of live tags.)
        assertThat(html).doesNotContain("<script>");
        assertThat(html).doesNotContain("<img ");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).contains("&lt;img");
    }

    @Test
    void sanitizesJavascriptUrls() {
        String html = renderer.toHtml("[click](javascript:alert(1)) and ![x](javascript:alert(2))");
        assertThat(html).doesNotContain("javascript:");
    }

    @Test
    void nullOrBlankInput_returnsNull_soTemplatesCanGate() {
        assertThat(renderer.toHtml(null)).isNull();
        assertThat(renderer.toHtml("   ")).isNull();
    }
}
