package com.dbbaskette.issuebot.service.github;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubApiClientTest {

    @Test
    void markPrReadyUsesGitHubsReadyForReviewMutation() {
        AtomicReference<ClientRequest> graphQlRequest = new AtomicReference<>();
        GitHubApiClient client = client(request -> {
            if (request.url().getPath().equals("/repos/acme/widgets/pulls/55")) {
                return jsonResponse(HttpStatus.OK,
                        "{\"node_id\":\"PR_node_55\",\"draft\":true}");
            }
            graphQlRequest.set(request);
            return jsonResponse(HttpStatus.OK, """
                    {"data":{"markPullRequestReadyForReview":{"pullRequest":{"isDraft":false}}}}
                    """);
        });

        client.markPrReady("acme", "widgets", 55);

        assertThat(requestBody(graphQlRequest.get()))
                .contains("markPullRequestReadyForReview")
                .contains("PR_node_55")
                .doesNotContain("markPullRequestAsReady");
    }

    @Test
    void mergeFailureIncludesGitHubsExplanation() {
        GitHubApiClient client = client(request -> jsonResponse(HttpStatus.METHOD_NOT_ALLOWED, """
                {"message":"Pull Request is still a draft","documentation_url":"https://docs.github.com/rest/pulls/pulls#merge-a-pull-request"}
                """));

        assertThatThrownBy(() -> client.mergePullRequest(
                "acme", "widgets", 55, "IssueBot: fix", "squash"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Pull Request is still a draft")
                .hasMessageContaining("HTTP 405");
    }

    private static GitHubApiClient client(
            java.util.function.Function<ClientRequest, Mono<ClientResponse>> exchange) {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(exchange::apply)
                .build();
        return new GitHubApiClient(webClient);
    }

    private static Mono<ClientResponse> jsonResponse(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status)
                .header("Content-Type", "application/json")
                .body(body)
                .build());
    }

    private static String requestBody(ClientRequest request) {
        MockClientHttpRequest output = new MockClientHttpRequest(request.method(), request.url());
        request.body().insert(output, new BodyInserter.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return ExchangeStrategies.withDefaults().messageWriters();
            }

            @Override
            public Optional<ServerHttpRequest> serverRequest() {
                return Optional.empty();
            }

            @Override
            public Map<String, Object> hints() {
                return Map.of();
            }
        }).block();
        return output.getBodyAsString().block();
    }
}
