package com.dbbaskette.issuebot.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "harness_input_requests", uniqueConstraints = @UniqueConstraint(columnNames = {"transport_id", "native_id"}))
public class HarnessInputRequest {
    public enum State { WAITING, ANSWERED, DELIVERED, DISCONNECTED, WITHDRAWN }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long issueId;
    @Column(nullable = false) private int workflowRun;
    @Column(nullable = false) private int iterationNum;
    @Column(nullable = false, length = 40) private String harness;
    @Column(nullable = false, length = 80) private String transportId;
    @Column(nullable = false, length = 160) private String nativeId;
    @Column(length = 160) private String sessionId;
    @Column(nullable = false, length = 80) private String method;
    @Column(columnDefinition = "TEXT", nullable = false) private String payload;
    @Column(columnDefinition = "TEXT") private String response;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private State state = State.WAITING;
    @Column(nullable = false) private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime answeredAt;
    public HarnessInputRequest() {}
    public HarnessInputRequest(TrackedIssue issue, String harness, String transport, String nativeId, String session, String method, String payload) {
        this.issueId=issue.getId(); this.workflowRun=issue.getWorkflowRun(); this.iterationNum=issue.getCurrentIteration();
        this.harness=harness; this.transportId=transport; this.nativeId=nativeId; this.sessionId=session; this.method=method; this.payload=payload;
    }
    public Long getId(){return id;} public Long getIssueId(){return issueId;}
    public int getWorkflowRun(){return workflowRun;} public int getIterationNum(){return iterationNum;}
    public String getHarness(){return harness;} public String getTransportId(){return transportId;}
    public String getNativeId(){return nativeId;} public String getSessionId(){return sessionId;}
    public String getMethod(){return method;} public String getPayload(){return payload;}
    public String getResponse(){return response;} public State getState(){return state;}
    public LocalDateTime getCreatedAt(){return createdAt;} public LocalDateTime getAnsweredAt(){return answeredAt;}
    public void answer(String value){response=value;state=State.ANSWERED;answeredAt=LocalDateTime.now();}
    public void setState(State value){state=value;}
}
