# IntFitout WhatsApp reporting — daily requests and management summaries

This batch implements disabled-by-default recurring daily request creation at **19:00 Asia/Hebron**, the existing persistent multi-site/Other conversation through **submitted, awaiting review**, and identical initial-dispatch summaries to two independently configured management recipients. It adds no assignment approval, hours/overtime/payroll writes, reminders, or response-completion summary. No frontend changes or new dependencies are required.

The operator reports deployed revision `9c1c753` and applied migrations **001 and 002**. This work did not query production or Meta. **Do not reapply or modify 001/002.** All code/migration/configuration changes described below are local preparation; no Railway activation, live sending or production SQL was performed.

## Configuration and rollout

Existing `WHATSAPP_REPORTING_ENABLED` and `WHATSAPP_REPORTING_SEND_ENABLED` remain the receiving/global-sending gates. A global sending pause leaves valid replies persistable and next intents pending; it stops worker openings, follow-ups and management sends. Turning rollout off also blocks non-pilot conversations; use the global send pause while keeping rollout/receiving setup intact for those conversations. Fully disabling reporting bypasses reporting processing in the signed webhook; do not use that to pause an ongoing conversation.

New environment variables (all values belong only in Railway/private operator configuration):

| Variable | Default / setup |
| --- | --- |
| `WHATSAPP_REPORTING_DAILY_ENABLED` | `false`; explicit `true` enables the daily scheduler and daily-associated sending |
| `WHATSAPP_REPORTING_ROLLOUT_ENABLED` | `false`; preserves one configured pilot worker/recipient. Explicit `true` allows other active, explicitly participating workers with valid numbers |
| `WHATSAPP_REPORTING_MANAGEMENT_OWNER_RECIPIENT` | Unset; `<owner international number with optional leading +>` |
| `WHATSAPP_REPORTING_MANAGEMENT_FATHER_RECIPIENT` | Unset; `<father/top-boss international number with optional leading +>` |
| `WHATSAPP_REPORTING_MANAGEMENT_TEMPLATE_LANGUAGE` | Unset; `ar` only after the approved definition confirms it |
| `WHATSAPP_REPORTING_MANAGEMENT_TEMPLATE_CONFIRMED` | `false`; never enable merely because a template was submitted |

Owner/father numbers are independent of worker records, worker participation and assignment history. Supply the operator-confirmed numbers, not synthetic examples from tests. Only an optional leading `+` is stripped; whitespace/local/00 formats are rejected and +970/+972 are distinct. Both numbers must be valid and different after normalization. Numbers are never constants in source/tests. Neither manager gets a questionnaire even if their number is also on an active worker record. Do not fabricate assignments or repurpose worker 10041 to qualify management.

Preserve the existing real sender/access credentials and approved worker template variables: `WHATSAPP_REPORTING_PHONE_NUMBER_ID`, `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_GRAPH_API_VERSION` (existing `v25.0` default), `WHATSAPP_REPORTING_TEMPLATE_LANGUAGE=ar`, `WHATSAPP_REPORTING_TEMPLATE_CONFIRMED=true`. Worker template remains **daily_work_report_ar**, one Arabic weekday/date body parameter, quick-reply 0 `تسجيل مواقع العمل`, quick-reply 1 `لم أعمل`.

Keep `WHATSAPP_REPORTING_ADMIN_SECRET`, `WHATSAPP_WEBHOOK_VERIFY_TOKEN`, `WHATSAPP_APP_SECRET` and the existing pilot worker/recipient variables. Reporting never falls back to the connectivity test phone-number ID. Existing hello-world/test/scheduled-test variables remain separate; do not enable their senders for this rollout. Missing/malformed daily configuration or missing management approval does not break application startup or the existing manual pilot sender. Daily creation requires daily/global sending gates, the approved worker definition, shared transport and both management targets/approved definition. Management-only zero-worker summaries do not require a pilot worker ID or actual worker records; without rollout/pilot setup all workers remain excluded.

## Daily timing, persistence and restart policy

- A dedicated fixed-delay clock check begins **30 seconds after application readiness**, then every **15 seconds**, only when daily configuration is fully enabled. It creates no startup requests inline. The outbox remains a separate shared processor (first pass five seconds after readiness, then every two seconds). Time decisions use `LocalDate` and the IANA `Asia/Hebron` zone, including DST; no fixed UTC offset or previous-day loop exists.
- For report date D, active workers have at least one saved assignment dated **D−6 through D inclusive**. D itself need not have an assignment; assignments already on D do **not** suppress a request. Invoice activity, future assignments and manual worksite flags do not define activity.
- A new daily run may be created from **19:00 inclusive to 19:10 exclusive** on that same local date. This is the explicit bounded restart catch-up window. Restart outside that window cannot create a missed run. No yesterday requests are created. Today’s existing run can be inspected/resumed/finalized; an unfinished older run stays visible for operator inspection rather than being replayed on a later day.
- Worker opening attempts associated with a daily run expire at **19:10**; pending openings then become visibly CANCELLED. Manual pilot openings retain their existing same-day policy. Follow-ups retain their original request date and the existing 24-hour inbound-message window, without recalculating recent activity against a new “today.” Explicit participation disablement still blocks them.
- The roster and per-worker exclusions are committed atomically with requests/intents. The run key is unique per **business phone ID / work date**, worker requests stay unique per **worker / date**, and summary intents are unique per **run / normalized recipient**. SQL obtains an exclusive run lock directly on creation/duplicate lookup. Repeated ticks, concurrent triggers and process recreation do not create another roster, opening or summary.
- Existing same-worker/date requests are reused, never reset or resent, even if already submitted. Their original opening outcome is included in the run’s initial statistics; an acceptance may therefore predate 19:00. A request with conflicting recipient/business snapshots is excluded visibly, not rewritten.
- Summary generation waits until initial openings have left PENDING/SENDING, or until **19:10**, whichever comes first. At cutoff, UNCERTAIN/SENDING and PENDING counts stay explicit. It does not wait for workers’ answers or confirmed delivery. The snapshot and both management intents commit together once. Later statuses do not rewrite the frozen summary; inspection shows current worker outcomes separately.
- Management intents expire at **19:30** the same date. Recovery/finalization after that time may persist a warning snapshot but cannot send a late summary. Starting the server later in the evening does not replay missed worker requests. Do not enable daily sending with unresolved old intents without inspecting them.
- Management has queue priority after its snapshot exists. Invalid/withdrawn/expired individual intents become CANCELLED with a fixed failure code; the processor scans up to 50 candidates and proceeds to a valid item, preventing permanent starvation. It attempts at most one HTTP send per pass. A storage-wide failure can stop a pass and is logged with a fixed code, never provider text.

The rollout gate is separate from participation: with rollout false, only the configured pilot worker/number can qualify. With rollout true, all active workers still require explicit `eligible=true` and a normalized unique number. No contact-text inference or test-worker bypass exists. The daily run freezes reason codes including `PARTICIPATION_DISABLED`, `NUMBER_MISSING_OR_INVALID`, `MANAGEMENT_RECIPIENT`, `ROLLOUT_RESTRICTED`, `WORKER_REMOVED`, and `REQUEST_SETUP_CONFLICT`. Dispatch can subsequently cancel for `WORKER_SETUP_CHANGED`, `NO_RECENT_ACTIVITY`, `SENDER_CHANGED`, `DAILY_OPENING_CLOSED` or expiry; these outcomes remain inspectable.

## Summary counts and failure interpretation

The immutable summary contains weekday/date, active count, participating/configured count, selected request count, Meta-accepted openings, provider rejections, uncertain/in-flight sends, still-pending attempts, blocked/cancelled intents, excluded workers, and delivery failures **within the accepted group**. Managers never enter worker statistics. For a successfully prepared roster:

`active = selected + excluded`

`selected = accepted + rejected + uncertain/in-flight + pending + blocked`

The delivery-failed count is a subset of accepted, never another worker added to the rejection total. Missing setup/rollout restrictions appear by worker ID and reason in administration inspection; summary text has counts only, no wages, credentials, names or full phone lists. Any excluded workers or failed/uncertain/pending/blocked outcomes make the run status partial; a zero-selected run explicitly says no worker was contacted. Preparation/SQL failure rolls back the roster/requests and yields an **incomplete/unknown-data** warning, not a success-looking zero count. Inspection marks counts unknown for that failure. If the database failure prevents even persisting that warning, the scheduler logs failure and no delivery promise is possible. If a warning can later be persisted, management still shares the same sender/storage/provider failure risks.

A SNAPSHOT phase means content was persisted, not that either manager received it. Each recipient has independent PENDING/SENDING/ACCEPTED/REJECTED/UNCERTAIN/CANCELLED and delivery state. Failure for one does not suppress the other. Receipt of a summary never establishes that all workers received their request.

## Management template ready for operator submission

Proposed **name:** `daily_worker_dispatch_summary_ar`

**Language:** `ar`

**Components:** body only, no buttons/header/footer; **12 positional text parameters**, in this exact order. The operator must submit/review the applicable Meta category and actual approved definition; this code does not claim approval or contact Meta. Keep confirmation false until approved. Do not reuse the worker questionnaire template for summary content.

```text
ملخص طلبات العمل — {{1}}
حالة الجولة: {{2}}
العمال النشطون: {{3}}
المشاركون بأرقام صالحة: {{4}}
طلبات ضمن نطاق الإرسال: {{5}}
قبلتها Meta: {{6}}
رفضتها Meta: {{7}}
نتيجة غير مؤكدة أو قيد الإرسال: {{8}}
لم تبدأ محاولتها عند الملخص: {{9}}
محاولات محجوبة أو ملغاة: {{10}}
عمال مستبعدون من الإرسال: {{11}}
من المقبول أعلاه، تعذر تسليمه: {{12}}
قبول Meta لا يؤكد التسليم. هذا ملخص المحاولات الأولية، وليس اكتمال إجابات العمال.
```

| Position | Meaning | Successful example | Partial example |
| --- | --- | --- | --- |
| 1 | Arabic weekday and original work date | الخميس، 22/10/2026 | الخميس، 22/10/2026 |
| 2 | Truthful run status | اكتملت المحاولات الأولية؛ قبول Meta فقط | نتائج جزئية؛ يلزم التحقق والمتابعة |
| 3 | Active workers | 8 | 10 |
| 4 | Participating workers with valid numbers | 8 | 8 |
| 5 | Selected requests within rollout | 8 | 8 |
| 6 | Accepted by Meta | 8 | 5 |
| 7 | Provider rejected | 0 | 1 |
| 8 | UNCERTAIN or SENDING | 0 | 1 |
| 9 | Still PENDING at snapshot | 0 | 1 |
| 10 | Blocked/cancelled | 0 | 0 |
| 11 | Excluded workers | 0 | 2 |
| 12 | Delivery failed within accepted | 0 | 1 |

For no selected recipients, status is `لم تتم مراسلة أي عامل؛ راجع الاستبعادات`; for a failed preparation, status is `فشلت الجولة؛ البيانات غير مكتملة` and parameters 3–12 are `غير متاح`. Give Meta representative examples for those states too. The body is bounded to 1024 Unicode code points. Both management payloads use the same persisted parameter array and language; only their destination differs.

## Protected Postman operations

Use the existing dedicated header on **every** reporting administration operation:

`X-WhatsApp-Reporting-Admin-Secret: {{reporting_admin_secret}}`

JSON bodies use `Content-Type: application/json`. Keep that credential in private Railway/Postman values, never frontend code, query strings, shared exports or source. Missing/wrong/duplicate credentials fail closed; missing admin configuration returns 503. Unknown fields/query parameters are rejected.

- `POST /api/whatsapp/reporting/admin/daily/preview` with `{"workDate":"2026-10-22"}`: read-only active/configured/selected counts, worker IDs/exclusion reasons, readiness/window flags and rendered summary preview. No run, intent or send is created. Previewing another date is allowed; triggering another date is not.
- `POST /api/whatsapp/reporting/admin/daily-runs` with `{"workDate":"<today in Asia/Hebron, yyyy-MM-dd>"}`: same enabled gates, orchestration and deduplication as the scheduler. Creates durable work; no HTTP occurs in its database transaction. A new run outside 19:00–19:10 returns 409, another date returns 400, disabled/incomplete configuration returns 503. It accepts no worker/recipient/message/template/provider override.
- `GET /api/whatsapp/reporting/admin/daily-runs/<yyyy-MM-dd>`: run ID, phase/failure, cutoff/expiry, frozen summary parameters/body, current worker counts (or unknown), roster request links/reasons, and separate management outbound IDs/states/failure codes/Meta IDs/delivery. Full destination numbers/payloads are omitted.
- Existing `GET /api/whatsapp/reporting/admin/status`, `GET /requests?limit=20`, `GET /requests/<uuid>`, protected worker/site setup, Other resolution and outbound dispatch/reconciliation remain. The manual `POST /requests` remains a **pilot-only** administrative initiation path and accepts just workDate; broader workers are initiated through the daily run.

Every returned summary is a snapshot; current states can differ later. To inspect one worker, follow its requestId through the existing report endpoint. Inspect a manager’s returned outbound ID through its dispatch/reconciliation response; no fake worker request is created for management.

## Data and boundaries

Worker entities/DTOs remain unchanged. Reporting setup stays JDBC-only. Master worksites now expose an optional nickname through the existing entity/DTO/MapStruct mapping and ordinary APIs. Existing approved names, IDs and contact data are preserved:

- `team_members.whatsapp_reporting_eligible`: default false. Explicit participation/active reporting eligibility, **not an inferred employment status**. Set false when a worker must stop participating. No contact parsing or automatic opt-in.
- `team_members.whatsapp_number`: nullable, unique international digits, 7–15 digits, no leading zero. The protected setup API accepts either these digits or a leading `+`, strips only that `+`, and rejects spaces, punctuation, local/`00` formats. `970…` and `972…` remain different numbers. Eligibility requires a number; free-text `contact` is never read or changed.
- `master_worksites.whatsapp_reporting_selectable`: retained unchanged for compatibility with old clients and the protected setup operation. It no longer restricts reporting choices and is not an activity/disablement flag.
- `master_worksites.nickname`: optional, trimmed, maximum 80 UTF-16 characters without controls/format characters, no uniqueness requirement. Blank uses the approved name. Full updates with omitted/null nickname preserve the stored value; an empty string clears it. The narrow `PUT /api/master-worksites/{id}/nickname` body `{"nickname":"..."}` changes only that field; `{"nickname":""}` clears it. This uses the existing master-worksite API permissions, never reporting administration credentials in the frontend. The group details editor offers Save Nickname; existing creation APIs accept nickname as an optional DTO field.

The five existing report/conversation tables remain; 003 adds daily runs and roster membership:

| Table | Purpose |
| --- | --- |
| `whatsapp_report_requests` | Unique worker/work-date request, recipient and receiving phone-ID snapshots, conversation step/revision, current site, last inbound time, outcome and immutable submission time |
| `whatsapp_report_sites` | Stable per-request entry ID; nullable original master ID; shown label snapshot; original Other description; separate administrator resolution metadata |
| `whatsapp_report_coworkers` | Unique request/entry/mentioned-worker and name snapshot, including unresolved Other entries; supporting information only |
| `whatsapp_report_inbox` | Deduplicated normalized message/status IDs, context, issued action, receipt/message times and disposition; bounded accepted Other text only, no complete payload/contacts array |
| `whatsapp_report_outbound` | Generated template/prompt payload, unique request/revision, claim/result state, Meta ID and observed delivery state |

The shared outbox necessarily stores worker/management recipient snapshots and worker-facing labels in its generated payload, but no credentials. Administration responses omit outbox payloads and recipient numbers. Report inspection includes normalized inbox actions/IDs, selected site/coworker snapshots and outbound state. No personal records were added during implementation.

There are reporting-internal foreign keys, but no new foreign keys to legacy workers/sites. This preserves existing delete behavior while retaining report snapshots/logical IDs. Deleting a worker/site does **not** delete an existing report, inbox or outbox. A deleted or ineligible worker cannot continue or dispatch. This is not a comprehensive data-deletion implementation or retention policy. Retention/administrative deletion tooling remains to be designed.

## Worker conversation

1. Send `daily_work_report_ar` with one body text parameter such as `الخميس، 22/10/2026`. Quick-reply components have index `0` = `تسجيل مواقع العمل`, index `1` = `لم أعمل`, and carry server-generated request/revision action payloads.
2. The first reply opens site selection or a **did not work** confirmation.
3. Site selection opens optional coworkers for that site. Tapping a coworker toggles their mention; **متابعة بدون أسماء أخرى** continues. Then select another site or **انتهيت من المواقع**.
4. A separate confirmation explicitly submits the report for review. The final Arabic acknowledgement says it awaits review and no assignments/hours were changed.

Lists have at most 10 rows: up to 6 known-site choices plus previous/next, Other and finish; coworker lists retain up to 7 names plus navigation/continue. Names/site labels only, stripped of log/display controls, phone-like numbers redacted, labels truncated for Meta limits. Only explicitly eligible coworkers appear; a coworker needs their own explicit profile setup, and is never sent a request merely because someone mentions them. Pilot allowlisting remains the default; only explicit rollout enables other participating workers. Management numbers are excluded from questionnaires. No wage, payroll, contact, or role fields are queried for choices. Names may be ambiguous or truncated; choose the eligible roster/site labels carefully before the pilot.

Draft entry selection is additive (up to 50 listed/Other entries); coworkers can be toggled while at that site's step. This batch does not provide entry removal, revisiting completed coworker steps, arbitrary free-text reporting, or editing a submitted report. Bounded text is accepted only during the persisted Other-capture step. Back from confirmation allows adding more sites; a did-not-work confirmation can go back to the initial choices. Requests are unique per worker/date: repeated initiation returns the existing request and never resends the template or resets a submission.

The original `work_date` is never recomputed from a reply's date. Reply context is matched to the specific issued outgoing prompt and revision, including after midnight or when multiple days have requests. Template quick replies (`type=button`), interactive `button_reply`, `list_reply`, and correlated Other text are supported; external button/list titles are never trusted as selections. Unrelated events retain HTTP 200 behavior. A wrong business phone ID or sender is ignored by reporting, without creating reporting data.

## Seven-day activity and Other entries (25 October 2026)

For immutable report work date **D**, activity means at least one **saved assignment dated D−6 through D inclusive**. Backend SQL binds `LocalDate` values; today is derived from `Asia/Hebron`, without UTC ISO truncation. D−7 and future assignments do not count. No assignment on D itself is required. Invoice activity, child-worksite state, Saturday–Friday weeks and manual reporting flags are not substitutes.

Opening request initiation and opening-template dispatch require both recent worker activity and the explicit participation/number/rollout gates. Idempotent inspection of an already-created request does not resend it. Prompt dispatch and inbound replies use the original request, not a fresh activity check against today; explicit worker disablement still blocks them. If an opening intent loses its recent activity, it becomes visibly CANCELLED without sending and needs operator inspection; do not create artificial assignments to bypass the gate.

Known choices are existing master worksites with assignment activity in the same D−6/D window, regardless of the legacy selectable flag. The worker sees nickname when nonblank, otherwise approved name, sanitized/truncated with a permanent `#<master-id>` suffix to distinguish duplicate/truncated labels. The exact issued row title is saved when selected, even if a nickname changes before the reply. Inspection retains original master ID and current business approved name (null if the master was deleted), separately from the snapshot. Coworker choices retain the existing explicit participation policy; selecting a coworker never sends them a request or writes an assignment.

**Other** appears as `موقع آخر` / `غير موجود بالقائمة`, even with zero known choices; initiation no longer requires selectable sites. It asks `ما اسم الموقع الذي عملت فيه؟ اكتب الاسم أو وصفًا يساعدنا على معرفته.` Accept 2–300 Unicode code points, trimmed only, with at least one letter/digit and no control/format/line-separator characters. Invalid input is deduplicated without storing its raw body and reissues the instructions with cancellation. Cancel returns to site selection. A valid description creates its own stable entry, then offers optional coworkers and returns to site selection. Other-only, mixed, and multiple-Other reports can be submitted. The combined limit remains 50 entries. Confirmation enumerates every entry with bounded labels to fit message limits; inspection retains complete original descriptions.

Text correlation is fail-closed:

- Signature verification precedes parsing. Business phone ID, sender, persisted request snapshots and explicit worker/rollout setup must match. Management replies are ignored by worker conversations.
- A supplied context must identify the current persisted Other prompt. A conflicting/malformed context never falls back to another report. Correlation is scoped to the persisted worker/sender/recipient; another worker’s context or capture cannot claim the reply.
- Contextless text is accepted only when exactly one persisted capture exists for this worker/recipient/business sender and its current prompt is ACCEPTED. Multiple captures are recorded as AMBIGUOUS without advancing or retaining the body; the worker must use WhatsApp Reply on the intended prompt. No report is selected by “latest for this phone.”
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

## Shared dispatch, signatures and recovery

All worker openings/follow-ups and management summaries use the existing real business sender, shared credentials and bounded HTTP transport: 3-second connect/pool limits, 10-second response/socket limits, redirects and automatic retries disabled. No HTTP runs in a database transaction. The outbox commits PENDING → SENDING **before** the one attempt, then records ACCEPTED plus validated message ID, REJECTED, or UNCERTAIN. Acceptance is not delivery. Credentials, raw provider bodies, payloads, full phone numbers and worker descriptions are never logged.

The webhook validates HMAC over original bytes before parsing. Worker senders are matched to actual persisted requests and current explicit worker/number/rollout setup; every reply must match the request’s recipient/business sender and issued prompt/revision. Management replies cannot enter that flow. Delivery events must match a persisted outgoing message and its recipient/business snapshots. An early status can be retained only for a unique SENDING/UNCERTAIN intent for that recipient/sender; it affects delivery only when its actual Meta ID is bound. Arbitrary statuses, wrong recipients/business IDs and ambiguous unbound statuses are safely ignored. When multiple unbound sends share a recipient, an early status may need later provider evidence/status after binding; it is never guessed onto a request. Signed receipt does not justify guessing the latest report.

Accepted inbound events commit inbox, conversation/entry/coworker mutations and next outgoing intent before HTTP acknowledgement. Failure withholds acknowledgement with sanitized 503; redelivery deduplicates events already committed. Original work dates and persisted Other-capture correlation survive midnight and process recreation.

SENDING/UNCERTAIN/REJECTED/ACCEPTED/CANCELLED are never reset to PENDING or automatically resent. A process can stop after Meta accepted but before the result is saved; that row remains SENDING. Pause dispatch, finish any live attempt, inspect evidence, then use:

- `POST /api/whatsapp/reporting/admin/outbound/<uuid>/resolve`, `{"decision":"accepted","metaId":"<evidenced wamid...>"}` to bind proven acceptance without resending. For a worker this can transactionally release matching deferred replies. Management reconciliation never changes worker conversations.
- The same route with `{"decision":"abandon"}` closes a pending/interrupted/uncertain intent without sending. It does not retract a message already accepted.
- `POST /api/whatsapp/reporting/admin/outbound/<uuid>/dispatch` accepts no body/query and only claims a still-valid PENDING intent. Repeated calls on attempted states never retry them. Global sending pause blocks all dispatch; accepted reconciliation can run with sending paused.

A frozen management summary is not regenerated/resubmitted to report later delivery or response completion. Independent evidence-based recipient recovery uses the original intent; no blind retry endpoint exists. There is no cross-system atomic transaction or durable exactly-once claim across MySQL and Meta. Database uniqueness/locking protect overlapping initial run/request/intent creation; keep a controlled deployment size and inspect deadlock/storage failures rather than assuming every network outcome is known.

## Consolidated migration, onboarding and activation checklist

1. **Prepare one reviewed maintenance/code/configuration batch.** Confirm backup/change window and compare actual schema with new `docs/sql/003-whatsapp-daily-runs.sql`. Migrations 001/002 are already applied; never rerun them. Preserve all existing credentials/pilot/template values and the local application.properties bytes. New automation/rollout/management confirmation remain false during preparation.
2. **Pause sending and stop application instances for DDL.** Old code and partially migrated schema must not process concurrent reporting writes. Apply **only 003 manually, once**, then verify the two new tables, outbound routing backfill, owner/FK/unique constraints and unchanged old payloads. DDL commits implicitly: inspect/repair partial execution rather than blindly rerun. This work has not applied production SQL.
3. **Deploy this backend only after schema completion.** No startup migration runner or new JPA fields were introduced. The pre-existing local `spring.jpa.hibernate.ddl-auto=update` remains untouched; it does not apply these JDBC-only changes. Keep SQL under operator control and review existing production DDL settings with the rest of the application. No full configured startup was tested here.
4. **Configure management targets and submit the dedicated template.** Use the actual operator-confirmed owner/father international numbers as independent env values, without making worker records. Keep daily automation and management-template confirmation false until the exact Arabic definition is approved. Do not change the worker template, webhook URL/signature secret or test sender variables. No category/approval is inferred by code.
5. **Onboard workers deliberately.** Existing `PUT /api/whatsapp/reporting/admin/workers/<real-id>` body `{"eligible":true,"whatsappNumber":"+<explicit verified international digits>"}` configures participation. Set false/null when appropriate. Each worker must have genuine assignment history in D−6/D; today’s existing assignments do not suppress them. Do not fabricate history or repurpose worker 10041. Management numbers are excluded from worker questions. Nicknames/Other and recent-site rules remain as documented below; the old selectable flag is compatibility-only.
6. **Preview before enabling broader sending.** Inspect active/configured counts and every exclusion. `WHATSAPP_REPORTING_ROLLOUT_ENABLED=false` preserves the pilot gate and can produce a no-worker-contact management summary. After explicit review, rollout true allows all active participating profiles; this is the additional-worker authorization. Preview includes no full number lists and sends nothing. A missing/inactive pilot no longer prevents an independent management-only summary.
7. **Enable in one final reviewed Railway change batch.** Preserve `WHATSAPP_REPORTING_ENABLED=true`; set the existing global send flag true only when ready, management language/confirmation from approval, and `WHATSAPP_REPORTING_DAILY_ENABLED=true`. Choose rollout true only after onboarding/preview review. Review and resolve old pending/SENDING/UNCERTAIN intents before restoring dispatch. Enabling within 19:00–19:10 can intentionally create today’s one bounded catch-up run; outside it no new missed run is replayed.
8. **Observe the run and both deliveries separately.** Use run inspection, follow worker request links and verify each management message’s own acceptance/status. Test duplicate/manual triggers, partial failures, zero configured workers, follow-up replies and after-midnight Other input. Do not interpret the summary as delivery proof or completed reports. Pause with the global send flag if needed; inspect before any recovery.

## Verification and limitations

On **25 October 2026**, **129 distinct isolated unit/MVC cases passed across targeted runs**, including 16 daily-run/scheduler/summary cases, 27 administration protection cases, 16 sender cases, existing worker/Other flows, settings, inbox/transaction wiring, pump and signed-webhook checks. Final source/test compilation passed. No full application/context, configured datasource, live HTTP or startup sending was used.

**50 distinct real MySQL cases passed across the initial run and focused corrections/reruns**, on actual **MySQL Community Server 9.7.2**, Docker Linux/amd64, REPEATABLE-READ, existing Connector/J 8.3.0 and actual JpaTransactionManager. The initial 50-case run passed 49 and found an INSERT IGNORE/shared-lock upgrade deadlock under six simultaneous daily triggers. A no-op duplicate-key update now takes the exclusive run lock directly. A targeted 10-case rerun passed 9, including concurrency, and exposed a test-only queue ordering assumption when timestamps tied. That assertion now drains a bounded queue rather than assuming UUID order. The final two-case database rerun passed both concurrency and queue progression with zero failures/errors/skips. The 40 unchanged legacy database cases were not unnecessarily rerun after passing. All newly created containers, anonymous volumes and networks were removed after success and failure.

Real database coverage includes actual 003 routing backfill with exact payload/Meta-ID preservation, run/worker-date/management-recipient uniqueness, atomic roster/request creation, atomic snapshot plus two intents, forced SQL rollback/recovery, concurrent triggers, multiple-worker and deferred/Other reply isolation across ORM/service recreation and midnight, management-only configuration, independent manager dispatch/status validation, interruption after mocked acceptance without resending, and unchanged assignment/overtime/payroll sentinels. Fake-store clock/restart/concurrency checks are not presented as database/host-crash proof. The original MySQL startup guard, generated local credentials, loopback binding and hash protection remain intact.

The final application.properties SHA256 remains `8E2B078835F168C9ECF15BBC9F40F40B5CB4EEC17A6885102D30833AA37772AF`; its pre-existing modification was preserved byte-for-byte. Applied scripts 001/002, security/CORS, uploads, financial/assignment behavior, frontend and connectivity-test senders were not modified.

All tests use synthetic identities and mocked Meta traffic. The guarded Windows Docker runner pins official `mysql:9.7.2@sha256:e2fa358a74ac81148e7e27d9433e4c0ab62133de8ea4428b4f39a51a4ba7d07f`, verifies SELECT VERSION exactly, requires the local Docker Desktop Linux named pipe, and uses a new labeled container/network, memory-backed schema, generated credentials, loopback-only random port and per-run database guard before fixture DDL. It never falls back to application datasource settings or existing databases. Ownership-checked cleanup removes only its resources, restores process variables and checks the application.properties hash. Docker unavailable means stop, no alternative database. Maven runs offline; no Spring Boot startup or application hooks are loaded.

```powershell
.\scripts\test-reporting-mysql.ps1 -MavenExecutable 'C:\Program Files\Maven\apache-maven-3.9.9\bin\mvn.cmd'
```

The optional `-IntegrationMethods 'methodOne+methodTwo'` selects only named methods in the same guarded integration class; it cannot select arbitrary application suites. This permits targeted reruns after a concrete failure. The base fixture applies actual migrations 001/002/003 only inside the disposable schema, checks original report/outbox preservation, and snapshots assignment/overtime/payroll sentinels. Actual JPA/JDBC transaction integration is tested without full component scanning. Fake-store unit/recreation tests are labelled separately from real MySQL tests.

Remaining limits: the existing serial processor makes one HTTP attempt per pass with a two-second gap; a large/slow queue can reach cutoff with explicit pending/blocked counts. Review capacity before rollout. Provider/template approval and delivery are operator-controlled and were not tested live; database availability is required for durable progress; memory-backed tests and service/JDBC interruption are not host-power-loss durability proof; all possible interleavings and production multi-instance behavior are not certified. Full application boot, deployed environment settings and production schema were deliberately not accessed. Roster exclusions are frozen at preparation; later setup changes require inspection and do not reset a worker/date request. Snapshot counts can differ from later live state, and there is no automatic completion summary/reminder or approval-to-assignment operation.

## Changed-file manifest

New files:

- `docs/sql/003-whatsapp-daily-runs.sql`.
- `src/main/java/life/work/IntFit/backend/reporting/DailyReportingService.java` and `DailyReportingScheduler.java`.
- `src/test/java/life/work/IntFit/backend/reporting/DailyReportingTest.java`.

Updated files:

- `docs/whatsapp-reporting-pilot.md`, `scripts/test-reporting-mysql.ps1`.
- Under `src/main/java/life/work/IntFit/backend/reporting/`: `ReportingSettings.java`, `ReportingModel.java`, `ReportingStore.java`, `JdbcReportingStore.java`, `ReportingService.java`, `ReportingSender.java`, `MetaReportingSender.java`, `ReportingOutboxPump.java`, `ReportingAdminController.java`.
- Under `src/test/java/life/work/IntFit/backend/reporting/`: `MemoryReportingStore.java`, `ReportingServiceTest.java`, `ReportingOtherTest.java`, `MetaReportingSenderTest.java`, `ReportingAdminControllerTest.java`, `ReportingMySqlIntegrationTest.java`.
- `src/test/resources/reporting/mysql-legacy-baseline.sql`.

Branch remains `master`, HEAD `9c1c753cd64b568ec86b688bf2f359e1cb9683b6`. All batch changes remain uncommitted, undeployed and unsent. The separate pre-existing application.properties modification remains as it was.


## Report review and assignment approval (004)

The earlier batch sections describe the pre-review baseline. For the new review UI, protected APIs, required assignment revisions/payroll guards, manual **004 only** deployment sequence and current verification, use [Worker-report review operating guide](whatsapp-report-review.md). Migrations 001–003 and the live reporting/sending behavior remain unchanged.
