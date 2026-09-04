## 2024-08-29 - Icon-only buttons accessibility pattern
**Learning:** Found that custom programmatic Android Views in this app often omit `contentDescription` for icon-only `ImageView`s (acting as buttons) and lack visual tooltips.
**Action:** When adding accessibility to icon-only buttons in this codebase, set both `setContentDescription()` for screen readers and `TooltipCompat.setTooltipText()` for long-press tooltips.
