# IntFitout Backend

## 1. Overview

This is the Spring Boot backend for the Interior Fitout system. Among its many responsibilities (worksites, materials, payroll, closeouts, etc.), this document focuses specifically on its **invoice-processing role**, which spans:

- **Final invoice management** — `Invoice`/`InvoiceItem` entities, created via `POST /api/invoices` or `/api/invoices/manual`, queried by worksite/date.
- **Pending invoice workflow** — a staging area (`PendingInvoice`/`PendingInvoiceItem`) for invoices that have been parsed but not yet reviewed/approved.
- **SMS invoice ingestion** — receiving raw SMS payloads from the Android SMS uploader app and routing them by type.
- **Status-message ingestion** — parsing non-invoice SMS text (balances, payments, returns, order confirmations) into a separate `StatusMessage` table.
- **Python converter bridge** — this backend, not Android and not Python itself, is the component that calls the external Python PDF-to-JSON converter and persists its output.
- **Frontend approval workflow (expected, not yet confirmed)** — pending invoices are presumably listed, reviewed, corrected, and confirmed/rejected somewhere in the frontend; the exact mechanics of that are **out of scope for this backend audit** and require a separate frontend audit (see [Section 20](#20-open-frontend-questions)).

## 2. Role in the Larger Invoice Flow

**Invoice flow:**

```
Android SMS Uploader
        │  POST /api/invoices/sms-invoices/upload
        ▼
Backend receives raw SMS payloads, splits by "type"
        │
        │  type == "invoice"
        ▼
Backend calls Python  POST /process-invoice  { "url": content }
        │
        ▼
Python returns parsed invoice JSON
        │
        ▼
Backend saves the result as a PendingInvoice
        │  (via a self-call to its own POST /api/invoices/pending/upload)
        ▼
Frontend lists/reviews pending invoices  (GET /api/invoices/pending)
        │
        ▼
User confirms / deletes / reprocesses pending invoices
```

**Status flow (independent of the invoice flow above):**

```
Android status SMS  (type == "status")
        │
        ▼
Backend's Arabic regex status parser  (PendingInvoiceService.processStatusMessage)
        │
        ▼
StatusMessage table
        │
        ▼
Frontend can list / assign-to-worksite / apply-as-adjustment
   (endpoints exist for this — see Section 6 — actual frontend usage unconfirmed)
```

Important clarifications, stated plainly:

- **Android does not call Python.** It only ever talks to this backend's SMS upload endpoint.
- **Python does not save to this backend.** It returns JSON in its HTTP response and nothing else; it has no knowledge of this backend's database or endpoints.
- **This backend is the bridge** — it is the only component that talks to both Android (as a receiver) and Python (as a caller).
- **Frontend behavior beyond "list pending invoices" is not yet confirmed from this repo.** Whether/how a confirmed pending invoice becomes a final `Invoice`, how worksite correction happens, and how the Android deep link is actually built are all open questions for the frontend audit (Sections 10, 11, 20).

## 3. Backend Project Structure

Files/classes directly involved in the invoice/SMS/Python flow:

| File / Class | Responsibility |
|---|---|
| `controller/InvoiceController.java` | All invoice + pending-invoice HTTP endpoints — SMS upload, pending upload/list/confirm/delete/reprocess, and the separate final-invoice CRUD endpoints. |
| `service/PendingInvoiceService.java` | Core pending-invoice + SMS-routing logic: `processSmsMessages`, `processStatusMessage`, `savePendingInvoice`, `confirmPendingInvoice`, `reprocessUnmatchedInvoices`. |
| `utils/PythonInvoiceProcessor.java` | The only code that talks to the Python converter — `sendInvoiceToPython` (`/process-invoice`) and `reprocessMismatchedInvoices` (`/fix-mismatched`); also the code that self-POSTs results back into this backend's own pending-upload endpoint. |
| `dto/PendingInvoiceDTO.java` | Top-level shape exchanged with Python and with `/api/invoices/pending/upload`; matches Python's camelCase output field-for-field. |
| `dto/PendingInvoiceItemDTO.java` | Item shape; its Java fields are **literally named `unit_price`/`total_price`** (snake_case), matching Python's item casing directly with no alias needed. |
| `dto/SmsMessageDTO.java` | The shape Android's SMS upload payload is deserialized into (`type`, `content`, `receivedAt`). |
| `model/entity/PendingInvoice.java` | JPA entity backing the pending-invoice table; holds a nullable `Worksite` relation, `receivedAtSms`, `totalMatch`, `reprocessedFromId`, etc. |
| `model/entity/PendingInvoiceItem.java` | JPA entity for pending items; also uses literal `unit_price`/`total_price` field names; links to `Material`. |
| `repository/PendingInvoiceRepository.java` | Query methods including the "latest business/SMS datetime" helpers used for deep-link cutoffs, and the unconfirmed+mismatched lookup used by reprocessing. |
| `mapper/PendingInvoiceMapper.java` | MapStruct DTO↔entity mapping; **explicitly ignores the `worksite` relation** on `toEntity` ("set manually on confirmation"). |
| `controller/StatusMessageController.java` | Status-message CRUD, worksite assignment, "apply as negative invoice adjustment," and the latest-status-date endpoint. |
| `model/entity/StatusMessage.java` / `repository/StatusMessageRepository.java` | Status-message storage — fully independent table from `PendingInvoice`, linked to `Worksite` only once manually assigned. |
| `service/InvoiceService.java` | Final-invoice logic — `saveInvoice`/`saveManualInvoice` (includes real worksite + material matching, unlike the pending path), `changeInvoiceWorksite`. |
| `model/entity/Invoice.java` / `model/entity/InvoiceItem.java` | Final invoice/item JPA entities — separate tables from `PendingInvoice`/`PendingInvoiceItem`. |
| `repository/MaterialRepository.java` | `findByName` (exact) and `findByNameIgnoreCase` — both used, by different code paths (see Section 12). |
| `repository/WorksiteRepository.java` | `findByName` (exact match only) — used only by the final-invoice path. |
| `utils/NameCleaner.java` | ICU4J NFKC-based Arabic material-name normalizer with a ~400-entry lookup table; used only by `InvoiceService`, **not** by the pending-invoice path. |
| `config/CorsConfig.java` | Global CORS config — `allowedOrigins("*")` for `GET/POST/PUT/DELETE` across the whole API. |
| `resources/application.properties` | Server/DB/upload config. The **committed** version uses environment variables (`${MYSQLHOST:...}`, `${PORT:...}`, etc.) for deployment portability. No Python-related configuration lives here — the Python base URL is hardcoded in `PythonInvoiceProcessor.java` instead. |

## 4. Android SMS Upload Endpoint

```
POST /api/invoices/sms-invoices/upload
```

**Expected request body** — a JSON array of `SmsMessageDTO`:

```json
[
  {
    "type": "invoice",
    "content": "https://example.com/invoice.pdf",
    "receivedAt": "2026-07-01T10:15:30.123"
  },
  {
    "type": "status",
    "content": "raw status SMS text",
    "receivedAt": "2026-07-01T10:17:01.456"
  }
]
```

This matches the Android SMS uploader's actual payload shape exactly — no wrapper object, no casing mismatch.

- **Accepted fields:** `type` (String), `content` (String), `receivedAt` (String, ISO-8601 local date-time).
- **Invoice/status branching:** `"invoice"`/`"status"` are compared case-insensitively (`equalsIgnoreCase`); everything routes through `PendingInvoiceService.processSmsMessages`.
- **Empty-list behavior:** a `null` or empty array returns `400 Bad Request` with a plain string body.
- **No enum validation for `type`:** confirmed — any value other than `"invoice"`/`"status"` (case-insensitive) is **silently ignored**; no error, no log entry, no rejection.
- **`receivedAt` parsing:** done via an unguarded `LocalDateTime.parse(...)` — works with Android's actual format, but there is **no try/catch**, so a malformed value would throw an unhandled exception for that request.
- **Response behavior:** `200 OK` with an **empty body** as long as the array itself wasn't empty — this is true regardless of whether any individual message inside it actually succeeded downstream.
- **Caller:** the Android SMS uploader app (`InvoiceSMSUploader`) — confirmed as the only known caller.

## 5. Invoice SMS Flow

1. Android sends an `"invoice"`-type SMS entry (its `content` is the raw PDF URL, since Android itself already filtered for `startsWith("http")`).
2. Backend receives it in `uploadSmsMessages` → `processSmsMessages` → recognizes `type == "invoice"`.
3. Backend treats the **entire `content` string as the PDF URL**, with no extraction or validation logic.
4. Backend calls Python: `PythonInvoiceProcessor.sendInvoiceToPython(content, smsReceivedAt)` → `POST https://invoices-convertor-1.onrender.com/process-invoice` with `{"url": content}`.
5. Backend receives Python's parsed invoice JSON, deserialized directly into `PendingInvoiceDTO`.
6. Backend saves it as a pending invoice — by setting `receivedAtSms` on the DTO and POSTing it (wrapped in a list) to its own `/api/invoices/pending/upload` endpoint.
7. The invoice is now sitting in the `PendingInvoice` table, presumably to be reviewed later in the frontend.

**Risks confirmed in this flow:**
- **No URL validation** — the backend never checks that `content` is a well-formed or reachable URL, or that it points to an actual invoice PDF, before handing it to Python.
- **Python failure is swallowed** — any exception (Python down, non-2xx, timeout, JSON mapping failure) is caught by a blanket `try/catch` in `PythonInvoiceProcessor` and only `System.err`-logged; nothing is retried or queued.
- **No retry/dead-letter queue exists** — confirmed by the absence of any such mechanism anywhere in this code path.
- **Confirmed: Android receives `200 OK` from `/sms-invoices/upload` even when the individual Python call fails downstream**, because `processSmsMessages` has no return value and `uploadSmsMessages` doesn't inspect per-message outcomes — the HTTP response only reflects "the array wasn't empty," not "every message was successfully processed."

## 6. Status SMS Flow

- **`type == "status"` behavior:** routed to `PendingInvoiceService.processStatusMessage(message)`.
- **Arabic regex/status parsing:** `classifyStatusType(content)` matches on literal Arabic substrings — `"رصيدكم لغاية"` → `BALANCE_AT_DATE`, `"اصدار طلبيه"` → `ORDER_ISSUED`, `"مرتجع بضاعة"` → `RETURN`, `"شكرا لسداد"` → `PAYMENT`; anything else → `UNKNOWN`. Numeric amounts are extracted via marker-based regex (e.g., after `"بمبلغ"`, `"رصيدكم"`, `"هو"`), and dates for `BALANCE_AT_DATE` via a `d/M/yyyy` pattern.
- **`StatusMessage` storage:** each parsed message is saved as its own row (`content`, `receivedAt`, `amount`, `totalOwed`, `statusType`, `balanceDate`) — fully independent of the `PendingInvoice` table.
- **Worksite assignment behavior:** `StatusMessage.worksite` starts `null`; `PATCH /api/status-messages/{id}/assign/{worksiteId}` and `/unassign` exist to set/clear it manually.
- **Apply-as-adjustment behavior:** `POST /api/status-messages/{id}/apply` — currently supports only `RETURN`-type messages, and **does** call `InvoiceService.saveInvoice` directly to create a real negative-amount final `Invoice` (with real worksite/material matching, since it goes through the final-invoice path), then marks the `StatusMessage` as applied.
- **Latest status timestamp endpoint:** `GET /api/status-messages/latest-saved-date` — returns the most recent `StatusMessage.receivedAt`.
- **Relation to Android's deep-link cutoff:** this is the most likely backend source for Android's `lastStatusAt` deep-link parameter, since it's the only endpoint that reads the raw `StatusMessage.receivedAt` timestamp directly (see Section 14).

## 7. Python Converter Integration

**Python endpoints used by this backend:**
- `POST /process-invoice` — normal invoice parsing.
- `POST /fix-mismatched` — reprocessing.

**Python base URL:** `https://invoices-convertor-1.onrender.com` — **hardcoded** as a string literal directly in `PythonInvoiceProcessor.java` (two separate literals, one per endpoint). Not read from `application.properties` or any environment variable.

**Request body for `/process-invoice`:** `{"url": "<content>"}` — `originalId` is omitted entirely for this call.

**Request body for `/fix-mismatched`:** `{"url": "<pendingInvoice.pdfUrl>", "originalId": <pendingInvoice.id>}`.

**Expected response shape:** deserialized directly into `PendingInvoiceDTO` via Jackson defaults (no custom `ObjectMapper`/naming strategy anywhere in this project) — top-level camelCase field names, item-level literal `unit_price`/`total_price` field names.

**Timeout behavior:** none explicitly configured — a plain `new RestTemplate()` with default settings is used, so calls can block for however long the underlying HTTP client's defaults allow.

**Error handling:** a single blanket `try/catch (Exception e)` wraps each call; failures are logged to `System.err` only and otherwise silently dropped — no exception propagates back to the original SMS-upload HTTP response.

**Does the backend expect Python to save anything?** No — the backend always persists the result itself afterward (see Section 8). Python is treated as a stateless transform step.

**Does backend field casing match Python's output?** Yes, currently — confirmed compatible. `PendingInvoiceDTO`'s top-level fields are plain camelCase Java fields matching Python's top-level output, and `PendingInvoiceItemDTO`'s `unit_price`/`total_price` fields are literally named in snake_case in Java, matching Python's item-level output with no `@JsonProperty` alias required.

## 8. Pending Invoice Persistence

```
POST /api/invoices/pending/upload
```

- **Why it still exists:** this is the backend's actual persistence mechanism for parsed pending invoices. It is **not legacy** — it is actively load-bearing today.
- **Who calls it today:** the backend calls **itself**, over HTTP, from `PythonInvoiceProcessor` (both after a normal `/process-invoice` call and after a `/fix-mismatched` reprocess call). **Python no longer calls this endpoint directly** — that was true in an earlier version of the system but is not the current behavior.
- **How `PendingInvoiceService.savePendingInvoice` works:** maps the incoming DTO to a `PendingInvoice` entity, explicitly sets `date`, `confirmed = false`, `receivedAtSms`, and `reprocessedFromId`; for each item, resolves or creates a `Material` by description (see Section 12), then saves the whole aggregate via `PendingInvoiceRepository.save(...)`.
- **`confirmed` behavior:** always forced to `false` on save, regardless of whatever value was in the incoming DTO.
- **`receivedAtSms` behavior:** carried through explicitly from the caller (either the original SMS timestamp on first save, or copied forward from the original invoice during reprocessing) so the deep-link cutoff logic stays accurate even after a reprocess.
- **`parsedAt` behavior:** currently trusts whatever value came from the mapped DTO (there's a commented-out line that would have forced it to "now" — that override is not active).
- **`totalMatch` behavior:** carried through as-is from Python's computed value.
- **`reprocessedFromId` behavior:** set explicitly from the DTO — links a reprocessed result back to the original pending invoice's ID.
- **Item persistence behavior:** each item's `Material` is resolved/created by raw description match (not by the incoming `materialId`, which is ignored); items are attached to the new `PendingInvoice` and saved together via cascade.

## 9. Pending Invoice API

| Endpoint | Purpose | Likely frontend caller | Notable behavior |
|---|---|---|---|
| `GET /api/invoices/pending` | List all pending invoices | Frontend pending-invoice review screen | Eager-loads items + materials via a `JOIN FETCH` query |
| `PATCH /api/invoices/pending/{id}/confirm` | Mark a pending invoice confirmed | Frontend "confirm" action | **Only sets `confirmed = true`** on the same row — see Section 10 |
| `DELETE /api/invoices/pending/{id}` | Remove a pending invoice | Frontend "reject/delete" action | Hard delete, no soft-delete/audit trail |
| `POST /api/invoices/pending/fix-unmatched` | Trigger reprocessing of unconfirmed+mismatched invoices via Python | Frontend "reprocess" action (button or scheduled — unconfirmed) | See Section 13 for the duplicate-risk caveat |
| `GET /api/invoices/pending/latest-date` | Legacy: latest business date among pending invoices | Possibly deep-link building | Date-only |
| `GET /api/invoices/pending/latest-business-datetime?onlyUnconfirmed=` | Latest business datetime among pending invoices | Possibly deep-link building | Business/parse time, not SMS-received time |
| `GET /api/invoices/pending/latest-business-date?onlyUnconfirmed=` | Same, date-only | Possibly deep-link building | |
| `GET /api/invoices/pending/latest-sms-datetime?onlyUnconfirmed=` | Latest **real SMS-received** timestamp among pending invoices | Most likely source of Android's `lastPendingAt` | Reads `receivedAtSms`, not `date`/`parsedAt` |

## 10. Confirmation and Final Invoice Gap

This is an important, deliberately-cautious finding:

- **Confirming a pending invoice currently only flips `confirmed = true`** on the existing `PendingInvoice` row (`PendingInvoiceService.confirmPendingInvoice`). It updates nothing else and creates nothing else.
- **This backend audit did not find any backend code path that converts a confirmed `PendingInvoice` into a final `Invoice`.** No service method, no event listener, and no scheduled job in this codebase reads confirmed `PendingInvoice` rows and creates corresponding `Invoice`/`InvoiceItem` rows.
- **Final `Invoice` creation exists only through separate endpoints** — `POST /api/invoices` and `POST /api/invoices/manual` (`InvoiceService.saveInvoice`/`saveManualInvoice`) — which are not called from anywhere in the pending-invoice code.
- **This does not necessarily mean the feature is broken.** It's entirely possible the frontend calls `POST /api/invoices` (or `/manual`) itself, using the reviewed/edited pending-invoice data, immediately after or instead of calling the confirm endpoint. **This has not been confirmed either way** — it requires the frontend audit.
- **Until the frontend is checked, whether "confirm" alone is suffic to finalize an invoice, or whether a second explicit step is required, is an open architectural question** — not a confirmed bug.

## 11. Worksite Matching

- **The pending-invoice path does not resolve the worksite relation at all.** `PendingInvoiceMapper.toEntity` has `@Mapping(target = "worksite", ignore = true)`, with the code comment "set manually on confirmation."
- **`worksiteName` is stored as a plain string** on `PendingInvoice.worksiteName` — no lookup, no normalization, no matching against the `Worksite` table happens during save.
- **`worksiteId`/the `worksite` relation stays `null`** on every pending invoice saved through this flow, regardless of what Python's `worksiteName` guess was (including the literal fallback value `"other"`).
- **The final-invoice path (`InvoiceService.saveInvoice`) has separate, real worksite-matching logic**: exact match (after whitespace-collapse + lowercase normalization) against `WorksiteRepository.findByName`, auto-creating a new `Worksite` if no match is found. This logic is never invoked for pending invoices.
- **No pending-invoice worksite-correction endpoint was found.** The only worksite-setting endpoints in this codebase operate on the final `Invoice` entity (`PUT /api/invoices/{id}/worksite[/{worksiteId}]`) or on `StatusMessage` (`PATCH /api/status-messages/{id}/assign/{worksiteId}`) — neither applies to `PendingInvoice`.
- **The frontend audit needs to confirm how users are actually meant to correct/assign a worksite for a pending invoice** — whether that happens implicitly as part of a "confirm + create final invoice" step the frontend performs, or whether this is a genuine gap.

## 12. Material / Item Matching

- **Pending-invoice item matching** (`PendingInvoiceService.savePendingInvoice`): looks up `Material` by the **raw, uncleaned** item `description`, case-insensitively (`materialRepository.findByNameIgnoreCase(...)`).
- **Auto-create behavior:** confirmed — if no match is found, a new `Material` row is created using that same raw, uncleaned description as its name.
- **`materialId` is ignored:** confirmed — the incoming DTO's `materialId` field (which Python always sends as `null` anyway) is never read for resolution; matching is always by description text.
- **The final-invoice path (`InvoiceService.saveInvoice`) uses `NameCleaner.clean(...)` first** — an ICU4J NFKC-normalizing, ~400-entry Arabic name-standardization lookup — before matching via `MaterialRepository.findByName` (exact, case-sensitive, since the input is already cleaned/standardized).
- **Possible material duplication/inconsistency:** since these two paths normalize differently (raw vs. cleaned) and match differently (case-insensitive-raw vs. exact-cleaned), the same real-world material item can plausibly end up as **two different rows** in the `Material` table depending on whether it arrived via the SMS/pending pipeline or the final-invoice/manual pipeline. Nothing in this codebase reconciles the two.

## 13. Reprocess / Fix-Mismatched Flow

```
POST /api/invoices/pending/fix-unmatched
```

1. Backend selects candidate pending invoices via `PendingInvoiceRepository.findByConfirmedFalseAndTotalMatchFalse()`, then filters to those with a non-empty `pdfUrl` and a `null` `reprocessedFromId`.
2. Backend calls Python `POST /fix-mismatched` for each candidate, with request body `{"url": pdfUrl, "originalId": <pending invoice's own id>}`.
3. The JSON Python returns is saved as a **new** pending invoice, via the same self-POST to `/api/invoices/pending/upload` used elsewhere.
4. **The old pending invoice is not deleted or updated** — it remains exactly as it was in the database.
5. **`reprocessedFromId`** on the new row links it back to the original invoice's ID — informational only, nothing reads it to suppress future reprocessing of the original.
6. **Confirmed duplicate risk:** because the original invoice's own `reprocessedFromId` stays `null` forever (it's the source, not a result), it will continue to match the `reprocessedFromId == null` filter on every subsequent call to this endpoint — meaning **repeated calls to `/api/invoices/pending/fix-unmatched` will keep re-sending the same still-mismatched original invoices to Python and creating a new duplicate pending invoice each time**, with no cleanup of earlier attempts.
7. **The frontend audit needs to confirm how/when this endpoint is actually triggered** — a one-off admin action, a button a user can click repeatedly, or something scheduled — since that materially affects how quickly the duplicate-accumulation risk above becomes a real problem in practice.

## 14. Deep-Link Timestamp Support

Backend endpoints that most likely support the Android SMS uploader's deep-link generation (`baba.intfit.sms_uploader://extract?from=...&to=...&lastPendingAt=...&lastStatusAt=...`):

- `GET /api/invoices/pending/latest-business-date` / `-business-datetime` — business/parse-time based.
- **`GET /api/invoices/pending/latest-sms-datetime?onlyUnconfirmed=`** — reads the real `receivedAtSms` field; the most likely direct source of the `lastPendingAt` deep-link parameter.
- `GET /api/status-messages/latest-saved-date` — reads `StatusMessage.receivedAt`; the most likely source of `lastStatusAt`.

Important clarifications:
- **This backend does not appear to build the deep-link URI string itself** — confirmed by an exhaustive search for the URI scheme/host anywhere in this codebase (no matches). It only exposes the raw timestamp values.
- **Something else (most likely the frontend) is presumably responsible for assembling the actual deep link** using these values — this needs frontend-side confirmation.
- **Pending-invoice and status-message timestamps are tracked completely separately** (`PendingInvoice.receivedAtSms` vs. `StatusMessage.receivedAt`), in separate tables with separate query methods — there is no shared "last SMS timestamp" concept spanning both.
- **Timezone-naive `LocalDateTime` risk:** all of this timestamp handling uses plain `LocalDateTime`, with no explicit timezone/`ZoneId` conversion anywhere in this flow. This isn't a confirmed bug, but it's a real, unverified assumption that the device and server agree on a timezone convention.

## 15. Field Contract Summary

**Android → Backend** (`POST /api/invoices/sms-invoices/upload`):
```json
[
  { "type": "invoice" | "status", "content": "...", "receivedAt": "..." }
]
```

**Backend → Python** (`POST /process-invoice`, and `/fix-mismatched` with `originalId` populated):
```json
{ "url": "...", "originalId": null }
```

**Python → Backend** (deserialized into `PendingInvoiceDTO`):

Top-level, camelCase:
```
id, date, netTotal, total, worksiteName, worksiteId, items, totalMatch, pdfUrl, confirmed, parsedAt, reprocessedFromId
```

Item-level, snake_case:
```
description, quantity, unit_price, total_price, materialId
```

**This backend currently supports Python's exact mixed-casing contract, including the snake_case `unit_price`/`total_price` item fields** — the Java DTO fields are literally named that way, so no Jackson alias/naming-strategy configuration is needed for this to work.

## 16. Error Handling / Failure Modes

| Scenario | Confirmed behavior |
|---|---|
| Empty Android SMS list | `400 Bad Request`, plain string body |
| Unknown `type` value | Silently ignored — no error, no log |
| Malformed `receivedAt` | Unguarded `LocalDateTime.parse(...)` throws unhandled — likely fails the whole request |
| Invalid invoice URL (well-formed but not a real invoice) | No validation; passed straight to Python, which will fail on its own and have that failure swallowed here |
| Python unavailable | Caught by a blanket `try/catch`, logged to `System.err`, silently dropped |
| Python returns 400/500 | Same blanket catch — `RestTemplate` throws on non-2xx, caught, logged, dropped |
| Backend's self-save call to its own `/pending/upload` fails | Also caught by the same outer `try/catch` in `PythonInvoiceProcessor` — silently dropped |
| Duplicate SMS upload (same content sent twice) | **No server-side dedup exists** — both `PendingInvoice` and `StatusMessage` rows are created again independently |
| `totalMatch = false` returned from Python | Saved as-is; the invoice becomes eligible for `/pending/fix-unmatched` reprocessing |
| Reprocess duplicate behavior | Confirmed — see Section 13; repeated reprocessing accumulates duplicate rows |
| Confirming an invalid/incomplete pending invoice | Only blocked if already `confirmed = true`; no check on data completeness (missing worksite, `totalMatch = false`, etc.) |

**Risks/unknowns (not directly confirmable from code alone):** real-world frequency of malformed `receivedAt` values from Android; actual timezone behavior in production; whether `/pending/fix-unmatched` is triggered often enough for the duplicate-accumulation risk to matter in practice.

## 17. Configuration / Security Notes

- **Python converter URL:** hardcoded string literals in `PythonInvoiceProcessor.java` — not sourced from `application.properties` or any environment variable.
- **Backend self-save URL:** also hardcoded in the same file, pointing at this backend's own production URL — meaning even a local/dev backend instance calling this code path would round-trip through the production save endpoint unless manually changed.
- **Hardcoded URL concern:** both of the above are a legitimate configuration fragility — there's no single place to change environments (dev/staging/prod) for the Python integration.
- **Committed `application.properties`** uses environment-variable-driven configuration for the database, port, and upload paths (e.g., `${MYSQLHOST:localhost}`, `${PORT:8080}`, `${APP_UPLOADS_ROOT:uploads}`) — this is the properly portable, deployable configuration.
- **Local/machine-specific `application.properties` changes:** this working copy may contain local, machine-specific, or otherwise sensitive values that differ from the committed version (per the project owner's note that this file is edited across multiple personal machines). These local values were **not modified, staged, or unstaged** as part of this documentation update, and are **not treated as part of the invoice feature** unless they affect documented runtime behavior — they don't. Care should be taken not to accidentally commit machine-specific or sensitive local changes to this file.
- **Endpoints appear unauthenticated:** confirmed — no Spring Security filter chain is active in this project (the security starter dependency is present in `pom.xml` but commented out), and no endpoint in the invoice/SMS/Python flow has any auth annotation or header check.
- **CORS is fully open:** confirmed — `CorsConfig` registers `allowedOrigins("*")` for `GET/POST/PUT/DELETE` across the entire API.
- **`/api/invoices/pending/upload` is public and load-bearing:** confirmed — it has no additional protection despite being the backend's actual persistence mechanism for pending invoices (called both externally, in principle, and internally by the backend itself).
- **No API key or auth exists between this backend and the Python converter** in either direction — confirmed; the Python service itself also requires none (per its own audit).

## 18. Tests

- **Existing tests:** exactly one — the default Spring Boot–generated `IntFitBackendApplicationTests.contextLoads()`, which only verifies the application context starts. No assertions about invoice/SMS/Python behavior exist.
- **Missing:** tests for the SMS upload endpoint and `SmsMessageDTO` mapping; tests for the Python integration (`PythonInvoiceProcessor`, even with a mocked `RestTemplate`); tests for pending-invoice save (`savePendingInvoice`); tests for the reprocess/fix-mismatched flow (which would have caught the duplicate-reprocessing behavior in Section 13); tests for pending→final invoice behavior (there being none to test is itself notable, see Section 10); tests for field-casing compatibility between Python and the DTOs; tests for worksite matching (`normalizeWorksiteName`, `findByName`); tests for material matching (`NameCleaner`, `findByNameIgnoreCase` vs. `findByName`).

## 19. Known Risks / Technical Debt

| Issue | Category |
|---|---|
| `PythonInvoiceProcessor` calls this backend's own production save endpoint over HTTP (self-POST) instead of an in-process call | **Architecture risk** |
| No backend code path found converting a confirmed `PendingInvoice` into a final `Invoice` | **Unknown, pending frontend audit** |
| No worksite matching/resolution happens anywhere in the pending-invoice save path | **Missing validation** |
| Repeated calls to `/api/invoices/pending/fix-unmatched` create duplicate pending invoices without cleaning up originals | **Confirmed bug** |
| Material matching differs between the pending path (raw, case-insensitive) and the final-invoice path (`NameCleaner`-normalized, exact) | **Maintainability issue** |
| No server-side deduplication of repeated/duplicate SMS uploads | **Missing validation** |
| No validation that SMS `content` is a well-formed or reachable URL before calling Python | **Missing validation** |
| Python/self-save failures are caught by a blanket `try/catch` and only logged — never surfaced, retried, or queued | **Weak error handling** |
| Python base URL and backend self-save URL are both hardcoded string literals | **Security/config concern** |
| All invoice/SMS endpoints are unauthenticated with fully open CORS | **Security/config concern** |
| Only one trivial context-load test exists for the entire application | **Maintainability issue** |

## 20. Open Frontend Questions

1. Who builds the Android deep link (`baba.intfit.sms_uploader://extract?...`) — is it the frontend, using the `latest-sms-datetime`/`latest-saved-date` endpoints documented in Section 14?
2. Which screen lists pending invoices, and does it call `GET /api/invoices/pending` directly?
3. How are pending invoices edited before confirmation — is there a PUT/PATCH endpoint expected that doesn't currently exist for `PendingInvoice`?
4. What does the "confirm" button in the frontend actually do — does it call only `PATCH /api/invoices/pending/{id}/confirm`, or does it also call `POST /api/invoices` (or `/manual`) to create the final invoice?
5. If it calls both, in what order, and what happens if the second call fails after the first succeeds?
6. How is a worksite assigned or corrected for a pending invoice, given no backend endpoint for that currently exists?
7. How does the frontend handle material matching/editing for pending-invoice items — does it expose the raw, uncleaned material names created by the pending-invoice path?
8. How is `totalMatch = false` surfaced to the user, and does the frontend distinguish it from a fully-matched invoice?
9. When and how is `POST /api/invoices/pending/fix-unmatched` triggered — user-initiated per invoice, a global button, or scheduled?
10. How are status messages shown and applied in the frontend — does it use `assign`/`unassign`/`apply` as implemented, or expect additional behavior?
11. What exact response shapes does the frontend expect from `GET /api/invoices/pending` and related endpoints — does it match the camelCase/snake_case mix documented in Section 15?

## 21. Quick Reference

- **Android upload endpoint:** `POST /api/invoices/sms-invoices/upload`
- **Python endpoints called by this backend:** `POST /process-invoice`, `POST /fix-mismatched` (base URL hardcoded in `PythonInvoiceProcessor.java`)
- **Pending invoice persistence endpoint:** `POST /api/invoices/pending/upload` (self-called by this backend, not by Python)
- **Pending invoice management:** `GET /api/invoices/pending`, `PATCH /api/invoices/pending/{id}/confirm`, `DELETE /api/invoices/pending/{id}`, `POST /api/invoices/pending/fix-unmatched`
- **Deep-link timestamp helpers:** `GET /api/invoices/pending/latest-sms-datetime`, `GET /api/invoices/pending/latest-business-datetime`, `GET /api/status-messages/latest-saved-date`
- **Status message endpoints:** `GET /api/status-messages`, `GET /api/status-messages/latest-20`, `GET /api/status-messages/unassigned`, `GET /api/status-messages/by-worksite/{worksiteId}`, `PATCH /api/status-messages/{id}/assign/{worksiteId}`, `PATCH /api/status-messages/{id}/unassign`, `POST /api/status-messages/{id}/apply`, `GET /api/status-messages/since-latest-balance`, `DELETE /api/status-messages/{id}`

**Key mental model:**
Android sends raw SMS. Backend calls Python. Python returns JSON. Backend saves the result as a pending invoice (by calling itself). Frontend approval/finalization into a real invoice still needs verification.
