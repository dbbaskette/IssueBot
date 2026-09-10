package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.github.GitHubApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DecompositionGroupService {
    private static final Logger log = LoggerFactory.getLogger(DecompositionGroupService.class);
    private static final Pattern LEGACY_PARENT = Pattern.compile("decomposed from #(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PART = Pattern.compile("^\\s*(\\d+)\\s*/\\s*\\d+\\s*:");
    private static final Set<IssueStatus> ATTENTION = EnumSet.of(
            IssueStatus.FAILED, IssueStatus.COOLDOWN, IssueStatus.AWAITING_APPROVAL,
            IssueStatus.AWAITING_PLAN_APPROVAL, IssueStatus.READY_TO_START,
            IssueStatus.AWAITING_DECOMPOSITION, IssueStatus.BLOCKED);

    private final DecompositionGroupRepository groups;
    private final DecompositionChildRepository children;
    private final TrackedIssueRepository issues;
    private final DecompositionGroupTransactionManager transactions;
    private final GitHubApiClient github;
    private final WorkflowCancellationService cancellations;

    public DecompositionGroupService(
            DecompositionGroupRepository groups, DecompositionChildRepository children,
            TrackedIssueRepository issues, DecompositionGroupTransactionManager transactions,
            GitHubApiClient github, WorkflowCancellationService cancellations) {
        this.groups = groups;
        this.children = children;
        this.issues = issues;
        this.transactions = transactions;
        this.github = github;
        this.cancellations = cancellations;
    }

    public void createOrResume(Long groupId) {
        DecompositionGroup group = groups.findById(groupId).orElseThrow();
        if (group.isDispatchSuspended()) return;
        WatchedRepo repo = group.getRepo();
        try {
            List<JsonNode> remote = safe(github.listIssues(
                    repo.getOwner(), repo.getName(), "issuebot-decomposed", "all"));
            for (DecompositionChild child : children.findByGroupOrderBySequencePositionAsc(group)) {
                if (child.getCreationState() == DecompositionChildState.CREATED) continue;
                String marker = childMarker(group.getId(), child.getSequencePosition());
                JsonNode found = remote.stream()
                        .filter(node -> node.path("body").asText("").contains(marker))
                        .findFirst().orElse(null);
                if (found == null) {
                    int parentNumber = group.getParentIssue().getIssueNumber();
                    Set<Integer> claimed = children.findByGroupOrderBySequencePositionAsc(group).stream()
                            .map(DecompositionChild::getGithubIssueNumber).filter(Objects::nonNull)
                            .collect(java.util.stream.Collectors.toSet());
                    found = remote.stream()
                            .filter(node -> !claimed.contains(node.path("number").asInt()))
                            .filter(node -> node.path("title").asText("")
                                    .equals(child.getProposedTitle()))
                            .filter(node -> {
                                Matcher legacy = LEGACY_PARENT.matcher(node.path("body").asText(""));
                                return legacy.find()
                                        && Integer.parseInt(legacy.group(1)) == parentNumber;
                            })
                            .findFirst().orElse(null);
                }
                if (found == null) {
                    JsonNode created = github.createIssue(repo.getOwner(), repo.getName(),
                            child.getProposedTitle(),
                            child.getProposedBody() + "\n\n" + marker,
                            List.of("agent-ready", "issuebot-decomposed"));
                    found = created;
                    remote = new ArrayList<>(remote);
                    remote.add(created);
                }
                int linkedNumber = found.path("number").asInt();
                transactions.linkCreatedChild(groupId, child.getId(), linkedNumber);
                DecompositionGroup currentGroup = groups.findById(groupId).orElseThrow();
                if (currentGroup.getState() == DecompositionGroupState.ABANDONING
                        || !currentGroup.getState().unfinished()) {
                    github.removeLabel(repo.getOwner(), repo.getName(), linkedNumber, "agent-ready");
                    return;
                }
            }
            postCreatedCommentOnce(group);
            github.addLabels(repo.getOwner(), repo.getName(),
                    group.getParentIssue().getIssueNumber(), List.of("issuebot-parent"));
            github.removeLabel(repo.getOwner(), repo.getName(),
                    group.getParentIssue().getIssueNumber(), "agent-ready");
            transactions.activate(groupId);
        } catch (Exception failure) {
            transactions.recordError(groupId, failure.getMessage());
            log.warn("Decomposition #{} creation remains resumable: {}", groupId, failure.getMessage());
        }
    }

    public void reconcileRepo(WatchedRepo repo) {
        recoverLegacyGroups(repo);
        groups.findByStateInOrderByIdAsc(EnumSet.of(
                        DecompositionGroupState.CREATING, DecompositionGroupState.WAITING,
                        DecompositionGroupState.ACTIVE, DecompositionGroupState.NEEDS_ATTENTION,
                        DecompositionGroupState.COMPLETING, DecompositionGroupState.ABANDONING))
                .stream().filter(g -> Objects.equals(g.getRepo().getId(), repo.getId()))
                .forEach(g -> reconcileGroup(g.getId()));
    }

    public void reconcileGroup(Long groupId) {
        DecompositionGroup group = groups.findById(groupId).orElseThrow();
        if (group.isDispatchSuspended()) return;
        if (group.getState() == DecompositionGroupState.CREATING) {
            createOrResume(groupId);
            group = groups.findById(groupId).orElseThrow();
            if (group.getState() == DecompositionGroupState.CREATING) return;
        }
        if (group.getState() == DecompositionGroupState.WAITING) {
            if (groups.findOwningByRepo(group.getRepo().getId()).isEmpty()
                    && hasNoUnrelatedActiveWork(group)) {
                transactions.reconcileState(groupId, DecompositionGroupState.ACTIVE, null);
            }
            return;
        }
        if (group.getState() == DecompositionGroupState.ABANDONING) {
            boolean running = children.findByGroupOrderBySequencePositionAsc(group).stream()
                    .map(DecompositionChild::getTrackedIssue).filter(Objects::nonNull)
                    .anyMatch(i -> i.getStatus() == IssueStatus.IN_PROGRESS);
            if (!running) finalizeAbandon(group);
            return;
        }
        if (!group.getState().ownsRepository()) return;

        List<DecompositionChild> ordered = children.findByGroupOrderBySequencePositionAsc(group);
        Optional<DecompositionChild> current = group.currentChild(ordered);
        if (current.isPresent()) {
            DecompositionChild child = current.orElseThrow();
            if (child.getTrackedIssue() == null) {
                transactions.reconcileState(groupId, DecompositionGroupState.NEEDS_ATTENTION,
                        "A child issue has not been linked on GitHub.");
                return;
            }
            JsonNode remote = github.getIssue(group.getRepo().getOwner(), group.getRepo().getName(),
                    child.getTrackedIssue().getIssueNumber());
            if ("closed".equals(remote.path("state").asText())
                    && child.getTrackedIssue().getStatus() != IssueStatus.COMPLETED) {
                transactions.reconcileState(groupId, DecompositionGroupState.NEEDS_ATTENTION,
                        "Child #" + child.getTrackedIssue().getIssueNumber()
                                + " was closed on GitHub without completing in IssueBot.");
            } else if (ATTENTION.contains(child.getTrackedIssue().getStatus())) {
                transactions.reconcileState(groupId, DecompositionGroupState.NEEDS_ATTENTION,
                        "Child #" + child.getTrackedIssue().getIssueNumber()
                                + " needs operator action: " + child.getTrackedIssue().getStatus() + ".");
            } else {
                transactions.reconcileState(groupId, DecompositionGroupState.ACTIVE, null);
            }
            ensureParentOpen(group);
            return;
        }
        if (transactions.reconcileState(groupId, DecompositionGroupState.COMPLETING, null)) {
            completeParent(groupId);
        }
    }

    private void completeParent(Long groupId) {
        DecompositionGroup group = groups.findById(groupId).orElseThrow();
        WatchedRepo repo = group.getRepo();
        int parent = group.getParentIssue().getIssueNumber();
        try {
            String marker = completeMarker(groupId);
            JsonNode remote = github.getIssue(repo.getOwner(), repo.getName(), parent);
            if (!"closed".equals(remote.path("state").asText())) {
                github.closeIssue(repo.getOwner(), repo.getName(), parent);
                remote = github.getIssue(repo.getOwner(), repo.getName(), parent);
            }
            if (!"closed".equals(remote.path("state").asText())) return;
            if (!transactions.complete(groupId)) {
                ensureParentOpenStrict(group);
                return;
            }
            List<JsonNode> comments = safe(github.listIssueComments(
                    repo.getOwner(), repo.getName(), parent));
            if (comments.stream().noneMatch(c -> c.path("body").asText("").contains(marker))) {
                github.addComment(repo.getOwner(), repo.getName(), parent,
                        "All decomposition children completed in IssueBot. Closing the tracking issue.\n\n" + marker);
            }
        } catch (Exception failure) {
            transactions.recordError(groupId, failure.getMessage());
        }
    }

    private boolean ensureParentOpen(DecompositionGroup group) {
        int parentNumber = group.getParentIssue().getIssueNumber();
        try {
            JsonNode parent = github.getIssue(group.getRepo().getOwner(), group.getRepo().getName(),
                    parentNumber);
            if ("closed".equals(parent.path("state").asText())) {
                github.reopenIssue(group.getRepo().getOwner(), group.getRepo().getName(),
                        parentNumber);
                parent = github.getIssue(group.getRepo().getOwner(), group.getRepo().getName(),
                        parentNumber);
            }
            if ("open".equals(parent.path("state").asText())) return true;
            markParentReopenFailure(group, "GitHub did not confirm that the parent is open.");
        } catch (Exception failure) {
            markParentReopenFailure(group, failure.getMessage());
        }
        return false;
    }

    private void markParentReopenFailure(DecompositionGroup group, String detail) {
        String reason = "Parent #" + group.getParentIssue().getIssueNumber()
                + " is closed and could not be reopened. Reopen it on GitHub, then retry.";
        transactions.reconcileState(group.getId(), DecompositionGroupState.NEEDS_ATTENTION, reason);
        transactions.recordError(group.getId(),
                detail == null || detail.isBlank() ? reason : detail);
    }

    public List<DecompositionGroup> recoverLegacyGroups(WatchedRepo repo) {
        List<DecompositionGroup> recovered = new ArrayList<>();
        List<JsonNode> parents;
        List<JsonNode> subs;
        try {
            parents = safe(github.listIssues(repo.getOwner(), repo.getName(), "issuebot-parent", "all"));
            subs = safe(github.listIssues(repo.getOwner(), repo.getName(), "issuebot-decomposed", "all"));
        } catch (Exception unavailable) {
            return recovered;
        }
        for (JsonNode remoteParent : parents) {
            int parentNumber = remoteParent.path("number").asInt();
            Optional<TrackedIssue> parent = issues.findByRepoAndIssueNumber(repo, parentNumber);
            if (parent.isEmpty() || parent.orElseThrow().getStatus() != IssueStatus.DECOMPOSED
                    || groups.findByParentIssue(parent.orElseThrow()).isPresent()) continue;
            List<JsonNode> matching = subs.stream().filter(sub -> {
                Matcher matcher = LEGACY_PARENT.matcher(sub.path("body").asText(""));
                return matcher.find() && Integer.parseInt(matcher.group(1)) == parentNumber;
            }).sorted(Comparator.comparingInt(this::legacyOrder)
                    .thenComparingInt(n -> n.path("number").asInt())).toList();
            if (matching.isEmpty()) continue;
            List<DecompositionGroupTransactionManager.ChildIntent> intents = new ArrayList<>();
            for (int index = 0; index < matching.size(); index++) {
                JsonNode child = matching.get(index);
                intents.add(new DecompositionGroupTransactionManager.ChildIntent(index + 1,
                        child.path("title").asText("Part " + (index + 1)),
                        child.path("body").asText("")));
            }
            DecompositionGroup group = transactions.beginGroup(parent.orElseThrow().getId(), intents);
            List<DecompositionChild> local = children.findByGroupOrderBySequencePositionAsc(group);
            for (int index = 0; index < matching.size(); index++) {
                transactions.linkCreatedChild(group.getId(), local.get(index).getId(),
                        matching.get(index).path("number").asInt());
            }
            recovered.add(transactions.activate(group.getId()));
        }
        return recovered;
    }

    public AbandonResult abandon(Long parentIssueId, String reason, String actor) {
        if (reason == null || reason.isBlank()) return new AbandonResult(false, "A release reason is required.");
        if (reason.strip().length() > 2000) {
            return new AbandonResult(false, "The release reason must be 2,000 characters or fewer.");
        }
        reason = reason.strip();
        actor = actor == null || actor.isBlank() ? "local operator"
                : actor.strip().substring(0, Math.min(actor.strip().length(), 255));
        TrackedIssue parent = issues.findById(parentIssueId).orElse(null);
        if (parent == null) return new AbandonResult(false, "Parent issue not found.");
        DecompositionGroup group = groups.findByParentIssue(parent).orElse(null);
        if (group == null || !group.getState().unfinished()) {
            return new AbandonResult(false, "No active decomposition group exists.");
        }
        DecompositionGroupTransactionManager.AbandonPreparation preparation =
                transactions.requestAbandon(group.getId(), reason, actor);
        if (preparation.runningIssueId() != null) {
            cancellations.requestCancel(preparation.runningIssueId());
            return new AbandonResult(false, "Cancellation requested; ownership remains until the worker stops.");
        }
        finalizeAbandon(groups.findById(group.getId()).orElseThrow());
        return new AbandonResult(true, "Decomposition released.");
    }

    private boolean hasNoUnrelatedActiveWork(DecompositionGroup group) {
        Set<Long> memberIds = children.findByGroupOrderBySequencePositionAsc(group).stream()
                .map(DecompositionChild::getTrackedIssue).filter(Objects::nonNull)
                .map(TrackedIssue::getId).collect(java.util.stream.Collectors.toSet());
        return issues.findByRepoAndStatusIn(group.getRepo(), List.of(
                        IssueStatus.IN_PROGRESS, IssueStatus.AWAITING_APPROVAL,
                        IssueStatus.AWAITING_PLAN_APPROVAL, IssueStatus.READY_TO_START,
                        IssueStatus.AWAITING_DECOMPOSITION)).stream()
                .noneMatch(issue -> !Objects.equals(issue.getId(), group.getParentIssue().getId())
                        && !memberIds.contains(issue.getId()));
    }

    private void finalizeAbandon(DecompositionGroup group) {
        String reason = group.getReleaseReason() == null ? "Released by operator" : group.getReleaseReason();
        String actor = group.getReleasedBy() == null ? "local operator" : group.getReleasedBy();
        for (DecompositionChild child : children.findByGroupOrderBySequencePositionAsc(group)) {
            if (child.getGithubIssueNumber() == null || child.getTrackedIssue() != null
                    && child.getTrackedIssue().getStatus() == IssueStatus.COMPLETED) continue;
            github.removeLabel(group.getRepo().getOwner(), group.getRepo().getName(),
                    child.getGithubIssueNumber(), "agent-ready");
        }
        ensureParentOpenStrict(group);
        postAbandonComment(group, reason, actor);
        transactions.abandon(group.getId(), reason, actor);
    }

    private void postCreatedCommentOnce(DecompositionGroup group) {
        String marker = "<!-- issuebot-decomposition-created:" + group.getId() + " -->";
        List<JsonNode> comments = safe(github.listIssueComments(group.getRepo().getOwner(),
                group.getRepo().getName(), group.getParentIssue().getIssueNumber()));
        if (comments.stream().anyMatch(c -> c.path("body").asText("").contains(marker))) return;
        String links = children.findByGroupOrderBySequencePositionAsc(group).stream()
                .map(c -> "- #" + c.getGithubIssueNumber()).reduce((a, b) -> a + "\n" + b).orElse("");
        github.addComment(group.getRepo().getOwner(), group.getRepo().getName(),
                group.getParentIssue().getIssueNumber(),
                "IssueBot created an ordered decomposition:\n\n" + links + "\n\n" + marker);
    }

    private void postAbandonComment(DecompositionGroup group, String reason, String actor) {
        String marker = "<!-- issuebot-decomposition-abandoned:" + group.getId() + " -->";
        List<JsonNode> comments = safe(github.listIssueComments(group.getRepo().getOwner(),
                group.getRepo().getName(), group.getParentIssue().getIssueNumber()));
        if (comments.stream().anyMatch(comment -> comment.path("body").asText("").contains(marker))) return;
        github.addComment(group.getRepo().getOwner(), group.getRepo().getName(),
                group.getParentIssue().getIssueNumber(),
                "IssueBot decomposition released by " + actor + ".\n\nReason: " + reason
                        + "\n\n" + marker);
    }

    private void ensureParentOpenStrict(DecompositionGroup group) {
        JsonNode parent = github.getIssue(group.getRepo().getOwner(), group.getRepo().getName(),
                group.getParentIssue().getIssueNumber());
        if ("closed".equals(parent.path("state").asText())) {
            github.reopenIssue(group.getRepo().getOwner(), group.getRepo().getName(),
                    group.getParentIssue().getIssueNumber());
            JsonNode confirmed = github.getIssue(group.getRepo().getOwner(), group.getRepo().getName(),
                    group.getParentIssue().getIssueNumber());
            if (!"open".equals(confirmed.path("state").asText())) {
                throw new IllegalStateException("GitHub did not confirm that the parent was reopened.");
            }
        }
    }

    private int legacyOrder(JsonNode node) {
        Matcher matcher = PART.matcher(node.path("title").asText(""));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }
    private static <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }
    public static String childMarker(Long groupId, int position) {
        return "<!-- issuebot-decomposition:" + groupId + ":" + position + " -->";
    }
    public static String completeMarker(Long groupId) {
        return "<!-- issuebot-decomposition-complete:" + groupId + " -->";
    }
    public record AbandonResult(boolean completed, String message) {}
}
