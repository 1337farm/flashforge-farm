# Agent working agreement

When a task is finished (code complete, verified locally):

1. Commit every code fix on a NEW branch (never push straight to main, never reuse a merged branch).
2. Push the branch to origin.
3. ALWAYS open a PR against `main` with `gh pr create --base main` and return the PR URL — no exceptions, every time.
4. Do not leave finished work uncommitted/unpushed without telling the user.

After opening a PR, babysit it through merge:

1. Monitor CI: `gh pr checks <PR>` / `gh pr view <PR>`.
2. Fix failures: failing checks, review comments, merge conflicts (rebase on `main`, resolve, force-push with lease).
3. Re-request review / re-run checks until green.
4. Merge when green and approved (or per repo policy); confirm `gh pr view` shows MERGED.
5. Report the final state (merged URL or blockers) — never abandon an open PR silently.
