# One-time WhatsApp connectivity test

This in-memory prototype sends the existing `hello_world` template (`en_US`) only
to `WHATSAPP_TEST_RECIPIENT`. It adds no endpoint, database storage, worker report,
assignment change or recurring job. The manual endpoint still requires its
dedicated secret header. HTTP timeouts, response sanitization, disabled redirects
and single-attempt HTTP behavior are shared with the existing sender.

## Configuration

Configuration is read from environment variables when the process starts:

| Variable | Meaning |
| --- | --- |
| `WHATSAPP_SCHEDULED_TEST_ENABLED` | Defaults to false. Only `true` (case insensitive) enables registration. |
| `WHATSAPP_SCHEDULED_TEST_AT` | Future ISO-8601 date/time with an explicit offset, such as `Z` or `+03:00`. No default. |

The existing `WHATSAPP_TEST_SEND_ENABLED` must also be true. All existing sender
configuration must remain valid: `WHATSAPP_ACCESS_TOKEN`,
`WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_TEST_RECIPIENT`,
`WHATSAPP_TEST_SEND_SECRET`, and `WHATSAPP_GRAPH_API_VERSION` (defaults to `v25.0`).
The scheduled path does not make an HTTP call to the manual endpoint or expose
its secret. Do not change `WHATSAPP_WEBHOOK_VERIFY_TOKEN` or `WHATSAPP_APP_SECRET`.

## Timing and shutdown

After the application becomes ready, the component considers registration once.
Disabled, incomplete, malformed, offset-free, or past/present configuration is
skipped with a safe reason in the logs. The clock is checked again immediately
before registration, after initializing the dedicated scheduler. Startup never
calls the sender directly, and past timestamps are never caught up.

At execution, the sender gate is checked again. An early callback or a callback
more than 60 seconds late is skipped without rescheduling. The 60-second window
allows ordinary dispatch latency while preventing stale sends after a long pause.
There is at most one sending attempt in that component's lifetime, with no retry
or daily repetition. Shutdown cancels pending work and shuts down the dedicated
executor. A send already in progress cannot be assumed undone by shutdown.

The existing `Asia/Hebron` business clock is used for time checks. The configured
offset determines the absolute instant; no fixed Hebron UTC offset is assumed.
The future 19:00 `Asia/Hebron` worker-reporting feature, including retaining the
original work date for replies after midnight, is not implemented here.

## Later Railway setup and choosing a future time

1. Deploy the code with `WHATSAPP_SCHEDULED_TEST_ENABLED=false` (or leave it unset)
   and wait for that deployment to become healthy. These instructions do not
   themselves deploy anything.
2. Open the backend service's **Variables** tab in the intended Railway
   environment. Keep the current single-replica setup and existing sender/webhook
   variables. Ensure `WHATSAPP_TEST_SEND_ENABLED=true` for the planned test.
3. After the deployment is healthy, generate a fresh UTC timestamp with a buffer
   for the next configuration deployment. For example, in PowerShell:

   ```powershell
   [DateTimeOffset]::UtcNow.AddMinutes(15).ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
   ```

4. Set `WHATSAPP_SCHEDULED_TEST_AT` to that generated value and
   `WHATSAPP_SCHEDULED_TEST_ENABLED=true`. Review and deploy the staged variable
   changes. The new process must become ready before the timestamp. Choose a
   larger buffer if deployments need longer; do not reuse an old sample time.
5. Check for `whatsapp_scheduled_test event=scheduled at=...` in the new
   deployment's logs. The logged instant is UTC. If scheduling was skipped,
   inspect its static reason and choose a new future time if needed; there is no
   catch-up send.
6. At the selected time, look for `event=attempt`, followed by `event=accepted
   message_id=...` or `event=failure outcome=...`. Acceptance is not delivery;
   confirm delivery separately through the existing signed webhook. Long numeric
   runs in logged message IDs are masked. Provider error bodies and exception
   details are not logged.
7. After the test, set `WHATSAPP_SCHEDULED_TEST_ENABLED=false` and apply the
   configuration change. Keep the timestamp unset or in the past until another
   deliberate test is planned. Environment changes are not hot-reloaded.

Railway stages variable edits until they are applied/deployed; see
[Railway's variable documentation](https://docs.railway.com/variables).

## Prototype limitations

There is no durable execution record, distributed lock, or cross-instance
deduplication. **Overlapping application instances can both send the scheduled
test**, including during deployment overlap even when the configured replica
count is one. This is not durable or cross-instance exactly-once delivery.

A restart before the configured time can register it again in the new process.
A restart after the time skips it, even if no prior process sent anything. A
crash or shutdown can lose pending work. If the provider call times out, Meta may
still have accepted the request; no automatic retry is attempted. A manual test
request can send another message independently of this one-shot task.
