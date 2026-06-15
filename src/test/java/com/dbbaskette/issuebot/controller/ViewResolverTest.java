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
}
