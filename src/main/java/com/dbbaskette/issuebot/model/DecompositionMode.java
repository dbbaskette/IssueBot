package com.dbbaskette.issuebot.model;

/** Whether IssueBot may split a too-large issue into sub-issues. */
public enum DecompositionMode {
    OFF,      // never decompose; escalate to needs-human instead
    PROPOSE,  // post the breakdown as a proposal; human approves from the dashboard
    AUTO      // legacy: create sub-issues immediately
}
