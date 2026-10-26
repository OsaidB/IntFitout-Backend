# Worker-report review and assignment approval (004)

This batch is local, uncommitted and undeployed. It does not send messages or change reporting schedules, templates, recipients, webhook processing or rollout gates. Migrations 001–003 are historical and remain unchanged. No existing database has been accessed for this work.

## Navigation and access

On web and native: **Assignments → Worker Reports** (visible button in the assignment list and header). Enter the existing **Review access key**. Default list: submitted / awaiting review, all original work dates; optionally filter by a `YYYY-MM-DD` date, approved, drafts/in progress or all. Use Refresh reports after editing the date. Previous/Next loads bounded server pages, including records beyond the old 100-request administration limit.

The backend still has no general authenticated user identity suitable for this feature. Every review route uses the same fail-closed `WHATSAPP_REPORTING_ADMIN_SECRET` as existing reporting administration, in exactly one `X-WhatsApp-Reporting-Admin-Secret` header. Missing configuration is 503; missing/wrong/repeated credentials are 403. A shared key is stored as `SHARED_REVIEW_KEY`, never a named human reviewer.

The masked key is held only by the mounted screen/client closure. Unlock clears the text input. **Lock / clear access**, screen unmount or app reload clears access and aborts pending review requests. It is never saved in AsyncStorage/localStorage, URLs, EXPO_PUBLIC, bundled config, logs or global Axios defaults. Review failures discard the original Axios object, headers and request config. Other application HTTP calls do not receive the review credential.

## Supported review flow

1. Open a report. Drafts are inspection-only. Sending may be paused, and the worker need not remain active/eligible today.
2. Compare original answer/date, nickname snapshot, approved business name, Other descriptions and coworker mentions with current assignments, custom regular/overtime allocations and overtime total for that worker/date. No phone, wage or raw inbox/outbox data is returned by the review APIs.
2b. Optionally tick **Also assign ‹name› to this worksite** beside a mentioned coworker. Selection is per coworker **and** per worksite, so the same person can be selected at several sites, and nothing is proposed for anyone until you tick it. Each selected person then shows their own saved day and the day they would end up with, with their own hours and overtime controls.
3. Resolve included Other entries by searching **all** master worksites (including inactive), linking an existing master, or explicitly creating a master through the existing business validation. Original descriptions stay intact. City, when supplied, must be an existing canonical city; it is optional for creation. Approved reports cannot be resolved or changed.
4. Include/exclude original entries explicitly and edit the proposed master set. Duplicate resolutions can point at one master while retaining every original entry; assignment rows are deduplicated. Included unresolved/deleted masters and selected deleted masters block approval.
5. Review overtime and **Edit / reset regular and overtime allocations**. The existing eight-hour regular day is unchanged. Replies never supply hours. Existing overtime and valid custom splits are preserved unless explicitly edited. Changed sites or overtime that invalidate a saved custom split require reconciliation or an explicit AUTO reset in the existing hours editor.
6. DID_NOT_WORK with no records is an approved no-op. With existing assignments/overtime, choose explicit removal, enter zero overtime when its record exists, and explicitly reset affected custom splits. The confirmation shows before/after sites, overtime and resulting allocations. There is no silent deletion of overtime/custom splits.
7. Compare and approve once. The confirmation names every person the approval affects with their before/after worksites and hours. The screen shows immutable approval evidence and actual saved assignments and refreshes the report list. Returning to Assignments refreshes the displayed day. Coworker assignments are created **only** for the pairs an administrator explicitly ticked; a mention on its own still assigns nobody. No payroll line, adjustment, wage, payment or snapshot is changed by approval, for the reporter or for any selected coworker.

After a stale conflict the proposal remains in memory. Refresh saved state, compare it with the proposal, and review again. The planners have **Review saved day without losing proposal**, which shows the current saved day and requires confirmation before adopting its revision. After an uncertain/network approval outcome, the screen checks stored review status; if that check fails, approval stays disabled until a successful inspection. Identical approval retries are safe; a different decision after approval is a conflict.

## API contracts

Base: `/api/whatsapp/reporting/admin`. All responses and errors are `Cache-Control: no-store`; all these routes require the dedicated header. Existing administration routes remain available. Unexpected/repeated query parameters, JSON keys, duplicate JSON fields, invalid scope overrides or malformed values are rejected by the new review routes.

| Method and path | Contract |
| --- | --- |
| GET `/reviews` | Optional `date=yyyy-MM-dd`, `status=AWAITING_REVIEW` (default), `APPROVED`, `DRAFT`, `ALL`; zero-based `page=0`, `size=30` (1–100). Page ≤100000. Response `{items,page,size,total}`. Rows: request ID, worker ID/name/deleted fallback, original workDate, step/outcome, submission time, reviewStatus, deliveryProblem. No per-row inbox/outbox hydration. |
| GET `/reviews/{canonicalUuid}` | `{report,entries,coworkers,existing,revision,approval,payrollStatus}`. Each coworker carries its stored `workerId`, so a selection never depends on matching a name. Existing is only the worker/date: assignment row IDs/master names/custom decimals as strings, overtime decimal text and whether a raw overtime record exists. Approval is null until approved; afterward contains approvedAt, reviewerKind, reviewed decision, before/after evidence and saved day revision. |
| GET `/review-masters?q=&page=0` | Literal substring search of approved names/nicknames across all masters, 50 rows per page; only IDs/names/nicknames. |
| POST `/reviews/{id}/entries/{entryId}/resolve` | Exactly `{masterWorksiteId:123}` or `{create:{approvedName,nickname?,notes?,city?,area?,subArea?,locationDetails?,projectSizeTier?,estimatedAreaM2?}}`; reuses existing resolution and master validation/transaction logic. |
| POST `/reviews/{id}/approve` | Decision below; returns refreshed detail. Only original request worker/date can be mutated, regardless of the submitted site corrections. |

Approval example (IDs are illustrative, not operator setup):

```json
{
  "expectedRevision": 4,
  "outcome": "WORKED",
  "masterWorksiteIds": [11, 12],
  "includedEntryIds": [11, 90],
  "overtimeHours": "1.5",
  "removeExistingConfirmed": false
}
```

`expectedRevision`, outcome, both ID arrays and the boolean are required. WORKED requires at least one distinct positive master ID; DID_NOT_WORK requires both arrays empty. Included entry IDs refer to the original report entries; they are separate from the admin-corrected master set. IDs must be distinct, at most 100 per array. Optional overtime decimal is null/omitted to preserve the stored total, otherwise a supported non-negative exact decimal. Optional `regular`/`overtime` use existing allocation contracts: `{mode:"AUTO"}` or `{mode:"CUSTOM",hours:[{masterWorksiteId:11,hours:"8"}]}`. Omitted categories preserve current stored values and are validated. Included Other entries must be resolved even when the administrator adds a different master to the proposal. No worker/date/recipient/reviewer/fingerprint override is accepted.

Errors: 400 invalid input; 403 rejected credential; 404 missing report/entry; 409 stale/missing revision, reviewed conflict, deleted/unresolved record, explicit removal needed or frozen payroll; 422 allocation validation with affected member/category errors; 503 missing credential or unavailable storage/indeterminate operation. Check persisted review status after an uncertain result.

## Transactions, revisions and payroll

New JDBC-only tables: `assignment_week_guards`, `assignment_day_revisions`, `whatsapp_report_reviews`. No new JPA mappings or dependencies. Absence of a review row means DRAFT if unsubmitted, AWAITING_REVIEW if submitted: all existing submissions are immediately reviewable without a backfill or resend. Existing/empty days initially have revision zero; guard rows are created lazily, inside the write transaction.

Global lock order: Saturday payroll-week guard → assignment-day guard → reporting worker → request. Existing resolution/inbound retains worker → request order and never needs the new guards. All assignment saves, approvals and payroll generate/adjust/override/finalize operations use the same week guard; write transactions use READ_COMMITTED, so waiting for a lock does not leave an obsolete repeatable-read snapshot. Unique guard upserts obtain an exclusive lock without INSERT IGNORE upgrade races. Guard rows exist independently of payroll shells, covering races when neither the day nor payroll week exists. Finalization already completed/paid is not recomputed.

`GET /api/assignments/day?date=...` now adds `revision`, read together with complete assignments/overtime in a repeatable-read transaction. `POST /api/assignments` now **requires `expectedRevision`**; revision validation happens before any assignment/overtime mutation. All known frontend writers were traced: web/native planners, Add Assignment and worksite Assign Workers modal. The latter two use the shared `addAssignmentsToDay` builder; planners use `buildPlannerPayload`. A whole-day save increments the revision, even if the payload matches, so previous editors become stale. Approval increments it only for actual assignment/overtime changes; an approved matching/no-op decision avoids rewriting those records.

The approval accepts an optional `coworkers` array of `{workerId,entryIds,overtimeHours?,regular?,overtime?}`. Each selection is validated against the stored mentions of **that** report: an unmentioned worker, a worker/entry pair the report never mentioned, the reporting worker themselves, a repeated worker, or an entry that is excluded or whose worksite is dropped from the final proposal are all rejected before any write. `GET /reviews/{canonicalUuid}/coworkers/{workerId}/day` is a protected read-only preview of one mentioned coworker's saved day; it creates nothing and refuses a worker this report does not mention. Omitting `coworkers` leaves the reporter-only request, payload and stored evidence exactly as before.

A selected coworker's target day is **their existing worksites plus the selected ones**, so their other sites are preserved rather than replaced and an already-assigned pair creates no duplicate. Every affected person goes through one multi-member `AllocationPlanner` plan, so the existing eight-hour regular-day rule and the existing allocation validation apply per person across that person's own complete site set; the reporter's hours and overtime are never copied onto anyone. Overtime is per person and omitting it keeps what that person already has. Reporting or messaging eligibility is not required to assign an existing coworker. A selected coworker's own report, answers and review status are never rewritten or auto-approved.

The worker-scoped approval never calls the destructive whole-date save. JDBC assignment deltas, overtime edits, revision and immutable review evidence commit atomically under the actual JpaTransactionManager. A failure at evidence insertion rolls everything back, for the reporter and for every selected coworker together. Every affected worker is locked in ascending worker-id order inside the existing payroll-week and assignment-day guards, so two approvals touching overlapping people cannot deadlock. Stale `expectedRevision` values are rejected and the FINALIZED/PAID guard protects every affected person, not only the reporter. The decision fingerprint covers the coworker selections, so an identical retry returns the existing review without applying anything twice while a differing retry fails. A stored SHA-256 fingerprint of the typed decision makes identical retries return the existing review; conflicting repeats fail. The original request/sites/coworker snapshots are not rewritten. Approval and all assignment/overtime saves reject FINALIZED/PAID periods before mutation. Payroll finalization waits on the same guard and snapshots committed rows, or wins first and causes approval to fail.

Direct/manual SQL writers or future code that ignores this locking/revision protocol are outside its protection. No durable individual reviewer identity, rejection workflow, bulk approval or unapproval is added. Offset pages are consistent within one read transaction; a changing report list between page requests can shift positions.

## Consolidated deployment sequence — operator action later

1. Review both repository diffs and normal backup/recovery procedures. This work performs no deployment or production SQL. Do not modify/reapply migrations 001, 002 or 003.
2. Arrange a coordinated writer maintenance window. Stop all old backend instances and prevent planner saves during the transition. Do not overlap old and new backend writers: old code does not participate in these revisions/guards. Plan around the existing live reporting schedule/cutoffs; this batch changes none of them.
3. Apply **only `docs/sql/004-whatsapp-report-review.sql` manually, once** to the already-003 schema. It creates three tables and a request-date index; it changes no existing worker/report/assignment/overtime/payroll row. The review request FK explicitly matches existing `ascii/ascii_bin` request IDs. MySQL DDL implicitly commits: inspect and repair partial DDL rather than blindly rerunning it.
3b. Then apply **only `docs/sql/005-report-review-coworkers.sql` manually, once**, after 001–004 are already applied. It adds a single nullable `coworkers_json JSON` column to `whatsapp_report_reviews` and nothing else: no new table, identity or relationship, and no backfill. Coworker worker IDs already exist in `whatsapp_report_coworkers.worker_id` from 001, so the column holds only immutable evidence of which other people an approval affected. Existing reviews keep NULL, which reads as "this approval affected only the reporting worker". Migrations 001–004 are not edited or rerun.
4. Deploy the new backend after 004 and 005 are complete. These JDBC-only tables are not applied by application startup. The existing `application.properties`, including its pre-existing Hibernate DDL setting, has been preserved byte-for-byte; that setting does not create these tables.
5. Release the matching web/native frontend and require older planner clients to update/reload. Old clients missing `expectedRevision` get **409**, with no writes; there is intentionally no legacy bypass. Existing by-date/history reads keep their shapes, while complete day reads add revision. External clients that save whole days must first GET `/day`, preserve untouched assignments/overtime, and send that revision.
6. **No new Railway variables.** Reuse `WHATSAPP_REPORTING_ADMIN_SECRET=<existing-or-operator-provisioned-strong-secret>` and enter it manually in the review screen. Never put it in frontend configuration. Leave all existing daily scheduler, reporting/send gates, sender IDs, templates and webhook variables/settings unchanged.
7. Inspect old submissions using the new guarded review APIs/UI, including drafts, pagination, resolved Other, frozen payroll and saved-day revision. With an operator-chosen report in an editable period, review the before/after comparison and explicit hour/removal handling before approval. No new WhatsApp request or live message is needed to review existing submissions.
8. Rollback is not a mixed-version operation: stop assignment writers first and preserve the new evidence/tables. Reintroducing old backend writers would bypass payroll/revision protections. Prefer a forward repair or keep affected writes stopped; do not automatically drop 004 or undo approved assignments.

## Verification

Results and exact changed-file manifest are recorded below after local isolated verification. The guarded runner uses a brand-new labeled container/network and official pinned MySQL 9.7.2, verifies the actual server version and run guard, synthetic fixtures, actual JPA/JDBC transactions and mocked Meta. It never reads application datasource settings or an existing database. All container resources are ownership-checked and removed. No full application/startup hooks or real provider requests are run.

Frozen payroll periods are inspection-only in this batch: even a matching/no-op approval is blocked, rather than altering a frozen period. The existing full-context allocation/rollback test fixtures mock the new concurrency collaborator so those older tests remain scoped to allocation behavior; they were compiled, not executed. Actual guard, revision, payroll and transaction behavior is verified only in the guarded MySQL cases.

The tests do not certify production load, every possible interleaving, host power-loss durability, a deployed web/native device, or external database writers. Full configured application startup, native builds and deployed browser rendering are deliberately untested.


## Local results — 25 October 2026

- Backend: **295 isolated unit/standalone MVC cases passed**, zero failures/errors. Includes strict review authorization/input parsing, unchanged reporting reception/sender/scheduler guards, allocation validation and visible assignment revision conflicts. No full Spring application context or database in this run.
- MySQL: **76 distinct reporting/review integration cases passed** against actual **9.7.2**, using the unchanged guarded runner and official pinned image. A full 74-case suite passed; two targeted two-case runs then checked pre-004 submissions/defaults and explicit overtime/rollback, adding two distinct cases and rechecking two existing cases after strengthening their assertions. This distinguishes executed database checks from unit mocks. Actual JpaTransactionManager/JDBC transactions, concurrent approvals, both planner/approval orderings on empty and populated days, absent-week payroll/finalization races, FINALIZED/PAID protection, Other deduplication, immutable originals and forced rollback all passed. All owned containers/networks were removed.
- Frontend: **77 distinct focused Jest cases passed** across ten suites (the 76-case ten-suite run, followed by affected planner/review reruns and one added double-tap case). Covers masked/session-only access, cleared credentials/late responses, sanitized errors, original date, Other linking, confirmation/cancellation, conflict proposal preservation, saved-state refresh, uncertain-result inspection, all known assignment writers and the reused allocation editor. All seven changed/new frontend screen/client/model files also passed Babel parsing. No Expo server, native build, full browser/device visual QA or real HTTP was run.
- Final backend/test-source compilation passed. Legacy full-context allocation suites were compiled but deliberately not executed; no alternative database was used.
- `git diff --check` passes for this batch. The only excluded warning is a pre-existing blank line in the untouched application.properties modification.
- No existing database, Railway, Meta, production SQL, real recipient or full application/startup hook was accessed. All provider traffic was mocked. No commit, push, merge, branch change or deployment was performed.

Commands/logs (generated logs are ignored under backend `target/`):

```powershell
# Guarded real MySQL suite, never a configured datasource:
.\scripts\test-reporting-mysql.ps1 -MavenExecutable 'C:\Program Files\Maven\apache-maven-3.9.9\bin\mvn.cmd'
# Optional same-class targeted checks use -IntegrationMethods 'methodA+methodB'.
```

Evidence: `target/review-unit-tests.log`, `target/review-mysql-full-suite.log`, `target/review-mysql-migration-recheck.log`, `target/review-mysql-recheck.log`, `target/review-mysql-overtime-recheck.log`, `target/review-frontend-tests.log`, `target/review-planner-tests.log`, `target/review-screen-final.log`, `target/review-final-compile.log`. Only synthetic fixture values appear in these generated logs.

## Allocation baseline correction — 25 October 2026

Review found that refreshing a report/day revision retained the previous saved allocation baseline. For example, the screen could confirm an untouched 5/3 split while omitting regular allocation from the request, causing the backend correctly to preserve a refreshed 6/2 split. This correction is frontend-only; backend behavior and migration 004 are unchanged.

- `usePlannerAllocations.rebase(day)` replaces only its saved regular/overtime baseline and clears obsolete server validation messages. It retains explicit category drafts, including AUTO resets, copied-proposal state and an open editor. Existing derived validation reruns against the refreshed baseline, retained draft, proposed worksites and proposed overtime total. Invalid retained splits block saving until reconciled/reset.
- Every same-report detail refresh uses the same rebase path: **Refresh saved state (keep proposal)**, uncertain-approval status inspection, Other link/create and a successful approval response. Explicit outcome, worksite choices, included entries, removal choice, edited overtime and allocation drafts are preserved. Untouched report overtime follows the refreshed stored total. A resolved master is added only when its original entry remains included. Opening a different report and locking access still intentionally reset/clear state.
- Web and Android planners rebase the saved splits together with the new revision only after the administrator accepts **Use refreshed revision**. Their retained whole-day worksite/overtime proposal stays unchanged; omitted allocation categories now show the actual refreshed values the backend will preserve. The retained proposal and explicit split drafts are revalidated against that baseline.
- If refresh completes while the hours editor is open, untouched editor sections adopt the refreshed values. Local typing and explicit AUTO/CUSTOM selections stay intact and are validated against current sites/totals. Refresh never remounts the editor to discard those inputs.
- Confirmation now reflects the effective combination: refreshed saved values for omitted categories, explicit retained values for changed categories, and the effective overtime total. No new backend fields, endpoints, migrations or provider behavior are introduced. The homepage remains queued and unchanged.

**Verification for this correction:** 110 focused frontend cases passed across seven suites, including 15 new regressions. The cases cover untouched custom/automatic transitions, retained custom/AUTO drafts, newly invalid retained drafts, Other link/create, uncertain-result inspection, both planners, open-editor typing, and confirmation versus a mocked backend preserving omitted fields. All 17 changed/new frontend files parsed successfully with the existing Babel/Expo preset; frontend diff checks passed. No backend/MySQL tests were rerun because no backend behavior changed. The earlier 295 backend / 76 MySQL results above remain historical verification of that unchanged implementation, not new executions.

Executed from the frontend repository, with HTTP mocked and no application startup:

```powershell
node node_modules/jest/bin/jest.js --runInBand --watchAll=false --runTestsByPath features/reporting/__tests__/reviewScreen.test.jsx features/reporting/__tests__/reviewModel.test.js features/assignments/__tests__/allocationState.test.js features/assignments/__tests__/hoursEditorModal.test.jsx features/assignments/__tests__/planWeb.test.jsx features/assignments/__tests__/planAndroid.test.jsx features/assignments/__tests__/dailySave.test.js
```

Generated local evidence: backend `target/review-allocation-rebase-tests.log` and `target/review-allocation-rebase-parse.log`. Raw logs/build output remain excluded from review ZIPs. Full browser/native visual QA remains unperformed.

The correction itself changes these nine frontend files and this operating guide; the implementation allowlist at that point contained **23 backend files and 17 frontend files** (four additional tracked frontend files: shared hook, hours editor, editor regressions and Android planner regressions).

- [app/assignments_stack/reports.jsx](<C:/Users/OAB2K/.H/interior-fitout/app/assignments_stack/reports.jsx>)
- [app/assignments_stack/plan.web.jsx](<C:/Users/OAB2K/.H/interior-fitout/app/assignments_stack/plan.web.jsx>)
- [app/assignments_stack/plan.android.jsx](<C:/Users/OAB2K/.H/interior-fitout/app/assignments_stack/plan.android.jsx>)
- [features/assignments/usePlannerAllocations.js](<C:/Users/OAB2K/.H/interior-fitout/features/assignments/usePlannerAllocations.js>)
- [features/assignments/HoursEditorModal.jsx](<C:/Users/OAB2K/.H/interior-fitout/features/assignments/HoursEditorModal.jsx>)
- [features/reporting/__tests__/reviewScreen.test.jsx](<C:/Users/OAB2K/.H/interior-fitout/features/reporting/__tests__/reviewScreen.test.jsx>)
- [features/assignments/__tests__/planWeb.test.jsx](<C:/Users/OAB2K/.H/interior-fitout/features/assignments/__tests__/planWeb.test.jsx>)
- [features/assignments/__tests__/planAndroid.test.jsx](<C:/Users/OAB2K/.H/interior-fitout/features/assignments/__tests__/planAndroid.test.jsx>)
- [features/assignments/__tests__/hoursEditorModal.test.jsx](<C:/Users/OAB2K/.H/interior-fitout/features/assignments/__tests__/hoursEditorModal.test.jsx>)

## Public business page — 26 October 2026

The previously queued page is now implemented locally at **GET `/about`**, following the existing PublicInformationController classpath-resource approach. Intended URL after coordinated deployment: `https://intfitout-backend-production.up.railway.app/about`. It has not been deployed or checked against the public backend.

The Arabic RTL page displays جمال البابا للطراشة والجبصين as the main heading, immediately followed by the English brand name AL-BABA Interiors; both names appear in the HTML title. It retains the supplied interior-finishing description (gypsum work and painting), location in Beitunia, Ramallah, West Bank, Palestine, named contact Osaid Jamal Baba, supplied business phone and email. Telephone/email links are `tel:+970599783048` and `mailto:office.albaba@gmail.com`; policy links use the actual `/privacy` and `/data-deletion` routes. CSS is embedded, responsive and uses system fonts, with no external assets/scripts, dependencies or contact form. No extra address, registration, ownership, certifications, experience, testimonials, projects or Meta-verification claim is added.

This add-on changes only the existing public controller, a new static page, a new standalone controller test and this guide. Existing privacy/deletion HTML, reporting-review/allocation code, configuration and migrations 001–004 remain unchanged. No Railway variable or schema change is needed. The existing coordinated backend/frontend deployment ordering above still applies; this static resource/controller addition ships with the backend after that preparation.

**Focused verification:** offline Maven compilation and **5 standalone MVC cases passed**, with zero failures/errors. Three cases compare exact classpath resource bytes and HTML/UTF-8 responses for unauthenticated GET `/about`, `/privacy`, `/data-deletion`; the others check supplied business/contact/policy content and rejection of POST `/about`. No full application context, startup hook, database, Meta call or message is involved. Static HTML checks passed for Arabic RTL, UTF-8, viewport, responsive CSS, exact contact/internal links and absence of external assets/scripts/forms. Existing working files, old policy resources and migrations were compared byte-for-byte with initial snapshots; application.properties retained its recorded hash. No unrelated suites were rerun. Full browser/device rendering was not run.

Command executed offline from the backend repository (with `MAVEN_SKIP_RC=1`):

```powershell
& 'C:\Program Files\Maven\apache-maven-3.9.9\bin\mvn.cmd' -o -B -ntp '-Dtest=PublicInformationControllerTest' test
```

Generated output stays in ignored `target/about-verification.log`; it is not part of the source manifest. Existing review ZIPs are unchanged historical snapshots and do not include this new page; no new ZIP was requested for this add-on.

Changed files for this add-on:

- [src/main/java/life/work/IntFit/backend/controller/PublicInformationController.java](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/main/java/life/work/IntFit/backend/controller/PublicInformationController.java>)
- [src/main/resources/static/about.html](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/main/resources/static/about.html>)
- [src/test/java/life/work/IntFit/backend/controller/PublicInformationControllerTest.java](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/test/java/life/work/IntFit/backend/controller/PublicInformationControllerTest.java>)
- [docs/whatsapp-report-review.md](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/docs/whatsapp-report-review.md>)

## Exact changed-file manifest

All changes below remain unstaged/uncommitted. Explicit review ZIP allowlist: **26 backend + 17 frontend files**. Pre-existing unrelated items are listed separately.

### Backend

Branch `master`, unchanged HEAD `c997aa76aef7ac38f9c7cf701e2e103272569898`.

| Status | File |
| --- | --- |
| Modified | [docs/whatsapp-reporting-pilot.md](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/docs/whatsapp-reporting-pilot.md>) |
| Modified | [src/main/java/life/work/IntFit/backend/controller/PublicInformationController.java](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/main/java/life/work/IntFit/backend/controller/PublicInformationController.java>) |
| Modified | [src/main/java/life/work/IntFit/backend/controller/WorkAssignmentController.java](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/main/java/life/work/IntFit/backend/controller/WorkAssignmentController.java>) |
| Modified | [src/main/java/life/work/IntFit/backend/dto/daily_assignments/DailyAssignmentsViewDTO.java](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/main/java/life/work/IntFit/backend/dto/daily_assignments/DailyAssignmentsViewDTO.java>) |
| Modified | [src/main/java/life/work/IntFit/backend/dto/daily_assignments/DailyWorkAssignmentDTO.java](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/main/java/life/work/IntFit/backend/dto/daily_assignments/DailyWorkAssignmentDTO.java>) |
| Modified | [src/main/java/life/work/IntFit/backend/reporting/JdbcReportingStore.java](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/main/java/life/work/IntFit/backend/reporting/JdbcReportingStore.java>) |
| Modified | [src/main/java/life/work/IntFit/backend/reporting/ReportingAdminController.java](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/main/java/life/work/IntFit/backend/reporting/ReportingAdminController.java>) |
| Modified | [src/main/java/life/work/IntFit/backend/reporting/ReportingResolutionService.java](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/main/java/life/work/IntFit/backend/reporting/ReportingResolutionService.java>) |
| Modified | [src/main/java/life/work/IntFit/backend/reporting/ReportingStore.java](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/main/java/life/work/IntFit/backend/reporting/ReportingStore.java>) |
| Modified | [src/main/java/life/work/IntFit/backend/service/PayrollService.java](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/main/java/life/work/IntFit/backend/service/PayrollService.java>) |
| Modified | [src/main/java/life/work/IntFit/backend/service/impl/WorkAssignmentServiceImpl.java](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/main/java/life/work/IntFit/backend/service/impl/WorkAssignmentServiceImpl.java>) |
| Modified | [src/test/java/life/work/IntFit/backend/reporting/ReportingMySqlIntegrationTest.java](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/test/java/life/work/IntFit/backend/reporting/ReportingMySqlIntegrationTest.java>) |
| Modified | [src/test/java/life/work/IntFit/backend/service/impl/AssignmentHistoryServiceTest.java](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/test/java/life/work/IntFit/backend/service/impl/AssignmentHistoryServiceTest.java>) |
| Modified | [src/test/java/life/work/IntFit/backend/service/impl/WorkAssignmentAllocationIntegrationTest.java](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/test/java/life/work/IntFit/backend/service/impl/WorkAssignmentAllocationIntegrationTest.java>) |
| Modified | [src/test/java/life/work/IntFit/backend/service/impl/WorkAssignmentSaveRollbackTest.java](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/test/java/life/work/IntFit/backend/service/impl/WorkAssignmentSaveRollbackTest.java>) |
| Modified | [src/test/resources/reporting/mysql-legacy-baseline.sql](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/test/resources/reporting/mysql-legacy-baseline.sql>) |
| New | [docs/sql/005-report-review-coworkers.sql](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/docs/sql/005-report-review-coworkers.sql>) |
| New | [docs/sql/004-whatsapp-report-review.sql](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/docs/sql/004-whatsapp-report-review.sql>) |
| New | [docs/whatsapp-report-review.md](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/docs/whatsapp-report-review.md>) |
| New | [src/main/java/life/work/IntFit/backend/reporting/ReportReviewController.java](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/main/java/life/work/IntFit/backend/reporting/ReportReviewController.java>) |
| New | [src/main/java/life/work/IntFit/backend/reporting/ReportReviewService.java](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/main/java/life/work/IntFit/backend/reporting/ReportReviewService.java>) |
| New | [src/main/java/life/work/IntFit/backend/reporting/ReportingAdminGuard.java](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/main/java/life/work/IntFit/backend/reporting/ReportingAdminGuard.java>) |
| New | [src/main/java/life/work/IntFit/backend/service/AssignmentConcurrency.java](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/main/java/life/work/IntFit/backend/service/AssignmentConcurrency.java>) |
| New | [src/main/resources/static/about.html](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/main/resources/static/about.html>) |
| New | [src/test/java/life/work/IntFit/backend/controller/PublicInformationControllerTest.java](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/test/java/life/work/IntFit/backend/controller/PublicInformationControllerTest.java>) |
| New | [src/test/java/life/work/IntFit/backend/controller/WorkAssignmentRevisionControllerTest.java](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/test/java/life/work/IntFit/backend/controller/WorkAssignmentRevisionControllerTest.java>) |
| New | [src/test/java/life/work/IntFit/backend/reporting/ReportReviewControllerTest.java](<C:/Users/OAB2K/.H/IntFitout Backend/IntFitout-Backend/src/test/java/life/work/IntFit/backend/reporting/ReportReviewControllerTest.java>) |


### Frontend

Branch `master`, unchanged HEAD `69be6eced7c1576109944005d716c3db2860dba8`.

| Status | File |
| --- | --- |
| Modified | [app/assignments_stack/_layout.tsx](<C:/Users/OAB2K/.H/interior-fitout/app/assignments_stack/_layout.tsx>) |
| Modified | [app/assignments_stack/index.jsx](<C:/Users/OAB2K/.H/interior-fitout/app/assignments_stack/index.jsx>) |
| Modified | [app/assignments_stack/plan.android.jsx](<C:/Users/OAB2K/.H/interior-fitout/app/assignments_stack/plan.android.jsx>) |
| Modified | [app/assignments_stack/plan.web.jsx](<C:/Users/OAB2K/.H/interior-fitout/app/assignments_stack/plan.web.jsx>) |
| Modified | [features/assignments/HoursEditorModal.jsx](<C:/Users/OAB2K/.H/interior-fitout/features/assignments/HoursEditorModal.jsx>) |
| Modified | [features/assignments/__tests__/dailySave.test.js](<C:/Users/OAB2K/.H/interior-fitout/features/assignments/__tests__/dailySave.test.js>) |
| Modified | [features/assignments/__tests__/hoursEditorModal.test.jsx](<C:/Users/OAB2K/.H/interior-fitout/features/assignments/__tests__/hoursEditorModal.test.jsx>) |
| Modified | [features/assignments/__tests__/planAndroid.test.jsx](<C:/Users/OAB2K/.H/interior-fitout/features/assignments/__tests__/planAndroid.test.jsx>) |
| Modified | [features/assignments/__tests__/planWeb.test.jsx](<C:/Users/OAB2K/.H/interior-fitout/features/assignments/__tests__/planWeb.test.jsx>) |
| Modified | [features/assignments/dailySave.js](<C:/Users/OAB2K/.H/interior-fitout/features/assignments/dailySave.js>) |
| Modified | [features/assignments/usePlannerAllocations.js](<C:/Users/OAB2K/.H/interior-fitout/features/assignments/usePlannerAllocations.js>) |
| New | [app/assignments_stack/reports.jsx](<C:/Users/OAB2K/.H/interior-fitout/app/assignments_stack/reports.jsx>) |
| New | [features/reporting/__tests__/reviewClient.test.js](<C:/Users/OAB2K/.H/interior-fitout/features/reporting/__tests__/reviewClient.test.js>) |
| New | [features/reporting/__tests__/reviewModel.test.js](<C:/Users/OAB2K/.H/interior-fitout/features/reporting/__tests__/reviewModel.test.js>) |
| New | [features/reporting/__tests__/reviewScreen.test.jsx](<C:/Users/OAB2K/.H/interior-fitout/features/reporting/__tests__/reviewScreen.test.jsx>) |
| New | [features/reporting/reviewClient.js](<C:/Users/OAB2K/.H/interior-fitout/features/reporting/reviewClient.js>) |
| New | [features/reporting/reviewModel.js](<C:/Users/OAB2K/.H/interior-fitout/features/reporting/reviewModel.js>) |


Pre-existing items preserved: backend `src/main/resources/application.properties` remains modified exactly as received (SHA-256 `8E2B078835F168C9ECF15BBC9F40F40B5CB4EEC17A6885102D30833AA37772AF`); frontend `.claude/` remains untracked and untouched. The public `/about` page was added separately as documented above; no unrelated PDF-related files were changed. Migrations 001–004 and the guarded Docker runner were preserved byte-for-byte. No database/configuration or frontend changes were made for this add-on.
