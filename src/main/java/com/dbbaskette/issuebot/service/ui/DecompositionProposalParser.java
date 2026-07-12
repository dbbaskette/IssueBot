package com.dbbaskette.issuebot.service.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Parses a {@code TrackedIssue#getDecompositionProposal()} JSON blob into the list of
 * sub-issue title/description maps used by the "Proposed Split" card. Originally lived inline in
 * {@code IssueController#populateDetailModel}; extracted (#91 Needs You inbox) so the inbox's
 * condensed split-proposals group can reuse the exact same parsing (down to titles) rather than
 * duplicating the try/catch.
 */
public final class DecompositionProposalParser {

    private static final Logger log = LoggerFactory.getLogger(DecompositionProposalParser.class);
    private static final TypeReference<List<Map<String, Object>>> TYPE = new TypeReference<>() {};

    private DecompositionProposalParser() {
    }

    /**
     * @return the parsed sub-issue list, or {@code null} when {@code json} is null/unparseable —
     * matching the original inline behavior, where a parse failure simply omits the model
     * attribute (the card doesn't render) rather than showing an empty list.
     */
    public static List<Map<String, Object>> parseOrNull(ObjectMapper mapper, String json, Long issueId) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse decomposition proposal for issue {}: {}", issueId, e.getMessage());
            return null;
        }
    }

    /** Convenience for callers that only need the proposed sub-issue titles (the inbox card). */
    public static List<String> titlesOrEmpty(ObjectMapper mapper, String json, Long issueId) {
        List<Map<String, Object>> proposal = parseOrNull(mapper, json, issueId);
        if (proposal == null) {
            return List.of();
        }
        return proposal.stream()
                .map(sub -> {
                    Object title = sub.get("title");
                    return title != null ? title.toString() : "(untitled)";
                })
                .toList();
    }
}
