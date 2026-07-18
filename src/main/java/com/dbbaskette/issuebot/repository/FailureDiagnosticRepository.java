package com.dbbaskette.issuebot.repository;
import com.dbbaskette.issuebot.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface FailureDiagnosticRepository extends JpaRepository<FailureDiagnostic,Long>{ Optional<FailureDiagnostic> findFirstByIssueOrderByOccurredAtDesc(TrackedIssue issue); List<FailureDiagnostic> findByIssueOrderByOccurredAtDesc(TrackedIssue issue); }
