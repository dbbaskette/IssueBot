package com.dbbaskette.issuebot.service.ui;

import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.service.workflow.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Full-repository graph, deliberately independent of queue paging and text/status filters. */
@Service
public class QueueDependencyService {
    private static final List<IssueStatus> ACTIVE = List.of(IssueStatus.IN_PROGRESS,
            IssueStatus.AWAITING_APPROVAL, IssueStatus.AWAITING_PLAN_APPROVAL,
            IssueStatus.READY_TO_START, IssueStatus.AWAITING_DECOMPOSITION);
    private final WatchedRepoRepository repos;
    private final TrackedIssueRepository issues;
    private final DecompositionGroupRepository groups;
    private final DecompositionChildRepository children;
    private final DecompositionReservationService reservations;
    private final ProcessingControlService processing;
    public QueueDependencyService(WatchedRepoRepository repos, TrackedIssueRepository issues,
            DecompositionGroupRepository groups, DecompositionChildRepository children,
            DecompositionReservationService reservations, ProcessingControlService processing) {
        this.repos = repos; this.issues = issues; this.groups = groups; this.children = children;
        this.reservations = reservations; this.processing = processing;
    }
    public record Edge(Integer number, Long id, String kind, boolean satisfied) {}
    public record Node(Long id, int number, String title, IssueStatus status, List<Edge> edges,
                       String reason, boolean selectable, boolean cycle) {}
    public record Group(Long id, int parentNumber, boolean suspended) {}
    public record Graph(String repo, List<Node> nodes, List<Group> groups) {}

    @Transactional(readOnly = true)
    public List<Graph> graphs(Long repoId) {
        return repos.findAll().stream().filter(r -> repoId == null || repoId.equals(r.getId()))
                .sorted(Comparator.comparing(WatchedRepo::fullName)).map(this::graph).toList();
    }
    private Graph graph(WatchedRepo repo) {
        var all = issues.findByRepo(repo).stream().sorted(Comparator.comparingInt(TrackedIssue::getIssueNumber)).toList();
        Map<Integer, TrackedIssue> byNumber = new HashMap<>();
        all.forEach(i -> byNumber.put(i.getIssueNumber(), i));
        Map<Long, List<Edge>> edges = new LinkedHashMap<>();
        var active = all.stream().filter(i -> ACTIVE.contains(i.getStatus())).toList();
        var unfinished = groups.findByRepoIdAndStateIn(repo.getId(), DecompositionGroupRepository.UNFINISHED_STATES);
        Map<Long, TrackedIssue> previousChild = new HashMap<>();
        Set<Long> parents = new HashSet<>();
        for (var group : unfinished) {
            parents.add(group.getParentIssue().getId());
            TrackedIssue previous = null;
            for (var child : children.findByGroupOrderBySequencePositionAsc(group)) {
                var tracked = child.getTrackedIssue();
                if (tracked == null) continue;
                if (previous != null) previousChild.put(tracked.getId(), previous);
                if (tracked.getStatus() != IssueStatus.COMPLETED) previous = tracked;
            }
        }
        Map<Long, String> reasons = new HashMap<>();
        for (var issue : all) {
            List<Edge> links = new ArrayList<>();
            for (Integer number : issue.getBlockerNumbers()) {
                var dependency = byNumber.get(number);
                links.add(new Edge(number, dependency == null ? null : dependency.getId(), "Dependency",
                        dependency != null && dependency.getStatus() == IssueStatus.COMPLETED));
            }
            var previous = previousChild.get(issue.getId());
            if (previous != null) links.add(new Edge(previous.getIssueNumber(), previous.getId(), "Child order", false));
            if (issue.getStatus() != IssueStatus.COMPLETED && !parents.contains(issue.getId())) {
                var reservation = reservations.evaluate(issue);
                if (!reservation.allowed()) {
                    reasons.put(issue.getId(), reservation.reason());
                    var current = reservation.reservation().currentChild();
                    if (current != null && current.getTrackedIssue() != null
                            && !Objects.equals(current.getTrackedIssue().getId(), issue.getId()))
                        links.add(new Edge(current.getGithubIssueNumber(), current.getTrackedIssue().getId(), "Repository reservation", false));
                }
                var blocker = RepositoryDispatchGate.blocker(issue, active);
                if (blocker != null) {
                    reasons.put(issue.getId(), "Repository checkpoint held by #" + blocker.getIssueNumber());
                    links.add(new Edge(blocker.getIssueNumber(), blocker.getId(), "Active checkpoint", false));
                }
                boolean planFirst = Boolean.TRUE.equals(issue.getPlanFirstOverride())
                        || (issue.getPlanFirstOverride() == null && repo.isPlanFirst());
                if (planFirst && !issue.isManualDispatch() && processing.mode() != ProcessingState.PAUSE_AFTER_CURRENT) {
                    all.stream().filter(i -> i.getIssueNumber() < issue.getIssueNumber()
                            && (i.getStatus() == IssueStatus.PENDING || i.getStatus() == IssueStatus.QUEUED
                                || ACTIVE.contains(i.getStatus()))).findFirst().ifPresent(earlier ->
                        links.add(new Edge(earlier.getIssueNumber(), earlier.getId(), "Automatic plan order", false)));
                }
            }
            edges.put(issue.getId(), links);
        }
        List<Node> nodes = new ArrayList<>();
        for (var issue : all) {
            if (issue.getStatus() == IssueStatus.COMPLETED) continue;
            var links = edges.get(issue.getId());
            boolean blocked = links.stream().anyMatch(e -> !e.satisfied());
            boolean cycle = reaches(issue.getId(), issue.getId(), edges, new HashSet<>());
            boolean startStatus = List.of(IssueStatus.PENDING, IssueStatus.QUEUED, IssueStatus.READY_TO_START).contains(issue.getStatus());
            String reason = reasons.getOrDefault(issue.getId(), blocked ? "Waiting for the issues listed here"
                    : parents.contains(issue.getId()) ? "Group overview — work happens in its children"
                    : startStatus ? "Ready to start"
                    : "Open issue to review the next step");
            if (cycle) reason = "Circular wait — review the links below. More controls → Choose a different task can free scheduling holds.";
            nodes.add(new Node(issue.getId(), issue.getIssueNumber(), issue.getIssueTitle(), issue.getStatus(),
                    List.copyOf(links), reason, startStatus && !blocked && !parents.contains(issue.getId()), cycle));
        }
        return new Graph(repo.fullName(), nodes, unfinished.stream()
                .map(g -> new Group(g.getId(), g.getParentIssue().getIssueNumber(), g.isDispatchSuspended())).toList());
    }
    static boolean reaches(Long target, Long from, Map<Long, List<Edge>> edges, Set<Long> visited) {
        if (!visited.add(from)) return false;
        for (var edge : edges.getOrDefault(from, List.of())) {
            if (edge.satisfied() || edge.id() == null) continue;
            if (edge.id().equals(target) || reaches(target, edge.id(), edges, visited)) return true;
        }
        return false;
    }
}
