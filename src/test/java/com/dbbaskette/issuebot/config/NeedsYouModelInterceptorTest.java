package com.dbbaskette.issuebot.config;

import com.dbbaskette.issuebot.service.ui.NeedsYouService;
import com.dbbaskette.issuebot.service.ui.NeedsYouSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NeedsYouModelInterceptorTest {
    private final NeedsYouService service = mock(NeedsYouService.class);
    private final NeedsYouModelInterceptor interceptor = new NeedsYouModelInterceptor(service);

    @ParameterizedTest
    @ValueSource(strings = {"layout", "dashboard :: content", "issues :: content", "issue-detail :: content",
            "approvals :: content", "repositories :: content", "costs :: content", "settings :: content", "setup :: content"})
    void everyPageGetsCanonicalCount(String viewName) {
        NeedsYouSnapshot snapshot = snapshot(3);
        when(service.snapshot()).thenReturn(snapshot);
        ModelAndView view = new ModelAndView(viewName);
        view.addObject("needsYouCount", 99L);

        interceptor.postHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), this, view);

        assertThat(view.getModel()).containsEntry("needsYouCount", 3L)
                .containsEntry("needsYouSnapshot", snapshot);
        verify(service).snapshot();
    }

    @Test
    void inboxReusesItsExactSnapshotInsteadOfRacingAnotherRead() {
        NeedsYouSnapshot snapshot = snapshot(0);
        ModelAndView view = new ModelAndView("inbox :: content");
        view.addObject("needsYouSnapshot", snapshot);
        view.addObject("totalCount", 0L);

        interceptor.postHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), this, view);

        assertThat(view.getModel().get("needsYouCount")).isEqualTo(view.getModel().get("totalCount"));
        verifyNoInteractions(service);
    }

    @Test
    void postMutationRenderUsesPostHandlerState() {
        NeedsYouSnapshot empty = snapshot(0);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/repositories/1/delete");
        ModelAndView view = new ModelAndView("repositories :: content");
        // Handler has already completed the deletion before postHandle runs.
        when(service.snapshot()).thenReturn(empty);

        interceptor.postHandle(request, new MockHttpServletResponse(), this, view);

        assertThat(view.getModel().get("needsYouCount")).isEqualTo(0L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"redirect:/inbox", "issue-detail :: live-status-poll", "fragments/needs-you :: live", "forward:/error"})
    void unrelatedOrAlreadyPopulatedLiveViewsDoNotQuery(String viewName) {
        interceptor.postHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), this,
                new ModelAndView(viewName));
        verifyNoInteractions(service);
    }

    private static NeedsYouSnapshot snapshot(int count) {
        var rows = java.util.stream.IntStream.range(0, count).mapToObj(i ->
                new com.dbbaskette.issuebot.model.TrackedIssue(
                        new com.dbbaskette.issuebot.model.WatchedRepo("owner", "repo"), i, "Issue"))
                .toList();
        return new NeedsYouSnapshot(rows, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), 0, 0);
    }
}
