package com.dbbaskette.issuebot.controller;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ViewResolverTest {

    @Test
    void returnsFragmentForHtmxRequest() {
        assertThat(ViewResolver.view("issues", true)).isEqualTo("issues :: content");
    }

    @Test
    void returnsLayoutForFullPageRequest() {
        assertThat(ViewResolver.view("issues", false)).isEqualTo("layout");
    }

    @Test
    void approvalRedirectReturnsToCurrentIssueOnlyForLiteralIssue() {
        assertThat(ViewResolver.approvalRedirect("issue", 42L, "redirect:/approvals"))
                .isEqualTo("redirect:/issues/42");
        assertThat(ViewResolver.approvalRedirect("https://evil.example", 42L, "redirect:/approvals"))
                .isEqualTo("redirect:/approvals");
    }

    @Test
    void approvalRedirectReturnsToInboxOnlyForLiteralInbox() {
        assertThat(ViewResolver.approvalRedirect("inbox", 42L, "redirect:/approvals"))
                .isEqualTo("redirect:/inbox");
        assertThat(ViewResolver.approvalRedirect("issue", null, "redirect:/approvals"))
                .isEqualTo("redirect:/approvals");
    }
}
