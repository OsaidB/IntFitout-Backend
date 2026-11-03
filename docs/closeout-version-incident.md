# Incident: accidental production schema change, 2026-11-02

**Status: closed. Production verified correct on 2026-11-03.**

A development startup intended for an isolated local database connected to the production
datasource instead, and Hibernate's `ddl-auto=update` added a column to production.

## What happened

Two application startups were attempted, both with `-Dspring-boot.run.profiles=test`.

**Attempt 1 — PID 6824**, started 16:26:52, reached "Started IntFitBackendApplication" at
16:27:10, killed at approximately 16:52. It connected to **production** and issued 13 DDL
statements. Twelve were idempotent `modify column` restatements that the production
application itself issues on every boot. The thirteenth was the change:

```sql
alter table closeout_drafts add column version bigint not null
```

Note the absent `DEFAULT` clause — `ddl-auto` does not add one.

**Attempt 2 — PID 4880**, 16:27:12 to 16:27:26. Also connected to production, issued the same
12 idempotent statements and **not** the `add column` (attempt 1 had already added it), then
failed on "Port 8099 was already in use" and shut down. It never reached
`ApplicationReadyEvent`.

## Root cause

`-Dspring-boot.run.profiles=test` activates the profile but **does not isolate anything**.
`application-test.properties` lives in `src/test/resources`, which is not on the classpath
that `spring-boot:run` uses. The profile activated with no properties behind it, so
`src/main/resources/application.properties` — the production datasource — stayed in force.

The pre-flight check that was performed read the *properties file* and confirmed it specified
H2. That proves nothing about what the running process connected to. The check was performed
on the wrong artefact, and before startup rather than after.

## Impact, established from the preserved logs

| | |
|---|---|
| Schema changes to production | **1** — the `add column` above |
| Other DDL | 12 idempotent `modify column` restatements |
| `INSERT` / `UPDATE` / `DELETE` | **0** |
| `SELECT` | **0** |
| Closeout drafts or measurements read or written | **none** |
| HTTP requests served | **none** |
| Scheduled reports generated | **none** |
| WhatsApp messages sent | **none** |

Neither log contains a `GenerationTarget encountered exception accepting command` warning, so
the DDL was accepted rather than merely attempted.

**No request was served.** Spring logs `Initializing Servlet 'dispatcherServlet'` at INFO on
the first HTTP request; neither log contains it. This is positive evidence, not an argument
from the absence of SQL — which matters, because `show-sql` does not log raw `JdbcTemplate`
statements.

**Nothing was sent and no report ran.** The project has no `@EnableScheduling` and no
`@Scheduled`. Four components run on startup:

- `MaterialNameFixer` — logged "Material name cleaner is disabled."
- `WhatsAppScheduledTest` — logged `event=skipped reason=disabled`
- `ReportingOutboxPump` — returns early unless `settings.anySendingReady()`; its
  `outbox_processor=enabled` line, emitted immediately after scheduling, is absent
- `DailyReportingScheduler` — returns early unless `settings.dailyReady()`; logs nothing
  either way, so this was established from configuration instead

`ReportingSettings` reads **environment variables** (`System::getenv`), not
`application.properties`. Both gates require `transportReady()`, which requires
`WHATSAPP_REPORTING_ENABLED`, `WHATSAPP_REPORTING_SEND_ENABLED`, the admin secret, the phone
number id and the access token. All 17 relevant variables were verified absent at Process,
User and Machine scope, so both gates were false and neither scheduler created an executor.

## Correction, by the operator

Backup taken before any correction:

```
C:\Users\OAB2K\Documents\IntFitout-backups\railway-after-closeout-incident-20261103-064015.sql
```

Then, executed successfully with zero warnings:

```sql
ALTER TABLE railway.closeout_drafts
    ALTER COLUMN version SET DEFAULT 0;
```

## Verified production state, 2026-11-03

```
closeout_drafts.version
  COLUMN_TYPE    bigint
  IS_NULLABLE    NO
  COLUMN_DEFAULT 0
```

This matches what `docs/sql/007-closeout-draft-version.sql` was written to produce.
**Production must not run 007's `ADD COLUMN` statement** — it would fail with "Duplicate
column name 'version'". 007 is now verification-only for production.

## Preserved evidence

Read-only copies, with SHA-256 prefixes:

| File | Bytes | SHA-256 | Contents |
|---|---|---|---|
| `backend-smoke.log` | 15,018 | `c38ced4f4968dd2a` | Attempt 1, PID 6824 |
| `b2pbt7gf8.output` | 19,111 | `851b547afe235702` | Attempt 2, PID 4880 |
| `bw6nv3usc.output` | 184 | `8d1344efef830757` | Attempt 1 launcher |
| `bqsa9wcu1.output` | 97 | `ef7c047ff1c8597f` | Ready-line waiter |

Held in the session scratchpad, which is temporary. Copy them somewhere durable if this
record needs to survive.

## Prevention

`scripts/run-local-isolated.ps1` replaces ad-hoc `spring-boot:run` invocations for local
work. It refuses to launch unless the datasource is explicitly configured and demonstrably
local, overrides the datasource on the command line rather than relying on a profile name,
and blanks the messaging and production configuration so none of it can be inherited. It also
verifies, from the running process's own log, that the connection is H2 before reporting
readiness.

Two rules follow from this incident:

1. **A profile name is not isolation.** Override `spring.datasource.*` explicitly.
2. **Verify the running process, not a file.** Check the live log for the actual driver and
   URL before issuing a single request.
