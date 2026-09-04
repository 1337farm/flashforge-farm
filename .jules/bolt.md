## 2024-06-11 - Math Vector Overhead in Rendering Loop
**Learning:** `Vec3d.center` method used heavily for rendering camera calculation created 3 new unused `Vec3d` instances per calculation by heavily chaining `clone()`.
**Action:** Always check the underlying implementation of vector math chaining like `.add().negate().clone()` in highly active math classes like `Vec3d`. Directly applying algebraic simplification drastically reduces garbage collection pressure.
## 2024-06-25 - Loopj Callback Optimization

To perform heavy operations inside Loopj's `AsyncHttpResponseHandler` callbacks without blocking the main thread, use `handler.setUseSynchronousMode(true)`. This configures the library to invoke the callback directly on the background worker thread where the request was executed, avoiding the need to manually spawn and manage threads within `onSuccess`. Always mark any shared fields accessed within the callback as `volatile` to ensure safe memory visibility.
## 2024-06-25 - GitHub Action CLI Checkout

When using GitHub CLI (`gh`) commands like `gh pr view` in GitHub Actions workflows, ensure `actions/checkout` is run first or explicitly use the `--repo` flag, as these commands typically require a valid git repository context to avoid "fatal: not a git repository" errors.
