package com.dbbaskette.issuebot.model;

import jakarta.persistence.*;

/** Separate from mutable workflow rows so stale orchestration saves cannot erase a policy snapshot. */
@Entity
@Table(name="harness_run_permissions", uniqueConstraints=@UniqueConstraint(columnNames={"issue_id","workflow_run"}))
public class HarnessRunPermissions {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private Long issueId;
    @Column(nullable=false) private int workflowRun;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private ExecutionPermissions policy;
    public HarnessRunPermissions() {}
    public HarnessRunPermissions(Long issueId,int run,ExecutionPermissions policy) {this.issueId=issueId;this.workflowRun=run;this.policy=policy;}
    public ExecutionPermissions getPolicy() {return policy;}
}
