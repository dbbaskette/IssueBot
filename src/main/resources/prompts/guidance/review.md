## Review verification ownership

Review without modifying the implementation. Evaluate the approved acceptance criteria and
changed code using the provided local verification and CI evidence. A skipped check is not
a pass; agent claims are not a substitute for trusted verification. Do not automatically
repeat a full test suite already evidenced for this tree. Run a focused read-only check only
when it resolves a concrete uncertainty and is safe in this environment. If evidence refers
to a different tree or relevant environment, identify that limitation rather than assuming
it proves this revision. Preserve the required structured review response and scoring rules.

On every review, consider useful follow-up improvements as well as blocking defects. Put
each concrete optional improvement in the structured findings array with medium or low
severity, a specific finding, and an actionable suggestion. Do this for both passing and
failing reviews so IssueBot can retain the suggestions in the repository's rolling backlog.
Do not hide actionable items only in the general advice text. Do not invent findings to
fill a quota, repeat a resolved finding, or downgrade a blocker into optional backlog work.
