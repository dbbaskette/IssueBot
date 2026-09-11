package com.dbbaskette.issuebot.service.harness;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodingHarnessRegistryTest {

    @Test
    void resolvesStableAndLegacyHarnessIds() {
        var claude = adapter("claude");
        var codex = adapter("codex");
        var registry = new CodingHarnessRegistry(List.of(claude, codex));

        assertThat(registry.require("claude")).isSameAs(claude);
        assertThat(registry.require("CLAUDE_CODE")).isSameAs(claude);
        assertThat(registry.require("CODEX")).isSameAs(codex);
        assertThatThrownBy(() -> registry.require("cursor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursor");
    }

    @Test
    void exposesAdaptersInStableIdOrder() {
        var codex = adapter("codex");
        var claude = adapter("claude");

        var registry = new CodingHarnessRegistry(List.of(codex, claude));

        assertThat(registry.adapters()).containsExactly(claude, codex);
    }

    @Test
    void rejectsDuplicateNormalizedHarnessIds() {
        var registryAdapters = List.of(adapter("claude"), adapter("CLAUDE_CODE"));

        assertThatThrownBy(() -> new CodingHarnessRegistry(registryAdapters))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("claude");
    }

    private CodingHarnessAdapter adapter(String id) {
        return new CodingHarnessAdapter() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String displayName() {
                return id;
            }

            @Override
            public List<HarnessModel> models() {
                return List.of();
            }

            @Override
            public HarnessCapabilities capabilities() {
                return HarnessCapabilities.NONE;
            }

            @Override
            public boolean checkCliAvailable() {
                return true;
            }

            @Override
            public boolean checkSubscriptionAuthentication() {
                return true;
            }

            @Override
            public HarnessExecutionResult execute(HarnessExecutionRequest request, Consumer<String> lineCallback) {
                return new HarnessExecutionResult();
            }
        };
    }
}
