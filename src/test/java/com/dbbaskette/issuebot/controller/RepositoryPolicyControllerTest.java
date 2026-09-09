package com.dbbaskette.issuebot.controller;

import com.dbbaskette.issuebot.model.WatchedRepo;
import com.dbbaskette.issuebot.model.WorkflowPolicy;
import com.dbbaskette.issuebot.repository.WatchedRepoRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RepositoryPolicyControllerTest {
    private final WatchedRepoRepository repositories = mock(WatchedRepoRepository.class);
    private final RepositoryPolicyController controller = new RepositoryPolicyController(repositories);

    @Test
    void savesValidatedPolicyAndCanonicalStageList() {
        var repo = new WatchedRepo("owner", "repo");
        when(repositories.findById(1L)).thenReturn(Optional.of(repo));
        controller.update(1L, "STAGED", List.of("MERGE", "PLANNING", "MERGE"), new RedirectAttributesModelMap());
        assertThat(repo.getWorkflowPolicy()).isEqualTo(WorkflowPolicy.STAGED);
        assertThat(repo.getApprovalStages()).isEqualTo("PLANNING,MERGE");
        verify(repositories).save(repo);
    }

    @Test
    void invalidStageNeverModifiesRepository() {
        var flash = new RedirectAttributesModelMap();
        controller.update(1L, "STAGED", List.of("INVALID"), flash);
        verifyNoInteractions(repositories);
        assertThat(flash.getFlashAttributes()).containsKey("error");
    }
}
