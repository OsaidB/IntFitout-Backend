# Persistent WhatsApp reporting pilot

This batch ends at **submitted, awaiting administrator review**. It has no approval-to-assignment operation, hours, overtime/payroll writes, recurring 19:00 dispatcher, or frontend credential integration. Future daily requests remain planned for 19:00 `Asia/Hebron`, asking about that same day. This batch manually initiates one real reporting request for one explicitly configured worker and recipient; it is not another hello-world endpoint.

## Data and boundaries

Worker entities/DTOs remain unchanged. Reporting setup stays JDBC-only. Master worksites now expose an optional nickname through the existing entity/DTO/MapStruct mapping and ordinary APIs. Existing approved names, IDs and contact data are preserved:

- `team_members.whatsapp_reporting_eligible`: default false. Explicit participation/active reporting eligibility, **not an inferred employment status**. Set false when a worker must stop participating. No contact parsing or automatic opt-in.
- `team_members.whatsapp_number`: nullable, unique international digits, 7–15 digits, no leading zero. The protected setup API accepts either these digits or a leading `+`, strips only that `+`, and rejects spaces, punctuation, local/`00` formats. `970…` and `972…` remain different numbers. Eligibility requires a number; free-text `contact` is never read or changed.
- `master_worksites.whatsapp_reporting_selectable`: retained unchanged for compatibility with old clients and the protected setup operation. It no longer restricts reporting choices and is not an activity/disablement flag.
- `master_worksites.nickname`: optional, trimmed, maximum 80 UTF-16 characters without controls/format characters, no uniqueness requirement. Blank uses the approved name. Full updates with omitted/null nickname preserve the stored value; an empty string clears it. The narrow `PUT /api/master-worksites/{id}/nickname` body `{"nickname":"..."}` changes only that field; `{"nickname":""}` clears it. This uses the existing master-worksite API permissions, never reporting administration credentials in the frontend. The group details editor offers Save Nickname; existing creation APIs accept nickname as an optional DTO field.

Five tables hold reporting state:

| Table | Purpose |
| --- | --- |
| `whatsapp_report_requests` | Unique worker/work-date request, recipient and receiving phone-ID snapshots, conversation step/revision, current site, last inbound time, outcome and immutable submission time |
| `whatsapp_report_sites` | Stable per-request entry ID; nullable original master ID; shown label snapshot; original Other description; separate administrator resolution metadata |
| `whatsapp_report_coworkers` | Unique request/entry/mentioned-worker and name snapshot, including unresolved Other entries; supporting information only |
| `whatsapp_report_inbox` | Deduplicated normalized message/status IDs, context, issued action, receipt/message times and disposition; bounded accepted Other text only, no complete payload/contacts array |
| `whatsapp_report_outbound` | Generated template/prompt payload, unique request/revision, claim/result state, Meta ID and observed delivery state |

The outbox necessarily stores the pilot recipient and worker-facing labels in its generated payload, but no credentials. Administration responses omit outbox payloads and recipient numbers. Report inspection includes normalized inbox actions/IDs, selected site/coworker snapshots and outbound state. No personal records were added during implementation.

There are reporting-internal foreign keys, but no new foreign keys to legacy workers/sites. This preserves existing delete behavior while retaining report snapshots/logical IDs. Deleting a worker/site does **not** delete an existing report, inbox or outbox. A deleted or ineligible pilot worker cannot continue or dispatch. This is not a comprehensive data-deletion implementation or retention policy. Retention/administrative deletion tooling remains to be designed.

## Worker conversation

1. Send `daily_work_report_ar` with one body text parameter such as `الخميس، 22/10/2026`. Quick-reply components have index `0` = `تسجيل مواقع العمل`, index `1` = `لم أعمل`, and carry server-generated request/revision action payloads.
2. The first reply opens site selection or a **did not work** confirmation.
3. Site selection opens optional coworkers for that site. Tapping a coworker toggles their mention; **متابعة بدون أسماء أخرى** continues. Then select another site or **انتهيت من المواقع**.
4. A separate confirmation explicitly submits the report for review. The final Arabic acknowledgement says it awaits review and no assignments/hours were changed.

Lists have at most 10 rows: up to 6 known-site choices plus previous/next, Other and finish; coworker lists retain up to 7 names plus navigation/continue. Names/site labels only, stripped of log/display controls, phone-like numbers redacted, labels truncated for Meta limits. Only explicitly eligible coworkers appear; a coworker needs their own explicit profile setup, and is never sent a request merely because someone mentions them. Pilot allowlisting still blocks sending to everyone except the configured worker/recipient. No wage, payroll, contact, or role fields are queried for choices. Names may be ambiguous or truncated; choose the eligible roster/site labels carefully before the pilot.

Draft entry selection is additive (up to 50 listed/Other entries); coworkers can be toggled while at that site's step. This batch does not provide entry removal, revisiting completed coworker steps, arbitrary free-text reporting, or editing a submitted report. Bounded text is accepted only during the persisted Other-capture step. Back from confirmation allows adding more sites; a did-not-work confirmation can go back to the initial choices. Requests are unique per worker/date: repeated initiation returns the existing request and never resends the template or resets a submission.

The original `work_date` is never recomputed from a reply's date. Reply context is matched to the specific issued outgoing prompt and revision, including after midnight or when multiple days have requests. Template quick replies (`type=button`), interactive `button_reply`, `list_reply`, and correlated Other text are supported; external button/list titles are never trusted as selections. Unrelated events retain HTTP 200 behavior. A wrong business phone ID or sender is ignored by reporting, without creating reporting data.

## Seven-day activity and Other entries (25 October 2026)

For immutable report work date **D**, activity means at least one **saved assignment dated D−6 through D inclusive**. Backend SQL binds `LocalDate` values; today is derived from `Asia/Hebron`, without UTC ISO truncation. D−7 and future assignments do not count. No assignment on D itself is required. Invoice activity, child-worksite state, Saturday–Friday weeks and manual reporting flags are not substitutes.

Opening request initiation and opening-template dispatch require both recent worker activity and all existing explicit participation/number/pilot gates. Idempotent inspection of an already-created request does not resend it. Prompt dispatch and inbound replies use the original request, not a fresh activity check against today; explicit worker disablement still blocks them. If an opening intent loses its recent activity, it stays unsent/PENDING and needs operator inspection/abandonment; do not create artificial assignments to bypass the gate.

Known choices are existing master worksites with assignment activity in the same D−6/D window, regardless of the legacy selectable flag. The worker sees nickname when nonblank, otherwise approved name, sanitized/truncated with a permanent `#<master-id>` suffix to distinguish duplicate/truncated labels. The exact issued row title is saved when selected, even if a nickname changes before the reply. Inspection retains original master ID and current business approved name (null if the master was deleted), separately from the snapshot. Coworker choices retain the existing explicit participation policy; selecting a coworker never sends them a request or writes an assignment.

**Other** appears as `موقع آخر` / `غير موجود بالقائمة`, even with zero known choices; initiation no longer requires selectable sites. It asks `ما اسم الموقع الذي عملت فيه؟ اكتب الاسم أو وصفًا يساعدنا على معرفته.` Accept 2–300 Unicode code points, trimmed only, with at least one letter/digit and no control/format/line-separator characters. Invalid input is deduplicated without storing its raw body and reissues the instructions with cancellation. Cancel returns to site selection. A valid description creates its own stable entry, then offers optional coworkers and returns to site selection. Other-only, mixed, and multiple-Other reports can be submitted. The combined limit remains 50 entries. Confirmation enumerates every entry with bounded labels to fit message limits; inspection retains complete original descriptions.

Text correlation is fail-closed:

- Signature verification precedes parsing. Business phone ID, sender and explicit worker/pilot setup must match.
- A supplied context must identify the current persisted Other prompt. A conflicting/malformed context never falls back to another report.
- Contextless text is accepted only when exactly one persisted capture exists for this pilot/sender and its current prompt is ACCEPTED. Multiple captures are recorded as AMBIGUOUS without advancing or retaining the body; the worker must use WhatsApp Reply on the intended prompt. No report is selected by “latest for this phone.”
- A contextual reply to the sole SENDING/UNCERTAIN capture may be persisted DEFERRED; it only applies after the evidenced accepted Meta ID exactly matches that context. Contextless text never advances an uncertain prompt.
- The inbound timestamp must not precede the issued capture prompt (Meta seconds precision). Original work dates survive midnight. Duplicate/stale/concurrent events cannot create an extra entry. Inbox, entry/coworker, state and next intent commit together before acknowledgement. Accepted original text is stored for review, never logged.

### Protected administrator resolution (Postman)

After submission, use the existing dedicated header `X-WhatsApp-Reporting-Admin-Secret: {{reporting_admin_secret}}` and `Content-Type: application/json`:

`POST /api/whatsapp/reporting/admin/requests/<request-uuid>/entries/<entryId>/resolve`

Link an existing master, including inactive/out-of-window masters:

```json
{"masterWorksiteId":123}
```

Or explicitly create through the existing MasterWorksiteService name uniqueness, city/area and nickname validation, then link in the same JPA/JDBC transaction:

```json
{"create":{"approvedName":"Operator-confirmed business name","nickname":"Optional nickname","notes":"Optional reviewed notes"}}
```

Creation also supports the ordinary optional `city`, `area`, `subArea`, `locationDetails`, `projectSizeTier`, and `estimatedAreaM2` DTO fields. Supply exactly one operation, no IDs inside create and no query overrides. Repeating the same body after success returns the existing resolution; a different resolution returns 409. Preserve the exact body for retries. Missing/wrong/duplicate credentials fail closed. Drafts and known entries cannot be resolved with this operation. Nothing auto-matches descriptions or creates masters from webhook text. Resolution records linked master ID, business name, key and time separately; the original description, label, coworker associations, work date, submission and AWAITING_REVIEW status remain intact. No assignment/hour/overtime/payroll writes occur.

## Transactions, acknowledgement, sending and recovery

The existing controller still validates `X-Hub-Signature-256` over the original bytes **before JSON parsing or reporting processing**. Matching pilot events are committed before HTTP 200. A persistence failure returns sanitized 503; a batched redelivery safely skips events already committed. Unrelated webhook logging retains its existing sanitization.

To pause sending while retaining incoming reporting answers, leave `WHATSAPP_REPORTING_ENABLED=true` and the receiving pilot configuration intact, and set only `WHATSAPP_REPORTING_SEND_ENABLED=false`. Fully disabling reporting or removing its receiving configuration bypasses reporting processing and retains the legacy webhook acknowledgement behavior; do not use that as a pause mechanism for an ongoing conversation.

Transactions acquire the configured pilot worker row lock, serialize request changes, deduplicate by normalized event key, validate the current request/action/step/context, and commit the inbox disposition, site/mention changes, request revision, and next outgoing intent together. Unique keys also protect worker/date, site/coworker selections and request/revision intents. Distinct concurrent answers to one step can apply at most one transition; late/duplicate actions are recorded/ignored. Submitted answer data cannot be edited through these services/routes. Delivery status/inbox/outbox metadata can still change after submission.

When sending is fully enabled, a dedicated outbox processor checks for one existing `PENDING` intent every two seconds (first pass five seconds after readiness). **It never creates reporting requests.** It uses the same bounded HTTP transport as the existing hello-world sender: 3-second connect/pool acquisition, 10-second response/socket limits, redirects disabled, automatic retries disabled. There is also a protected single-intent dispatch operation for inspection/recovery. No sending or scheduler is started by unit tests.

Each attempt:

1. Rechecks configuration, explicit pilot eligibility/number and request sender/recipient snapshots.
2. Commits `PENDING → SENDING` before calling Meta, outside any database transaction.
3. Makes one HTTP attempt, then commits `ACCEPTED` plus validated Meta ID, `REJECTED` (non-2xx), or `UNCERTAIN` (timeout/transport error/unrecognizable success).

Acceptance is not delivery. Signed statuses provide delivery evidence; out-of-order statuses do not regress `READ`/`DELIVERED`. Statuses received before the acceptance result are retained and consulted when its Meta ID becomes known. Unknown recipient/business events cannot update this reporting conversation.

A reply arriving while its prompt is `SENDING`/`UNCERTAIN` is saved `DEFERRED` if its sender, request, current revision and issued action match. It cannot advance until the actual Meta ID is available and matches its context. A committed acceptance or evidence-based accepted reconciliation applies matching deferred actions transactionally; a wrong context is ignored. Duplicate replay remains harmless. The original inbound timestamp is retained, so recovery cannot create a fresh 24-hour messaging window.

`PENDING` intents can resume after process restart only while their gates/window remain valid. `SENDING`, `UNCERTAIN`, `REJECTED`, `ACCEPTED` and `CANCELLED` are **never automatically retried**. Expired same-day templates, superseded prompts, and prompts outside the 24-hour inbound-message window are cancelled rather than sent as a catch-up. There is no automatic template reopening or resend. A request whose prompt failed may therefore remain incomplete for that date; it cannot be reset by repeating initiation.

If Meta accepted a send and the process stopped before saving its response, the row remains `SENDING`. Do not change it to `PENDING` or issue another send. Stop/pause the outbox sender and let any live HTTP attempt finish before reconciling. Inspect the request, normalized incoming context/status evidence, and Meta's own evidence. Only if the actual message ID is established, use **resolve accepted** below to bind it; this can release matching deferred replies without resending. If acceptance cannot be established, leave it blocked or **abandon** it. Abandonment does not retract a message that may already have arrived. There is no cross-system atomic transaction with Meta and no claim of exactly-once delivery. Database locking/constraints are designed to avoid concurrent claims, but production multi-instance behavior has not been database-tested; keep the pilot on one Railway replica.

Shutdown stops the outbox executor and interrupts pending/in-flight work. Interrupted sends are uncertain or remain `SENDING`; shutdown never resets an attempt to pending. New reporting service/processor logs contain internal outbound UUIDs and fixed outcome codes, not payloads, names, phone numbers, credentials, headers, exception text or provider response bodies.

## Consolidated deployment and setup checklist

The operator reports that **001 is already applied**, the persistent pilot is deployed/enabled, and `daily_work_report_ar` / `ar` is APPROVED with one weekday/date body parameter and quick replies 0 `تسجيل مواقع العمل` / 1 `لم أعمل`. These facts were supplied by the operator; no production/Meta access was performed here. **Do not rerun or rewrite 001.** No real pilot record/number/sender is embedded in source or synthetic fixtures.

1. **Prepare one coordinated maintenance/deployment batch.** Review the actual legacy schema, backup/change window and new `docs/sql/002-whatsapp-reporting-other-and-nicknames.sql`. Preserve existing Railway webhook, access-token, real sender, pilot and template values. No new environment variable is introduced. Stop reporting/application instances during manual DDL; pausing only the outbox does not prevent old code from processing inbound events against a partially changed schema. Keep `WHATSAPP_REPORTING_SEND_ENABLED=false` through maintenance and validation. No missed-day catch-up or recurring requests will run.
2. **Apply only 002, manually, once, before the new code.** The script adds nickname, seeds existing `entry_id` from old `site_id`, keeps current_site values (now entry references) and old payloads valid, moves coworker FKs to entries, adds nullable Other/resolution fields and accepted inbox text, expands the step CHECK and adds assignment activity indexes. Existing requests, snapshots, mentions, inbox/outbox history and selectable flags remain. DDL implicitly commits: inspect/completely repair partial execution, never blindly rerun. No production SQL was executed here.
3. **Deploy backend and nickname UI together after schema is complete.** No migration runner or startup SQL is added. The pre-existing local `spring.jpa.hibernate.ddl-auto=update` remains untouched. Nickname is now JPA mapped, so that setting could create its column; it cannot safely perform the full reporting migration. Do not rely on it. For deliberate SQL-controlled deployment, review Railway `SPRING_JPA_HIBERNATE_DDL_AUTO=none` with the rest of the existing schema before boot. Keep one Railway replica and existing daily-request scheduling disabled/nonexistent.
4. **Verify configuration and authorization with sending paused.** Preserve `WHATSAPP_REPORTING_ENABLED`, `WHATSAPP_REPORTING_SEND_ENABLED`, `WHATSAPP_REPORTING_ADMIN_SECRET`, `WHATSAPP_REPORTING_PILOT_WORKER_ID`, `WHATSAPP_REPORTING_PILOT_RECIPIENT`, `WHATSAPP_REPORTING_PHONE_NUMBER_ID`, `WHATSAPP_REPORTING_TEMPLATE_LANGUAGE`, `WHATSAPP_REPORTING_TEMPLATE_CONFIRMED`, `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_GRAPH_API_VERSION`, `WHATSAPP_WEBHOOK_VERIFY_TOKEN` and `WHATSAPP_APP_SECRET`. Keep secrets private in Railway/Postman, never frontend/source/shared exports. Preserve connectivity test variables separately; do not enable their senders for this batch. The reporting sender never falls back to the test phone-number ID. Use authenticated `GET /api/whatsapp/reporting/admin/status` and inspect old pending/interrupted intents before restoring sending; uncertain/SENDING messages must never be reset/retried blindly.
5. **Check truthful eligibility before live testing.** Configured pilot worker **10041** may have no saved assignment history. The operator must inspect genuine saved assignments for the intended Asia/Hebron work date: at least one between D−6 and D is required. With none, expect 409 from initiation/opening dispatch and zero sends. Do not fabricate history or attach the pilot number to another worker. Wait for genuine history or deliberately configure a genuinely eligible worker/recipient via the existing explicit setup and allowlist process. This batch adds no bypass. Existing participation and normalized number must still match the one configured pilot recipient; +970/+972 are never interchangeable.
6. **Prepare labels using ordinary worksite administration.** Optional nickname is edited in Group Details or via the narrow nickname API above. No need to enable the old three-site flag: activity determines known choices. The retained protected `PUT /api/whatsapp/reporting/admin/worksites/<id>` body `{"selectable":true}` remains compatible but no longer changes WhatsApp choices. Existing `PUT /api/whatsapp/reporting/admin/workers/<id>` body `{"eligible":true,"whatsappNumber":"+<explicit-digits>"}` still controls participation; false/null disables. Coworker mentions remain opt-in supporting data.
7. **Restore pilot sending deliberately, then initiate only today's report.** After readiness, restore reviewed `WHATSAPP_REPORTING_ENABLED=true` / `WHATSAPP_REPORTING_SEND_ENABLED=true` with the same one-worker/recipient and real sender. Postman `POST /api/whatsapp/reporting/admin/requests` body `{"workDate":"<today in Asia/Hebron, yyyy-MM-dd>"}`; no worker/recipient/template/URL overrides. Existing worker/date requests are returned, never reset/resubmitted/resent. Inspect via `GET /api/whatsapp/reporting/admin/requests?limit=20` and `GET /api/whatsapp/reporting/admin/requests/<uuid>`. Test mixed/Other-only/multiple Other entries, invalid/cancelled text, optional coworkers, explicit Reply context, duplicate taps and replies after midnight. All reports end AWAITING_REVIEW, without financial changes.
8. **Resolve submitted Other entries using the protected API above.** Test existing inactive master linking, new explicit creation, repeat/conflict behavior, original text retention and unchanged approval state. Nicknames do not auto-match entries. Use the returned entryId, never a fabricated master ID. A full review dashboard and assignment approval are not part of this batch.
9. **Retain existing conservative outbound recovery.** `POST /api/whatsapp/reporting/admin/outbound/<uuid>/dispatch` accepts no body/query and only claims PENDING once. `POST /api/whatsapp/reporting/admin/outbound/<uuid>/resolve` accepts `{"decision":"accepted","metaId":"<evidenced wamid...>"}` or `{"decision":"abandon"}`. Pause dispatch and finish any in-flight attempt before reconciliation. Acceptance may release matching deferred button/list/text replies without resending. Sending can remain false during recovery. Never fabricate IDs, reset attempted messages to PENDING or reinterpret ambiguous text as the latest report.

All reporting admin operations above use the dedicated header. Standard master-worksite nickname/create/update APIs retain their existing application security behavior and receive no reporting admin secret.

## Verification and remaining limitations

Current batch, **25 October 2026**: **40 real MySQL integration cases passed** on a newly created guarded official MySQL **9.7.2** container (zero failures/errors/skips), including all previous 28 cases. **261 distinct isolated unit/MVC cases passed across targeted runs**, including 10 Other-flow cases, 4 nickname cases and 21 protected-admin cases. The 76 cases directly covering changed conversation/nickname/admin/pump/logging behavior were verified in focused runs; unchanged connectivity/transport/settings cases passed earlier in this batch and were not repeatedly rerun. The final ten Other cases also verify malformed supplied context, list/body bounds and the 20-character cancel-button bound. Backend/test compilation passed.

Frontend: **3 mocked-Axios Jest cases passed**, and Babel transformed both modified JSX/service files offline. No Expo/full application was started. Device/web visual interaction remains unverified.

The expanded real-database suite applies the actual 001 then 002 scripts to representative synthetic legacy data, and separately populates in-progress/submitted 001 reports before applying 002. It verifies old payload continuity, coworker remapping, original snapshots, D−6/D/D−7/future boundaries, nickname fallback/omission/snapshot, Other text duplicates/concurrent actions/SQL rollback/redelivery, original dates after service/ORM recreation, uncertain-send reconciliation, and submitted resolution. New-master creation uses actual MasterWorksiteService/JPA repositories in the same JpaTransactionManager as JDBC linking; forced link failure rolls both back and concurrent identical retries create only one master. The immutable owner lookup precedes the resolution transaction so waiting on the worker lock cannot pin an obsolete REPEATABLE READ snapshot. All assignment/overtime/payroll sentinels remain unchanged. Invalid/ambiguous descriptions are not retained, and webhook logs never contain raw text.

An initial focused run encountered Mockito's sandbox-denied Java attach pipe; its owned stuck test JVM/helper were stopped and the focused cases passed with local attach access. Early fixture gaps (activity/city catalog mocks) and concurrent-resolution transaction ordering were corrected; failed container runs also performed ownership-checked cleanup. No production data, full boot, real Meta messages, deployment or application properties were used by tests. Both 001 and the pre-existing application.properties bytes are unchanged (properties SHA256 `8E2B078835F168C9ECF15BBC9F40F40B5CB4EEC17A6885102D30833AA37772AF`). All created disposable containers, volumes and networks were removed.

Historical version-validation result, **23 October 2026**: all **28 existing real MySQL reporting integration cases passed, zero failures/errors/skips**, against a newly created disposable local container using the official **`mysql:9.7.2`** image. Both the runner and the integration class independently verified `SELECT VERSION() = 9.7.2`. Production/test sources compiled successfully. Only `ReportingMySqlIntegrationTest` ran; the 245 regression cases were **not rerun**. No production-code or SQL compatibility issue was found, and neither was changed. That earlier batch changed only the runner's image/suite selection/version guard, the integration version assertion, and this guide.

Actual database: **MySQL Community Server 9.7.2**, Docker Desktop Linux **amd64**, `REPEATABLE-READ`, the existing Connector/J **8.3.0**, Hibernate **6.5.3.Final** and Spring's actual **JpaTransactionManager**. Pinned official image: `mysql:9.7.2@sha256:e2fa358a74ac81148e7e27d9433e4c0ab62133de8ea4428b4f39a51a4ba7d07f`. The version matches the operator's reported production check; no production metadata was queried by this validation. No new dependencies were needed.

Historical result, **22 October 2026**: **273 tests passed, zero failures/errors/skips** on disposable MySQL **8.0.46**: 28 real database cases and 245 isolated/mock regressions (94 reporting and 151 existing connectivity cases). The old pinned image was `mysql:8.0@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b`; the runner now targets 9.7.2 only.

The real-database tests bootstrap a small explicit set of actual worker/master-worksite/child/contact ORM mappings with explicit test datasource/JPA settings, schema generation off, direct reporting services/store and standalone MVC. They do not load a Spring Boot application/context, component scan, application properties, startup hooks, recurring jobs or real HTTP client. They use UTC JDBC sessions and a controlled `Asia/Hebron` business clock. Every test gets a representative legacy schema from `src/test/resources/reporting/mysql-legacy-baseline.sql`, with only synthetic workers/sites and assignment/overtime/payroll sentinels. The override columns match the existing `DECIMAL(65,30)` mapping. Reporting operations must leave all financial sentinel rows unchanged.

| Real MySQL coverage | Verified behavior |
| --- | --- |
| Additive migration | 001/002 apply only in the guarded test DB; legacy worker/site/contact/wage rows survive; existing configured/in-progress/submitted 001 reports, mentions, snapshots and issued payloads survive 002 |
| Constraints/concurrency | Concurrent unique worker/date, normalized phone, event key and outgoing revision; unique site, coworker and Meta IDs; real CHECK enforcement (vendor error 3819) |
| Atomic inbound transactions | Forced SQL failure after state/site/outbox/inbox changes rolls everything back; signed webhook returns 503; redelivery commits once and duplicates acknowledge safely |
| Concurrent actions | Six simultaneous template/list/button/coworker/text deliveries cannot apply one revision twice or create duplicate selections/requests |
| Resolution/nickname/activity | Real JPA master creation plus JDBC entry linking roll back atomically on injected SQL failure; concurrent identical retries create one master; existing out-of-window master linking, conflict/authorization, original answer preservation, nickname fallback/snapshot and D−6/D/D−7/future boundaries pass |
| Persistence/recreation | New ORM factory/store/services read committed rows and resolve an older request's reply to its original work date despite a newer request |
| Submission | Multi-site and did-not-work reports require final confirmation, remain immutable, and leave existing assignments/overtime/payroll unchanged |
| Outbound recovery | Claim is committed/observable before mocked HTTP; concurrent claims send once; aborted real JDBC connection after mocked Meta acceptance leaves `SENDING`; recreation never blindly retries; deferred replies resolve only after evidenced context binding |
| Pausing/failures | Uncertain outcomes remain blocked; protected abandonment never sends; accepted reconciliation can leave subsequent intents pending while sending is paused |
| Isolation/authentication | Signature validation precedes DB processing; wrong receiving phone ID/sender/context/action and request overrides cannot advance/send; every admin operation rejects missing/wrong/duplicate/query credentials |
| Disabled/absent schema | Existing real JPA worker HTTP read, GET/POST webhook and mocked hello-world endpoint work with no reporting tables/columns; disabled reporting/pump perform zero reporting queries |

Historical compatibility validation found no production-code/schema defect in its covered cases. During the earlier 8.0 validation, the runner's Windows CLI readiness/port handling was corrected. A direct CHECK test initially expected Spring to classify the error as `DataIntegrityViolationException`; Connector/J/MySQL returns HY000/3819 and Spring leaves it uncategorized, so the test verifies the actual vendor error and rejected data. That assertion also passed unchanged on 9.7.2. The administration controller already sanitizes all `DataAccessException` subclasses. No auth/pilot bypass was found in the covered source/MVC/database checks. This is not a general security certification of unrelated existing endpoints.

To reproduce on **local Windows Docker Desktop only**, with Docker's Linux engine running, Java 21, Maven and cached project dependencies:

```powershell
.\scripts\test-reporting-mysql.ps1 -MavenExecutable 'C:\Program Files\Maven\apache-maven-3.9.9\bin\mvn.cmd'
```

The runner pins the official `mysql:9.7.2` image digest listed above and requires `SELECT VERSION()` to return exactly `9.7.2` before Maven starts; the integration class independently checks that version before fixture DDL. It creates a uniquely named/labeled MySQL container and dedicated Docker bridge, a fresh memory-backed database, random generated credentials, and a randomly assigned **127.0.0.1-only** port. No existing container/volume joins it. A per-run guard must match before any fixture DDL. The test URL is constructed solely from that test port, hardcodes IPv4 loopback and a test-only schema, and has no application-datasource fallback. `INTFITOUT_MYSQL_TEST_*` process variables are supplied/restored only by this runner; do not populate them with another database. The integration class is skipped by ordinary Maven runs unless explicitly enabled, and enabled runs fail closed if their guard/configuration is missing. Docker unavailable means stop, never substitute H2 or connect elsewhere. All Meta traffic is mocked; Maven runs offline and selects only `ReportingMySqlIntegrationTest` (now 40 cases), without the 245 regression cases or the full repository test suite.

The no-known-sites database case injects an empty choice projection while using real MySQL for conversation writes; an active worker normally implies at least one existing site under the fixture's legacy FKs. City/area catalog dependencies are mocked; master uniqueness, nickname persistence, actual JPA creation and shared JPA/JDBC rollback use the real local database. Fake-store unit tests are not database/restart proof.

On both failure and success, cleanup verifies per-run ownership labels before removing only that container/anonymous volumes and network, restores environment values and verifies `application.properties`' hash. All containers/networks created during the observed runs were removed. The pinned image remains as a normal local Docker image cache. If the runner process itself is force-killed before `finally`, inspect the printed run name/label and clean up only its owned resources manually; never use a global prune. Synthetic test output stays in ignored `target/reporting-mysql-validation.log` and Surefire reports; generated credentials are not written to source/config files or printed.

**Remaining unverified:** the real legacy production table definitions and Railway JDBC/session configuration (MySQL 9.7.2, applied 001, deployed pilot and approved template were supplied by the operator), full application boot and startup hooks (deliberately kept stopped), real provider behavior for the new Other prompts and mobile UI rendering, full JVM/host power failure or MySQL server restart durability, all possible deadlock/interleaving cases, and production multi-replica behavior. Service/ORM recreation and actual JDBC-connection interruption were tested; no real JVM crash or Meta request was performed. Memory-backed disposable storage does not establish power-loss durability. No existing database, production, Railway setting, deployment, migration/backfill outside the new containers, or live message was accessed/performed.

**Readiness:** local database validation has passed on the operator-reported production version, MySQL 9.7.2. The code is ready for a controlled, initially disabled pilot deployment after the operator compares the real legacy schema with the tested baseline and applies only 002. That production SQL has not been applied here; 001 is operator-confirmed applied; 002 remains unapplied here. Live sending retains the existing configured single-worker/recipient, real sender, approved-template and explicit-participation gates, plus the new assignment-history requirement. Submitted-report approval, recurring 19:00 sending, reminders, retention/deletion tooling and a review dashboard remain later batches; this implementation does not write assignments, hours, overtime or payroll.

## Changed files for the 25 October batch

Backend runtime (under `src/main/java/life/work/IntFit/backend/`):

- `reporting/ReportingService.java`, `ReportingFlow.java`, `ReportingModel.java`, `ReportingStore.java`, `JdbcReportingStore.java`, `ReportingAdminController.java`; new `ReportingResolutionService.java`.
- `model/entity/MasterWorksite.java`, `dto/MasterWorksiteDTO.java`, `service/MasterWorksiteService.java`, `controller/MasterWorksiteController.java`. Existing MapStruct mappings automatically include nickname; the mapper source is unchanged.
- New `docs/sql/002-whatsapp-reporting-other-and-nicknames.sql` and this operating guide. Applied script 001, application properties, sender/webhook transport and scheduling implementation remain unchanged.

Backend tests (under `src/test/java/life/work/IntFit/backend/reporting/`):

- Updated `MemoryReportingStore.java`, `ReportingServiceTest.java`, `ReportingAdminControllerTest.java`, `ReportingLoggingTest.java`, `ReportingOutboxPumpTest.java`, `ReportingMySqlIntegrationTest.java`.
- New `ReportingTestSupport.java` extracts shared fixture/helpers without inheriting/rerunning old test methods; new `ReportingOtherTest.java` and `MasterWorksiteNicknameTest.java`.

Frontend (`C:/Users/OAB2K/.H/interior-fitout`):

- `services/masterWorksiteService.js`, `app/worksites_stack/group.jsx`.
- New `services/__tests__/masterWorksiteNickname.test.js`.

Both branches remain master, with backend HEAD `fd318ec9b465f51401ced2b87bd5f513fc1782ec` and frontend HEAD `7493c84d9bb73d6bd7e89feb1221415108cfbfa4`. All changes remain uncommitted and undeployed. The pre-existing backend application.properties modification and frontend untracked .claude directory were preserved.
