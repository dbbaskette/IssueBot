# Review Score History and Verdict Accuracy Design

**Date:** 2026-07-18  
**Status:** Approved  
**Scope:** Issue detail review-score visibility, score history, and conformance-state accuracy

## Problem

IssueBot persists structured implementation-review evidence, including a verdict, per-dimension scores, acceptance criteria, summary, and model. Most of this evidence is currently buried as raw JSON inside iteration history, while the richer rendered score table is posted to the repository issue. Operators therefore have to leave IssueBot to understand why an implementation passed or failed and how it changed between review attempts.

The issue detail page also interprets `planConformanceAttempt == 2` as if two reviews failed. The counter records completed conformance reviews, including passing reviews, and the template hard-codes every attempt badge as `Did not conform`. A passing second review can consequently appear as `Needs guidance after review 2` even though its persisted review result is `passed: true`.

## Goals

- Make the latest implementation-review verdict and scores prominent on the IssueBot issue page.
- Show how each score changed between review attempts without requiring GitHub navigation.
- Keep the score history legible on mobile and useful for exact auditing.
- Derive all verdict labels and guidance behavior from persisted review outcomes.
- Distinguish code-conformance failures from review invocation or parsing failures.
- Reuse a single structured review representation across issue detail, inbox, and approval views.

## Non-goals

- Changing review prompts, scoring dimensions, thresholds, or model-selection behavior.
- Replacing the detailed iteration history, logs, or raw evidence.
- Recomputing or rewriting historical review results.
- Adding a client-side charting library.
- Changing the two-attempt automatic implementation-review policy.

## Primary Experience

The issue detail page displays an expanded **Implementation review** card immediately below the current issue status/workflow panel. This makes the latest verdict and its evidence part of the normal issue-reading flow rather than an item hidden in iteration history.

The card contains:

- the selected review attempt and verdict;
- the selected attempt's overall score;
- the point change from the immediately preceding scored attempt;
- the review model and attempt metadata;
- a trajectory row for every available score dimension;
- an acceptance-criteria summary and expandable checklist; and
- an attempt selector when more than two review attempts are available.

The card is expanded by default. Iteration history continues to expose full summaries and raw persisted evidence for debugging, but it is not the primary score interface.

## Visual Design: Score Trajectory

Each dimension uses a paired horizontal trajectory rail:

- a muted gray baseline represents the preceding attempt;
- a blue foreground marker represents the selected attempt;
- the selected percentage is printed as text;
- the signed percentage-point delta is shown beside it; and
- an icon and text reinforce direction so color is never the only signal.

Example:

```text
Test coverage                              94%   ↑ +49
Previous  ━━━━━━━━━━━━━━━━━━━ 45%
Current   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━● 94%
```

Positive movement uses green delta text, negative movement uses red delta text, and an unchanged value says `No change`. The score rail itself remains blue so score magnitude and pass/fail meaning are not conflated. Red is reserved for actual negative or failed states.

On mobile, dimensions render one per row. Desktop may use two columns while retaining the same source order. The layout uses existing IssueBot tokens, typography, panels, spacing, and status treatments; it does not introduce a separate visual system.

## Verdict States

The persisted `Iteration.reviewPassed` value is authoritative for an individual review attempt:

| Persisted value | Display state | Meaning |
|---|---|---|
| `true` | `Conformed` / `Passed` | The implementation review completed and accepted the implementation. |
| `false` | `Did not conform` | The implementation review completed and rejected the implementation. |
| `null` | `Review unavailable` | No valid implementation verdict exists for the attempt. |

The UI must not infer a failure from the attempt number, the existence of raw JSON, or a workflow counter. Attempt badges are derived independently so a failed first attempt and passing second attempt display different labels.

Invocation failures, malformed responses, and parsing failures use a neutral operational-error presentation. They must not be described as proof that the code failed to conform. When available, the operational failure reason and retry guidance remain visible.

## Guidance Panel Rules

The **Needs guidance after review 2** panel appears only when all of the following are true:

1. the selected approved plan is still the current plan;
2. the latest completed implementation review explicitly has `reviewPassed == false`;
3. the automatic retry allowance is exhausted;
4. the issue is in a workflow state that accepts operator guidance; and
5. the selected plan is not historical.

`planConformanceAttempt == 2` may help establish that the automatic allowance is exhausted, but it cannot establish that the latest attempt failed.

A passing second review shows the success score card and normal approval/completion actions. It never shows the action-required guidance panel.

## Overall Score

The overall score is the arithmetic mean of the score dimensions present in that review result. Missing optional dimensions are omitted and are never interpreted as zero. The display rounds only for presentation; delta calculations use the persisted numeric values before display rounding.

The calculation and dimension ordering must be shared with the inbox and approval views so an attempt has the same score everywhere in IssueBot.

## History and Comparison Behavior

The default selection is the latest review attempt with structured scores. It is compared with the immediately preceding review attempt that has structured scores.

When more than two scored attempts exist, the operator can choose an older attempt. Selecting an attempt compares it with the nearest earlier scored attempt. The selector labels each entry with its attempt number, verdict, and overall score, for example:

- `Review 3 · Passed · 95%`
- `Review 2 · Did not conform · 74%`
- `Review 1 · Review unavailable`

For the first scored attempt, no prior baseline exists. The card shows the current scores without deltas and says `First scored review`.

Missing dimensions are handled per row:

- current present, previous absent: show current with `New`;
- current absent, previous present: omit from the current score list rather than imply zero;
- neither present: do not render the row.

The initial implementation may use server-rendered links or a small existing-style selector; it does not require a client-side visualization framework.

## Acceptance Criteria

The card summarizes criteria as `N of M met`. Expanding the summary shows the complete checklist and supporting text retained from the review result.

For failed reviews, unmet criteria appear first, followed by met criteria. For passing reviews, criteria retain their persisted order. Criteria use an icon, label, and accessible status text in addition to color.

If a review contains no structured criteria, the criteria section is omitted.

## Data and Rendering Architecture

Review JSON parsing currently lives inside `ApprovalCardAssembler`. That parsing and score construction will move into a reusable service or assembler that produces a structured review-score value object. The value object contains:

- persisted verdict;
- summary;
- overall score;
- ordered optional dimension scores;
- finding count;
- model;
- acceptance criteria; and
- enough attempt identity to build history labels.

`ApprovalCardAssembler`, the inbox presentation, and issue-detail assembly consume this shared representation rather than parsing the same JSON independently.

The issue-detail controller receives a score-history view model containing the selected structured review and its comparison baseline. Thymeleaf renders those values and does not parse JSON or infer verdicts. Raw `reviewJson` remains available only in the existing diagnostic history section.

Historical or malformed records degrade gracefully: an invalid score payload does not break the issue page, and the persisted verdict can still be displayed when present.

## Accessibility and Responsive Behavior

- Every score and delta is available as text.
- Verdict and direction use text/icons as well as color.
- Rails have accessible labels describing previous value, current value, and change.
- Interactive criteria and attempt controls are keyboard operable and expose expanded/selected state.
- The card fits a narrow mobile viewport without horizontal scrolling.
- Long model names, summaries, and criterion text wrap rather than overflow.
- Motion is unnecessary; the design respects reduced-motion preferences by default.

## Verification

Automated coverage will include:

1. A failed first review followed by a passing second review renders `Conformed`, shows score improvement, and does not render `Needs guidance`.
2. A genuine second failed review renders `Did not conform` and shows the guidance panel when all workflow preconditions hold.
3. Attempt badges reflect each attempt's own `reviewPassed` value.
4. A null verdict renders the neutral `Review unavailable` state.
5. Invocation and parsing failures do not render as code-conformance failures.
6. Overall scores and deltas handle increases, decreases, unchanged values, rounding, and missing dimensions.
7. The latest attempt compares with the immediately preceding scored attempt.
8. Selecting older attempts produces the correct comparison pair.
9. Acceptance-criteria counts, failed-first ordering, and empty-state omission are correct.
10. Existing inbox and approval score rendering remains consistent after parser extraction.
11. The issue detail page remains readable at representative mobile and desktop widths.

The regression fixture for the reported defect will represent a failed review 1 and a review 2 with `passed: true` and improved scores. It must prove that the attempt counter alone cannot activate the guidance panel.

## Success Criteria

- An operator can see the latest implementation verdict, exact dimension scores, and changes without leaving IssueBot.
- A passing second review is never described as a conformance failure.
- A genuine exhausted second failure still provides the existing guidance-and-retry path.
- Issue detail, inbox, and approval views agree on score and verdict semantics.
