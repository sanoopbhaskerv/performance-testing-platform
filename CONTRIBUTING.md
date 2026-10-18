# Contributing

Contributions are welcome under the [MIT license](LICENSE). Open a focused issue or pull request
with the user problem, resulting behavior and verification. Do not include real credentials,
customer data or internal target URLs. Use the local demo or targets you are authorized to test.

Read `AGENTS.md`, the current `HANDOFF.md`, and [the extension guide](docs/development/extending.md)
before changes. Historical internal architecture names are retained for compatibility; new user
documentation should describe a portable self-hosted product.

Keep UI, REST and YAML on the same specification; preserve immutable definitions, team ownership,
digest checks, idempotency, admission and durable cancellation. Do not add an engine or a separate
workflow system without discussing its contract and dependencies. New runner images need pinned
versions and a real execution test, not only a mock manifest test.

Run the relevant checks in the README. State environmental skips explicitly. Include short steps
for a reviewer to reproduce the user-facing flow. Visual changes should build on the existing UI
and include a screenshot of the working result when useful.
