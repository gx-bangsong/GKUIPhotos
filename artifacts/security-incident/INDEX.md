# Security Incident Evidence Index (desensitized)

Incident: CI diagnostic data exposure in workflow artifacts and a diagnostic branch (raw logcat, dumpsys activity, full UI hierarchy, full-resolution screenshots). Repository is public -> treated as exposed; credential rotation advised.

Timeline (2026-11-24 ~ 2026-11-25, CST):
- Incident identified during PR #6 draft review; safe pause declared (adjudicator).
- PR #6 closed and discussions locked; diagnostic branch deleted (tip 4ffe1401...; now only reachable via refs/pull/6/head, support ticket pending).
- Clean base branch clean/gkuiphotos-base created at adae0c15... (independently verified).
- Ruleset branch-guard-main-clean enabled: refs/heads/main + refs/heads/clean/gkuiphotos-base; deletion and non-fast-forward blocked; bypass list empty.
- All 5 workflows remain disabled_manually; no new runs since pause.

CI artifact census (2026-11-25, 1648 total, retained pending purge):
- screenshot-class (ui-candidate-light/dark, ui-baseline-light/dark): 512
- other ui-* (ui-apk, ui-lint-evidence, ui-unit-test-results): 286
- apk-class (app-debug.apk, glimpse-debug-apk): 715
- android-lint-report: 135

Purge order (adjudicated 2026-11-25): rebuild this index -> delete artifacts (scope pending) -> GitHub support ticket for refs/pull/6/head + unreachable objects + caches -> credential rotation / PII external-copy audit.

Constraints: no reopening PR #6; no edits to closed discussions; no force-push (ruleset); new branches use sanitized names; this index contains only desensitized metadata.
