package com.dbbaskette.issuebot.model;

import jakarta.persistence.*;

/** Durable transition identity, isolated from long-lived detached workflow entities. */
@Entity
@Table(name = "operator_transitions")
public class OperatorTransition {
    public enum State { ACCEPTED, IN_FLIGHT, UNKNOWN, SUCCEEDED, FAILED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private Long issueId;
    @Column(nullable = false, length = 200) private String scopeKey;
    @Column(nullable = false, length = 40) private String kind;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private State state;
    protected OperatorTransition() {}
    public OperatorTransition(Long issueId, String scopeKey, String kind, State state) {
        this.issueId = issueId; this.scopeKey = scopeKey; this.kind = kind; this.state = state;
    }
    public Long getId() { return id; }
    public Long getIssueId() { return issueId; }
    public String getScopeKey() { return scopeKey; }
    public String getKind() { return kind; }
    public State getState() { return state; }
    public void setState(State value) { state = value; }
}
