package com.dbbaskette.issuebot.service.harness;

/** The native request cannot safely be replayed; keep its durable run waiting for recovery. */
public class HarnessInputInterruptedException extends RuntimeException {
    public HarnessInputInterruptedException(String message) { super(message); }
}
