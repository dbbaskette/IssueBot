## Review verification ownership

Review without modifying the implementation. Evaluate the approved acceptance criteria and
changed code using the provided harness test evidence and CI results. The coding harness owns
local testing; IssueBot does not rerun commands. Agent-reported results are claims to evaluate,
not an independent PASS. Missing evidence, relevant failed tests, or inadequate coverage should
produce focused findings asking the harness to fix and test the affected behavior. Do not reject
solely because IssueBot did not run local commands. Review runs in a read-only capability profile.
Inspect the code and test evidence. When a concrete doubt requires execution, return a focused
verification request with the exact behavior or check needed and why existing evidence is insufficient;
the implementation session owns executing that check and reporting its result. Do not request a
duplicate full suite without a specific coverage or freshness reason. If evidence refers
to a different tree or relevant environment, identify that limitation rather than assuming
it proves this revision. Preserve the required structured review response and scoring rules.

On every review, consider useful follow-up improvements as well as blocking defects. Put
each concrete optional improvement in the structured findings array with medium or low
severity, a specific finding, and an actionable suggestion. Do this for both passing and
failing reviews so IssueBot can retain the suggestions in the repository's rolling backlog.
Do not hide actionable items only in the general advice text. Do not invent findings to
fill a quota, repeat a resolved finding, or downgrade a blocker into optional backlog work.
