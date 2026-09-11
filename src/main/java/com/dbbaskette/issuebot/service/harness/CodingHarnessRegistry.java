package com.dbbaskette.issuebot.service.harness;

import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Registry of the coding-harness adapters installed in this IssueBot instance. */
@Service
public class CodingHarnessRegistry {

    private final Map<String, CodingHarnessAdapter> adapters;

    public CodingHarnessRegistry(List<CodingHarnessAdapter> adapters) {
        Objects.requireNonNull(adapters, "Coding harness adapters are required");
        Map<String, CodingHarnessAdapter> sortedAdapters = new TreeMap<>();
        for (CodingHarnessAdapter adapter : adapters) {
            CodingHarnessAdapter nonNullAdapter = Objects.requireNonNull(adapter,
                    "Coding harness adapter must not be null");
            String id = nonNullAdapter.id();
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Coding harness id must not be blank");
            }
            String normalized = HarnessIds.normalize(id);
            if (sortedAdapters.putIfAbsent(normalized, nonNullAdapter) != null) {
                throw new IllegalArgumentException("Duplicate coding harness id: " + normalized);
            }
        }
        this.adapters = Collections.unmodifiableMap(sortedAdapters);
    }

    public CodingHarnessAdapter require(String id) {
        if (id == null || id.isBlank()) throw new HarnessSelectionException(
                HarnessSelectionException.Problem.HARNESS, "A harness identity is required");
        String normalized = HarnessIds.normalize(id);
        CodingHarnessAdapter adapter = adapters.get(normalized);
        if (adapter == null) {
            throw new HarnessSelectionException(HarnessSelectionException.Problem.HARNESS,
                    "Unsupported coding harness: " + id);
        }
        return adapter;
    }

    public List<CodingHarnessAdapter> adapters() {
        return List.copyOf(adapters.values());
    }
}
