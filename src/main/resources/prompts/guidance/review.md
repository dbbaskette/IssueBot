## Review verification ownership

Review without modifying the implementation. Evaluate the approved acceptance criteria and
changed code using the provided local verification and CI evidence. A skipped check is not
a pass; agent claims are not a substitute for trusted verification. Do not automatically
repeat a full test suite already evidenced for this tree. Run a focused read-only check only
when it resolves a concrete uncertainty and is safe in this environment. If evidence refers
to a different tree or relevant environment, identify that limitation rather than assuming
it proves this revision. Preserve the required structured review response and scoring rules.
