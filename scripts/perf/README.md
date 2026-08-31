# Endpoint performance runner

The runner is locked to `https://dev.kbap.site`, the `kbap-infra` AWS profile in
`ap-northeast-2`, and the checked-in dev ECS and artifact-bucket names. Loopback
URLs are accepted only with `TEST_MODE=true` for the fake shell tests.

Install `python3`, `k6`, AWS CLI, `session-manager-plugin`, `jq`, and Docker.
Export the dev JWT secret, then mint the fixed member 35 access token locally:

```bash
export JWT_SECRET='<dev JWT secret>'
export ACCESS_TOKEN="$(python3 k6/mint-token.py 35 2)"
```

Start the dashboard with `scripts/perf/dashboard.sh`, or invoke
`scripts/perf/run-endpoint.sh TARGET PROFILE LOAD EXTENT`. The accepted profile
caps are smoke `1/1`, read `40/300s`, write `10/120s`, and external `10/10`.
The runner performs one smoke iteration and an explicit two-minute warm-up before
the requested measurement. Fixture offsets reserve those scheduled iterations.

Every target whose catalog `stateCapability` is not `none` requires `MYSQL_HOST`,
`MYSQL_USER`, `MYSQL_DATABASE=kbap-dev`, and `MYSQL_PWD`. The runner captures the
member-35 fixture snapshot before smoke and restores snapshot, tagged, order, or
scan state on every exit. Exact object references returned by cleanup are deleted
from the `STORAGE_BUCKET` resolved from the run's dev ECS task definition. An
inherited bucket mismatch, SQL residual, restore failure, or object deletion
failure fails the run. The database password is inherited and never enters argv.

Every run streams sanitized phase/JFR/k6 output while persisting the same output
to `console.log`. Named/AWS secrets and S3, HTTP, HTTPS, and presigned URLs are
redacted. The manifest records the approved load and extent, target request
multiplier, runner-wide and measurement iterations, HTTP/billable maxima,
provider cost cap/quota, cleanup capabilities, and cleanup outcome.
