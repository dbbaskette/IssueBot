package com.dbbaskette.issuebot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name="failure_diagnostics")
public class FailureDiagnostic {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="issue_id",nullable=false) private TrackedIssue issue;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private FailureCategory category;
 @Column(nullable=false,length=1000) private String summary;
 private String phase;
 @Column(name="occurred_at",nullable=false,updatable=false) private LocalDateTime occurredAt=LocalDateTime.now();
 @Lob @Column(name="technical_details") private String technicalDetails;
 @Column(name="suggested_action",length=1000) private String suggestedAction;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private FailureRetryability retryability;
 protected FailureDiagnostic() {}
 public FailureDiagnostic(TrackedIssue i,FailureCategory c,String s,String p,String d,String a,FailureRetryability r){issue=i;category=c;summary=s;phase=p;technicalDetails=d;suggestedAction=a;retryability=r;}
 public String getTechnicalDetails(){return technicalDetails;} public String getSummary(){return summary;} public FailureCategory getCategory(){return category;} public String getPhase(){return phase;} public String getSuggestedAction(){return suggestedAction;} public FailureRetryability getRetryability(){return retryability;} public LocalDateTime getOccurredAt(){return occurredAt;}
}
