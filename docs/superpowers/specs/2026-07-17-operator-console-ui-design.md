# Operator Console UI Design

## Purpose

Make IssueBot's current processing state, queue health, and recovery actions immediately understandable to an operator. This redesign implements the eight UI improvements approved after the July 17 UI review without changing workflow behavior or persisted data.

## Design direction

IssueBot should feel like an operator console: quiet, dense, and decisive. The existing blue/teal palette and type system remain, while state-bearing surfaces gain stronger hierarchy. The signature element is a persistent processing rail above page content that shows whether processing is active or paused and exposes the single global control.

The interface uses solid or lightly tinted operational surfaces for primary content. Frosted glass remains only for transient overlays such as modals and notifications. Color communicates state: teal for healthy/running, amber for waiting, rose for intervention, and slate for neutral information.

## Approved improvements

1. Move the global processing control from the sidebar footer into a persistent rail above every page. The rail must show active/paused state, explain the scope, preserve the current URL after pause/resume, and keep the sidebar footer focused on navigation/theme.
2. Replace the eleven equal dashboard tiles with three groups: Needs attention, Active work, and Overview. Hide zero-value attention/work items and collapse secondary workflow counts behind an expandable disclosure so the dashboard prioritizes current state.
3. Rebuild the queue filters as one compact toolbar: a wide search field, compact status and repository selects, and active-view chips for common operational filters.
4. Render statuses in plain operator language everywhere touched by this change. Examples: `IN_PROGRESS` becomes “In progress,” `AWAITING_PLAN_APPROVAL` becomes “Plan review,” and `COOLDOWN` becomes “Needs attention.”
5. Show failure/cooldown reason directly below the queue status badge, clamped to two lines, instead of requiring a hover tooltip.
6. Make the failed issue Recovery panel the dominant first card. Remove the duplicate retry modal path: the header retry action scrolls to Recovery. Collapse the implementation plan, goal, timeline, iteration history, and activity log by default on failed/cooldown issues.
7. Replace raw activity rows with a readable event title and compact context. Put the full raw event message in a “Technical details” disclosure, both on the dashboard and issue detail.
8. Reduce visual sameness by reserving glass treatment for overlays, using solid content panels, stronger table rows/status surfaces, and clearer spacing between operational sections.

## Accessibility and behavior

- All state controls remain real buttons/forms and work without JavaScript.
- HTMX navigation remains progressively enhanced with real links and form actions.
- Disclosures use native `details`/`summary` elements.
- Failure reasons remain available in full through accessible text/disclosures even when visually clamped.
- The processing rail is responsive and wraps cleanly on narrow screens.
- Existing dark mode tokens remain supported.

## Testing

Render tests will assert the persistent rail, grouped dashboard hierarchy, compact filter structure, human status copy, inline failure reason, recovery anchor, collapsed failed-page sections, and technical-detail disclosures. Existing controller and template tests must continue to pass. The running application will be checked at desktop and mobile widths on the dashboard, issue queue, and a failed issue.
