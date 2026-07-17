package com.dbbaskette.issuebot.service.workflow;

/** Immutable approved planning contract shared by implementation and review. */
public record ApprovedPlanContext(long id, int versionNumber,
                                  String designSpec, String implementationPlan) {
}
