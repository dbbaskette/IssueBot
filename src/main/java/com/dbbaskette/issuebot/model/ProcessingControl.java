package com.dbbaskette.issuebot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "processing_control")
public class ProcessingControl {
    @Column(name = "transition_generation", nullable = false)
    private long transitionGeneration;
    public long getTransitionGeneration() { return transitionGeneration; }
    public void nextTransitionGeneration() { transitionGeneration++; }

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProcessingState state = ProcessingState.RUNNING;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    protected ProcessingControl() {}

    public ProcessingControl(ProcessingState state) {
        this.state = state;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public ProcessingState getState() { return state; }
    public void setState(ProcessingState state) { this.state = state; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
