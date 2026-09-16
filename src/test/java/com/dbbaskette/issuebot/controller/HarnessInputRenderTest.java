package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.service.harness.NativeInputProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class HarnessInputRenderTest {
    @Test void questionDraftSurvivesRefreshAndUntrustedTextIsEscaped() throws Exception {
        var request=new HarnessInputController.RequestView(7L,"codex","session","Waiting for your input",true,true,
                "Choose a database","Allow once","<script>bad()</script>",List.of(new NativeInputProtocol.Question("db","Which database?",List.of("Postgres"))));
        String html=render(request);
        assertThat(html).contains("hx-preserve=\"true\"","assistant-response-7","Which database?","Send answer and continue","&lt;script&gt;")
                .doesNotContain("<script>bad()");
        String output=System.getProperty("issuebot.inputVisualOutput");
        if(output!=null) {
            var root=java.nio.file.Path.of(output);java.nio.file.Files.createDirectories(root);
            try(var css=getClass().getClassLoader().getResourceAsStream("static/css/style.css")) {
                String page="<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><style>"
                        +new String(css.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)
                        +"</style></head><body><main style='max-width:850px;padding:24px;margin:auto'><h1>IssueBot — assistant input</h1>"
                        +html+"</main></body></html>";
                java.nio.file.Files.writeString(root.resolve("index.html"),page);
            }
        }
    }
    @Test void disconnectedRequestCannotBeAnswered() {
        String html=render(new HarnessInputController.RequestView(8L,"claude","session","Connection lost — recovery needed",false,false,"Bash","Allow once","{}",List.of()));
        assertThat(html).contains("cannot be replayed").doesNotContain("<form","Allow once");
    }
    private String render(HarnessInputController.RequestView request) {
        var resolver=new ClassLoaderTemplateResolver();resolver.setPrefix("templates/");resolver.setSuffix(".html");
        var engine=new SpringTemplateEngine();engine.setTemplateResolver(resolver);
        var servlet=new MockServletContext();
        var context=new WebContext(JakartaServletWebApplication.buildApplication(servlet)
                .buildExchange(new MockHttpServletRequest(servlet),new MockHttpServletResponse()));
        context.setVariable("assistantRequests",List.of(request));context.setVariable("assistantIssueId",1L);
        return engine.process(new TemplateSpec("fragments/assistant-input",Set.of("panel"),TemplateMode.HTML,null),context);
    }
}
