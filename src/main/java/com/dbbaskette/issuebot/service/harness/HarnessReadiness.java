package com.dbbaskette.issuebot.service.harness;

/** Diagnostic certainty is distinct from authorization: only READY permits execution. */
public enum HarnessReadiness {
    READY, UNMET, UNKNOWN;

    public boolean ready() { return this == READY; }

    /** Legacy false cannot distinguish a confirmed failure from an unavailable probe. */
    public static HarnessReadiness fromLegacy(boolean ready) { return ready ? READY : UNKNOWN; }
}
