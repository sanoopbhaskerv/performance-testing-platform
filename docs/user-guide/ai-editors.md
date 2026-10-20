# Use an AI editor, CLI or CI

The portable skill is [`skills/performance-testing/SKILL.md`](../../skills/performance-testing/SKILL.md).
It uses the public REST API and a Python 3 standard-library client, not an editor-specific plugin
or a hosted AI service. No OpenAI API key is required.

Point any editor or agent at that file and the repository’s schema/examples. For editors that
recognize project skills, link or copy the skill folder into the editor’s project skill directory,
preserving access to this repository’s relative documentation and scripts. For another editor,
add a rule telling it to read the skill before invoking performance tests. Editor-specific skill
installation conventions differ; the REST API and helper are the stable integration.

```bash
export PERF_PLATFORM_URL=http://localhost:8080
export PERF_PLATFORM_TOKEN_FILE=/absolute/private/path/team-access-token
python3 scripts/perf.py validate --spec my-test.yaml
python3 scripts/perf.py create --spec my-test.yaml
# Copy the immutable version.id from the create response.
python3 scripts/perf.py run --version VERSION_UUID --idempotency-key catalogue-2026-10-19-1
python3 scripts/perf.py wait --run RUN_UUID --timeout 600
python3 scripts/perf.py report --run RUN_UUID
python3 scripts/perf.py cancel --run RUN_UUID
```

For the local demo only, `PERF_PLATFORM_TOKEN=local-dev-key-not-a-secret` is an alternative to a
private token file. Real hosted credentials belong in a secret file or injected environment.
The CLI never prints credentials and refuses remote cleartext HTTP. Use HTTPS or a localhost
Kubernetes port-forward. A transport error never automatically replays material load.

Upload a reviewed browser journey:

```bash
python3 scripts/perf.py upload-browser --team my-team --script checkout.spec.ts --reviewed
```

The returned URI/digest go in `spec.sources`. `--reviewed` is an explicit assertion by the author;
an agent must not apply it to an unreviewed generated candidate. To revise an existing definition,
use `version --definition DEFINITION_UUID --spec revised.yaml`, then run the returned version.
Creation does not overwrite a definition with the same name.

Useful requests to an editor:

> Read the performance-testing skill. Draft a test for our registered catalogue application with
> 50 concurrent API users, a 30-second ramp, three-minute steady state, p95 below 750 ms and less
> than 1% errors. Validate the YAML and show the exact configuration before execution.

> Use this reviewed Playwright journey for the registered staging application. Run one browser
> once through the platform, wait for the report and explain the actual measurements and verdicts.

A successful HTTP response or completed orchestration is not a performance pass. Read the report,
check `synthetic=false`, completeness and each objective. Waiting returns a nonzero exit code for
failed/cancelled/rejected runs. A wait timeout returns 2 and leaves the durable run active.
