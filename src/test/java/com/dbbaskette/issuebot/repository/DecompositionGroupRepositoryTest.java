package com.dbbaskette.issuebot.repository;

import com.dbbaskette.issuebot.model.DecompositionChild;
import com.dbbaskette.issuebot.model.DecompositionGroup;
import com.dbbaskette.issuebot.model.DecompositionGroupState;
import com.dbbaskette.issuebot.model.TrackedIssue;
import com.dbbaskette.issuebot.model.WatchedRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@TestPropertySource(properties = "issuebot.github.token=test-token")
class DecompositionGroupRepositoryTest {

    @Autowired private WatchedRepoRepository repos;
    @Autowired private TrackedIssueRepository issues;
    @Autowired private DecompositionGroupRepository groups;
    @Autowired private DecompositionChildRepository children;

    @Test
    void persistsAnOwningGroupAndReturnsChildrenInSequenceOrder() {
        WatchedRepo repo = repos.save(new WatchedRepo("acme", "widgets"));
        TrackedIssue parent = issues.save(new TrackedIssue(repo, 153, "Parent"));
        DecompositionGroup group = groups.save(new DecompositionGroup(repo, parent, DecompositionGroupState.ACTIVE));

        children.save(new DecompositionChild(group, 2, "Second", "body 2", "group:2"));
        children.save(new DecompositionChild(group, 1, "First", "body 1", "group:1"));
        children.flush();

        assertThat(groups.findOwningByRepo(repo.getId())).contains(group);
        assertThat(children.findByGroupOrderBySequencePositionAsc(group))
                .extracting(DecompositionChild::getSequencePosition)
                .containsExactly(1, 2);
    }
}
