package com.dbbaskette.issuebot.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "issuebot.github.token=test-token",
        "ISSUEBOT_WEBHOOK_SECRET=test-webhook-secret",
        "spring.datasource.url=jdbc:h2:mem:security-csrf-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc
class SecurityConfigCsrfIntegrationTest {

    private static final String WEBHOOK_SECRET = "test-webhook-secret";

    @Autowired
    MockMvc mockMvc;

    @Test
    void readyStartRequiresValidCsrfToken() throws Exception {
        mockMvc.perform(post("/issues/999999/start"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/issues/999999/start").with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/issues/999999/start").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void readyReleaseRequiresValidCsrfToken() throws Exception {
        mockMvc.perform(post("/issues/999999/ready/release"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/issues/999999/ready/release").with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/issues/999999/ready/release").with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void signedGithubWebhookRemainsCsrfExempt() throws Exception {
        byte[] body = "{\"action\":\"labeled\",\"repository\":{\"full_name\":\"unknown/repo\"}}"
                .getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Hub-Signature-256", sign(body))
                        .header("X-GitHub-Event", "issues"))
                .andExpect(status().isNoContent());
    }

    @Test
    void fullPagePublishesCsrfMetadataAndStandardPostToken() throws Exception {
        String html = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .containsPattern("<meta name=\"_csrf\" content=\"[^\"]+\">")
                .contains("<meta name=\"_csrf_header\" content=\"X-CSRF-TOKEN\">")
                .containsPattern("(?s)<form[^>]*action=\"/processing/pause\"[^>]*>.*?"
                        + "<input type=\"hidden\" name=\"_csrf\" value=\"[^\"]+\"/?>");
    }

    @Test
    void htmxRequestsForwardCsrfHeaderFromLayoutMetadata() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("static/js/app.js")) {
            assertThat(input).isNotNull();
            String javascript = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(javascript)
                    .contains("meta[name=\"_csrf\"]", "meta[name=\"_csrf_header\"]")
                    .contains("evt.detail.headers[header] = token");
        }
    }

    @Test
    void htmxStylePostWithPublishedCsrfHeaderReachesController() throws Exception {
        MvcResult page = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) page.getRequest().getSession(false);
        String html = page.getResponse().getContentAsString();
        String token = metaContent(html, "_csrf");
        String header = metaContent(html, "_csrf_header");

        assertThat(session).isNotNull();
        mockMvc.perform(post("/issues/999999/start")
                        .session(session)
                        .header("HX-Request", "true")
                        .header(header, token))
                .andExpect(status().isNotFound());
    }

    private static String metaContent(String html, String name) {
        Matcher matcher = Pattern.compile("<meta name=\\\"" + Pattern.quote(name)
                + "\\\" content=\\\"([^\\\"]+)\\\">").matcher(html);
        assertThat(matcher.find()).as("meta[%s] is rendered", name).isTrue();
        return matcher.group(1);
    }

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
