package com.dbbaskette.issuebot.service.history;

import com.dbbaskette.issuebot.repository.IssueDecisionRepository;
import org.springframework.context.annotation.Import;
import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({DecisionProducer.class, DecisionHistoryService.class, IssueDecisionRepository.class})
public @interface WithDecisionHistory {}
