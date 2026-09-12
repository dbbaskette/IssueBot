## Frontend design guidance — apply when UI is affected

Consult the existing design system and nearby screens before designing or changing UI.
Keep spacing, typography, control sizes, cards, status colors, and notifications consistent.
Give each state a clear primary next action; remove redundant controls and label consequences,
especially approval, start, retry, pause, and destructive actions. Pair color with readable text.

Preserve drafts, focus, scroll, selection, and expanded details across background refreshes
while updating authoritative status and available actions. Use stable item identities, not row positions.
Provide useful loading, empty, error, and success states; avoid repeated notifications for unchanged state.
Use semantic controls, keyboard access, visible focus, accessible labels, and responsive layouts.
Check changed flows at desktop and narrow/mobile sizes, including refresh and failure paths,
when a browser is available. Report visual checks not performed rather than claiming them.
Backend-only work does not require a UI redesign or browser pass.
