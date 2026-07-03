# IntFitout Backend

## 1. Overview

This is the Spring Boot backend for the Interior Fitout system. Among its many responsibilities (worksites, materials, payroll, closeouts, etc.), this document focuses specifically on its **invoice-processing role**, which spans:

- **Final invoice management** — `Invoice`/`InvoiceItem` entities. Created either directly via `POST /api/invoices` / `/api/invoices/manual`, or — as of the atomic finalize endpoint — derived from a `PendingInvoice` via `POST /api/invoices/pending/{id}/finalize`. Queried by worksite/date.
- **Pending invoice workflow** — a staging area (`PendingInvoice`/`PendingInvoiceItem`) for invoices that have been parsed but not yet reviewed/approved, with an **atomic finalize endpoint** that converts a pending invoice into a confirmed final invoice in one transaction (see Section 10).
- **SMS invoice ingestion** — receiving raw SMS payloads from the Android SMS uploader app and routing them by type.
- **Status-message ingestion** — parsing non-invoice SMS text (balances, payments, returns, order confirmations) into a separate `StatusMessage` table.
- **Python converter bridge** — this backend, not Android and not Python itself, is the component that calls the external Python PDF-to-JSON converter and persists its output.
- **Frontend approval workflow** — the frontend lists/reviews pending invoices and, per the frontend team, now calls this backend's `POST /api/invoices/pending/{id}/finalize` to confirm one. See Section 10 for what this endpoint guarantees.

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
        │  (in-process, via PendingInvoiceService — see Section 8)
        ▼
Frontend lists/reviews pending invoices  (GET /api/invoices/pending)
        │
        ▼
User confirms a pending invoice
        │  POST /api/invoices/pending/{id}/finalize
        ▼
Backend atomically creates the final Invoice AND marks the
PendingInvoice confirmed = true, in one transaction  (see Section 10)
        │
        ▼
(user may also delete / reprocess pending invoices via the other endpoints)
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
- **The pending → final invoice path is now a solved, atomic backend operation** (`POST /api/invoices/pending/{id}/finalize`, Section 10) — this was previously an open question in this document; it no longer is, regardless of exactly how/when the frontend calls it. How worksite correction happens and how the Android deep link is actually built remain frontend-side details (Sections 11, 20).

## 3. Backend Project Structure

Files/classes directly involved in the invoice/SMS/Python flow:

| File / Class | Responsibility |
|---|---|
| `controller/InvoiceController.java` | All invoice + pending-invoice HTTP endpoints — SMS upload, pending upload/list/confirm/**finalize**/delete/reprocess, and the separate final-invoice CRUD endpoints. |
| `service/PendingInvoiceService.java` | Core pending-invoice + SMS-routing logic: `processSmsMessages`, `processStatusMessage`, `savePendingInvoice`, `confirmPendingInvoice`, **`finalizePendingInvoice`** (atomic pending → final, Section 10), `reprocessUnmatchedInvoices`. |
| `utils/PythonInvoiceProcessor.java` | The only code that talks to the Python converter — `sendInvoiceToPython` (`/process-invoice`) and `reprocessMismatchedInvoices` (`/fix-mismatched`); saves the parsed result **in-process** via `PendingInvoiceService.savePendingInvoice(...)` (no longer a self-POST — see Section 8). |
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
- **No enum validation for `type`:** any value other than `"invoice"`/`"status"` (case-insensitive) is skipped, but is no longer silent — it's counted in `unknownTypeMessages` and persisted as a `failed_invoice_imports` row with `failureReason = UNKNOWN_MESSAGE_TYPE` (see Section 5).
- **`receivedAt` parsing:** for `"invoice"` messages, a malformed value is now caught and recorded (`failureReason = INVALID_RECEIVED_AT`, counted in `invoicesFailed`) instead of throwing unhandled. For `"status"` messages, the parse is wrapped in a per-message `try/catch` that counts `statusFailed` (but does not persist a `failed_invoice_imports` row — that table is invoice-import scoped, see Section 5). Either way, one bad message no longer aborts the rest of the batch.
- **Response behavior:** `200 OK` with a **`SmsProcessingSummaryDTO`** body as long as the array itself wasn't empty — the response now reflects per-message outcomes instead of being an empty body:

```json
{
  "totalMessages": 5,
  "invoiceMessages": 3,
  "statusMessages": 1,
  "unknownTypeMessages": 1,
  "invoicesProcessed": 1,
  "invoicesSkippedInvalidUrl": 1,
  "invoicesFailed": 1,
  "statusProcessed": 1,
  "statusFailed": 0,
  "failedImportsRecorded": 3
}
```

`failedImportsRecorded` counts how many `failed_invoice_imports` rows were actually written for this batch (see Section 5).

- **Caller:** the Android SMS uploader app (`InvoiceSMSUploader`) — confirmed as the only known caller.

## 5. Invoice SMS Flow

1. Android sends an `"invoice"`-type SMS entry (its `content` is the raw PDF URL, since Android itself already filtered for `startsWith("http")`).
2. Backend receives it in `uploadSmsMessages` → `processSmsMessages` → recognizes `type == "invoice"`.
3. **Backend validates the URL** (`PendingInvoiceService.isValidInvoiceUrl`) before doing anything else: `content` must be non-blank and parse as a URI with an `http`/`https` scheme and a non-blank host. If it doesn't, Python is **never called** — the message is skipped, counted in `invoicesSkippedInvalidUrl`, and persisted as a `failed_invoice_imports` row with `failureReason = INVALID_INVOICE_URL` (see "Failed Invoice Import Persistence" below). This is a basic sanity check, not a full SSRF/private-IP blocker.
4. If the URL is valid, the backend parses `receivedAt` into `smsReceivedAt`. If that parse fails, the message is skipped, counted in `invoicesFailed`, and persisted with `failureReason = INVALID_RECEIVED_AT`.
5. Backend calls Python: `PythonInvoiceProcessor.sendInvoiceToPython(content, smsReceivedAt)` → `POST https://invoices-convertor-1.onrender.com/process-invoice` with `{"url": content}`.
6. Backend receives Python's parsed invoice JSON, deserialized directly into `PendingInvoiceDTO`.
7. Backend saves it as a pending invoice **in-process**, via `PendingInvoiceService.savePendingInvoice(...)` (this used to be a self-POST to `/api/invoices/pending/upload` — see Section 8) — counted in `invoicesProcessed`.
8. If Python returns a non-2xx status, a null body, or the in-process save throws, the message is counted in `invoicesFailed` and persisted with `failureReason = PYTHON_PROCESSING_FAILED`.
9. The invoice is now sitting in the `PendingInvoice` table, to be reviewed later in the frontend.

**Current behavior for this flow:**
- **URL validation now happens before Python is called** — invalid/blank/non-http(s)/hostless URLs never reach Python (step 3 above). This was previously a confirmed gap; it is fixed.
- **Failures are now counted and visible, not silently swallowed:** every batch to `/api/invoices/sms-invoices/upload` returns a `SmsProcessingSummaryDTO` (Section 4) with per-outcome counts, and individual invoice-import failures are additionally persisted for later review (below).
- **Still a real limitation:** an unreachable/failing Python service, or a save failure, still only produces a counted+persisted `PYTHON_PROCESSING_FAILED` record — there is still no automatic retry or dead-letter re-queueing; a failed import must be manually re-submitted (e.g. by re-sending the same SMS payload).

### Failed Invoice Import Persistence

When an `"invoice"`-type SMS cannot become a `PendingInvoice` (steps 3, 4, or 8 above), a row is saved to the **`failed_invoice_imports`** table (`FailedInvoiceImport` entity / `FailedInvoiceImportRepository`), so the failure is reviewable later instead of only appearing in logs or the upload response.

- **`failureReason`** — one of:
  - `INVALID_INVOICE_URL` — blank/malformed/non-http(s)/hostless content.
  - `INVALID_RECEIVED_AT` — `receivedAt` failed to parse.
  - `PYTHON_PROCESSING_FAILED` — Python returned a non-2xx status/null body, or the in-process save threw.
  - `UNKNOWN_MESSAGE_TYPE` — `type` wasn't `"invoice"` or `"status"` (Section 4).
- **`contentPreview`** — a truncated (≤512 char), trimmed preview of the original SMS content, not the full raw text — deliberately bounded and less exposed than storing the complete message.
- **`urlHost`** — the host parsed from `content`, when parseable, to help identify the source at a glance without needing the full URL.
- **`errorMessage`** — a truncated (≤1000 char) exception/detail message, when available.
- **`createdAt`** — set automatically on insert.
- **Review endpoint:** `GET /api/invoices/sms-import-failures?limit=50` — returns recent failures, newest first (`limit` is clamped server-side to a sane range).
- **Status-message failures are NOT persisted here** — this table is scoped to invoice-import failures only; a failed status message is only counted (`statusFailed`), not recorded as a row.

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

**Error handling:** a single blanket `try/catch (Exception e)` wraps each call in `PythonInvoiceProcessor`; `sendInvoiceToPython` returns `false` on any failure (non-2xx, null body, exception) instead of throwing. The **caller** (`PendingInvoiceService.processSmsMessages`) now counts that failure (`invoicesFailed`) and persists it (`failureReason = PYTHON_PROCESSING_FAILED`, Section 5) — so it's no longer silent. It still doesn't propagate as an HTTP error, though: `/api/invoices/sms-invoices/upload` still returns `200 OK`, with the failure reflected only in the `SmsProcessingSummaryDTO` body and the `failed_invoice_imports` table.

**Does the backend expect Python to save anything?** No — the backend always persists the result itself afterward (see Section 8). Python is treated as a stateless transform step.

**Does backend field casing match Python's output?** Yes, currently — confirmed compatible. `PendingInvoiceDTO`'s top-level fields are plain camelCase Java fields matching Python's top-level output, and `PendingInvoiceItemDTO`'s `unit_price`/`total_price` fields are literally named in snake_case in Java, matching Python's item-level output with no `@JsonProperty` alias required.

## 8. Pending Invoice Persistence

**This section previously described a self-POST pattern (the backend calling its own production URL over HTTP to save a pending invoice). That has been replaced with a direct in-process call; the old endpoint is unchanged and still available.**

### Current behavior

`PythonInvoiceProcessor` (both `sendInvoiceToPython` and `reprocessMismatchedInvoices`) now saves a parsed `PendingInvoiceDTO` by calling **`PendingInvoiceService.savePendingInvoice(...)` directly, in-process** — no HTTP call, no network round-trip, and no dependency on this backend's own public URL being reachable from itself. The hardcoded self-save URL that used to exist for this no longer exists in the code.

```
POST /api/invoices/pending/upload
```

- **Still available, for backward compatibility / manual use.** `InvoiceController.uploadPendingInvoices` → `PendingInvoiceService.savePendingInvoices(...)` still exists and still works exactly as before — it's simply no longer on the hot path for SMS-driven invoice ingestion (that path is now in-process, above). Anything that still calls this endpoint directly (manual testing, an older client, etc.) is unaffected. **Python no longer calls this endpoint directly either** — that was true in an earlier version of the system but is not, and has not been for a while, the current behavior.
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
| `PATCH /api/invoices/pending/{id}/confirm` | Mark a pending invoice confirmed | Backward-compatible only — **not** the current frontend confirm path | **Only sets `confirmed = true`** on the same row; does **not** create a final invoice — see Section 10 |
| `POST /api/invoices/pending/{id}/finalize` | **Atomically** create the final invoice from a pending invoice AND mark it confirmed | **The current frontend "confirm" action** (via `finalizePendingInvoice(id)`) | One transaction; see Section 10 for the full contract |
| `DELETE /api/invoices/pending/{id}` | Remove a pending invoice | Frontend "reject/delete" action | Hard delete, no soft-delete/audit trail |
| `POST /api/invoices/pending/fix-unmatched` | Trigger reprocessing of unconfirmed+mismatched invoices via Python | Frontend "reprocess" action (button or scheduled — unconfirmed) | Skips originals that already have a reprocessed child — see Section 13 |
| `GET /api/invoices/pending/latest-date` | Legacy: latest business date among pending invoices | Possibly deep-link building | Date-only |
| `GET /api/invoices/pending/latest-business-datetime?onlyUnconfirmed=` | Latest business datetime among pending invoices | Possibly deep-link building | Business/parse time, not SMS-received time |
| `GET /api/invoices/pending/latest-business-date?onlyUnconfirmed=` | Same, date-only | Possibly deep-link building | |
| `GET /api/invoices/pending/latest-sms-datetime?onlyUnconfirmed=` | Latest **real SMS-received** timestamp among pending invoices | Most likely source of Android's `lastPendingAt` | Reads `receivedAtSms`, not `date`/`parsedAt` |

## 10. Confirmation and Final Invoice Path (Gap Fixed)

**This section used to document an open architectural gap. That gap has been fixed with a new endpoint; the old finding is kept below for history, followed by the current behavior.**

### What used to be true

- Confirming a pending invoice only flipped `confirmed = true` on the existing `PendingInvoice` row (`PendingInvoiceService.confirmPendingInvoice`) — it updated nothing else and created nothing else.
- No backend code path converted a confirmed `PendingInvoice` into a final `Invoice`. Final `Invoice` creation only existed through the separate `POST /api/invoices` / `POST /api/invoices/manual` endpoints, which nothing in the pending-invoice code called.
- Whether the frontend closed this gap itself (by calling `POST /api/invoices` immediately after confirming) was an open question for the frontend audit.

### Current behavior

```
POST /api/invoices/pending/{id}/finalize
```

This endpoint (`PendingInvoiceService.finalizePendingInvoice`) does both of the following **in one database transaction**:
1. Builds an `InvoiceDTO` from the `PendingInvoice`'s current fields (`date`, `netTotal`, `total`, `worksiteId`, `worksiteName`, `totalMatch` → `total_match`, `pdfUrl`, `parsedAt`, `reprocessedFromId`, and items) and calls the same `InvoiceService.saveInvoice(...)` logic the old `POST /api/invoices` endpoint uses — so worksite/material matching behavior for the resulting final invoice is identical to before.
2. Marks the source `PendingInvoice` row `confirmed = true`.

**Response contract:**

| Outcome | Status | Body |
|---|---|---|
| Success | `200 OK` | The created `InvoiceDTO` |
| Pending invoice not found | `404 Not Found` | Plain error message |
| Pending invoice already confirmed | `409 Conflict` | Plain error message |

**Atomicity guarantee:** `finalizePendingInvoice` and the `InvoiceService.saveInvoice` it calls are both `@Transactional` with the default `REQUIRED` propagation, so the invoice-save call joins the same transaction as the confirm step rather than opening a second one. If creating the final invoice fails, the pending invoice is **not** marked confirmed (the whole transaction rolls back); if marking it confirmed fails, the just-created final invoice is rolled back too. There is no window where a final invoice can exist without its source pending invoice being confirmed, or vice versa.

**The old two-endpoint flow is still available, unchanged, for backward compatibility:**
- `POST /api/invoices` — still creates a final invoice directly; still used by any caller that isn't going through a pending invoice (e.g. manual creation, or older integrations).
- `PATCH /api/invoices/pending/{id}/confirm` — still just flips the `confirmed` flag with no invoice creation; **no longer the path the frontend's pending-review screens use for confirmation**, per the frontend team.

Per the frontend README, the frontend has switched its pending-confirmation flow to call `finalizePendingInvoice(id)` → this endpoint exclusively, and no longer performs the old two-call `POST /api/invoices` + `PATCH /confirm` sequence.

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

**This section previously documented a confirmed duplicate-creation bug on repeated calls to this endpoint. That has been fixed.**

1. Backend selects candidate pending invoices via `PendingInvoiceRepository.findByConfirmedFalseAndTotalMatchFalse()`, then filters to those with a non-empty `pdfUrl` and a `null` `reprocessedFromId`.
2. **Fixed:** the backend additionally excludes any candidate whose `id` already appears as another row's `reprocessedFromId` (`PendingInvoiceRepository.findAllReprocessedFromIds()`) — i.e., an original that already has a reprocessed child is skipped. An original mismatched invoice is now reprocessed **at most once**; repeated calls to this endpoint no longer re-send the same already-reprocessed originals to Python.
3. Backend calls Python `POST /fix-mismatched` for each remaining candidate, with request body `{"url": pdfUrl, "originalId": <pending invoice's own id>}`.
4. The JSON Python returns is saved as a **new** pending invoice, **in-process** via `PendingInvoiceService.savePendingInvoice(...)` (no longer a self-POST — see Section 8).
5. **`reprocessedFromId`** on the new row links it back to the original invoice's ID — this is exactly the field the new skip-check (step 2) reads to avoid re-reprocessing it.

**Remaining limitations (not addressed by the fix above):**
- **The old (original) pending invoice is still not deleted or updated** — it remains in the database exactly as it was, alongside its new reprocessed child, even though it will never be selected for reprocessing again. There is no cleanup/archival of superseded originals.
- **The reprocess lifecycle is still basic, single-generation:** if the *reprocessed child* itself still comes back `totalMatch = false`, nothing reprocesses it again either (it has a non-null `reprocessedFromId`, so it's excluded from the "original" candidate pool by the pre-existing filter in step 1). There's no multi-generation retry chain.
- **There is still no per-invoice reprocess endpoint or UI action.** This remains a single batch operation over all eligible unconfirmed+mismatched invoices — a user cannot ask to reprocess just one specific pending invoice.

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
| Unknown `type` value | **Fixed:** counted (`unknownTypeMessages`) and persisted (`failed_invoice_imports`, `UNKNOWN_MESSAGE_TYPE`) — no longer silent |
| Malformed `receivedAt` (invoice message) | **Fixed:** caught, counted (`invoicesFailed`), persisted (`INVALID_RECEIVED_AT`) — no longer throws unhandled |
| Malformed `receivedAt` (status message) | Caught per-message, counted (`statusFailed`) — not persisted to `failed_invoice_imports` (that table is invoice-import scoped) |
| Malformed/non-http(s) invoice URL | **Fixed:** validated before Python is called — skipped, counted (`invoicesSkippedInvalidUrl`), persisted (`INVALID_INVOICE_URL`); Python is never invoked |
| Well-formed `http(s)` URL that isn't actually an invoice PDF | Still no deep validation — passed to Python, which fails on its own; the failure comes back as `invoicesFailed`/`PYTHON_PROCESSING_FAILED` |
| Python unavailable | Caught by a blanket `try/catch`; **now counted (`invoicesFailed`) and persisted (`PYTHON_PROCESSING_FAILED`)** — no longer silent, though still no automatic retry |
| Python returns 400/500 | Same as above — `RestTemplate` throws on non-2xx, caught, counted, persisted |
| In-process pending-invoice save fails (e.g. a data constraint violation) | Caught by the same outer `try/catch` in `PythonInvoiceProcessor`; counted (`invoicesFailed`) and persisted with the same `PYTHON_PROCESSING_FAILED` reason (this failure reason currently covers both "Python failed" and "our own save failed") |
| Duplicate SMS upload (same content sent twice) | **Still no server-side dedup** — both `PendingInvoice` and `StatusMessage` rows are created again independently |
| `totalMatch = false` returned from Python | Saved as-is; the invoice becomes eligible for `/pending/fix-unmatched` reprocessing (now reprocessed at most once per original — see Section 13) |
| Reprocessing an already-reprocessed original | **Fixed:** skipped — see Section 13 |
| Confirming an invalid/incomplete pending invoice via `/confirm` or `/finalize` | Only blocked if already `confirmed = true` (`404`/`409` for `/finalize`); neither endpoint checks data completeness (missing worksite, `totalMatch = false`, etc.) |

**Risks/unknowns (not directly confirmable from code alone):** real-world frequency of malformed `receivedAt` values from Android; actual timezone behavior in production; whether `failed_invoice_imports` rows are ever reviewed/acted upon in practice, or just accumulate unread.

## 17. Configuration / Security Notes

- **Python converter URL:** hardcoded string literals in `PythonInvoiceProcessor.java` — not sourced from `application.properties` or any environment variable. **Still true.**
- **Backend self-save URL — removed.** The backend no longer self-POSTs to its own production URL to save pending invoices (Section 8); that hardcoded URL and the HTTP round-trip it required no longer exist in this code path.
- **Hardcoded URL concern (narrowed):** the Python base URL is still a legitimate configuration fragility — there's no single place to change environments (dev/staging/prod) for the Python integration. The previous concern about the backend's own hardcoded self-save URL no longer applies.
- **Committed `application.properties`** uses environment-variable-driven configuration for the database, port, and upload paths (e.g., `${MYSQLHOST:localhost}`, `${PORT:8080}`, `${APP_UPLOADS_ROOT:uploads}`) — this is the properly portable, deployable configuration.
- **Local/machine-specific `application.properties` changes:** this working copy may contain local, machine-specific, or otherwise sensitive values that differ from the committed version (per the project owner's note that this file is edited across multiple personal machines). These local values were **not modified, staged, or unstaged** as part of this documentation update, and are **not treated as part of the invoice feature** unless they affect documented runtime behavior — they don't. Care should be taken not to accidentally commit machine-specific or sensitive local changes to this file.
- **Endpoints appear unauthenticated:** confirmed — no Spring Security filter chain is active in this project (the security starter dependency is present in `pom.xml` but commented out), and no endpoint in the invoice/SMS/Python flow has any auth annotation or header check.
- **CORS is fully open:** confirmed — `CorsConfig` registers `allowedOrigins("*")` for `GET/POST/PUT/DELETE` across the entire API.
- **`/api/invoices/pending/upload` is public and still unprotected:** it's no longer the backend's primary persistence path for SMS-driven invoices (that's now in-process, Section 8), but it remains a live, unauthenticated endpoint that will persist any `PendingInvoiceDTO` payload posted to it directly.
- **No API key or auth exists between this backend and the Python converter** in either direction — confirmed; the Python service itself also requires none (per its own audit).

## 18. Tests

**Test database — fixed.** Tests now run against an **in-memory H2 database**, not the Railway/production MySQL instance. `IntFitBackendApplicationTests` is annotated `@ActiveProfiles("test")`, which activates `src/test/resources/application-test.properties` — an H2 datasource (MySQL-compatibility mode, so the MySQL-specific `LONGTEXT`/`TEXT` column definitions on some entities still work) with `spring.jpa.hibernate.ddl-auto=create-drop`, fully isolated from any real database. Running `./mvnw test` (or `-q test`) is now safe locally and in CI — it will not touch production data.

- **Existing tests:** still exactly one — `IntFitBackendApplicationTests.contextLoads()`. It's still the only test, but it's no longer a production-DB risk, and it's a genuinely useful startup/schema smoke test: it fails if any entity (including the new `FailedInvoiceImport`) can't be mapped to a schema, or if the Spring context — including the `PythonInvoiceProcessor` ↔ `PendingInvoiceService` circular dependency broken via `@Lazy` — fails to wire up.
- **Still missing:** tests for the SMS upload endpoint and `SmsMessageDTO` mapping; tests for the Python integration (`PythonInvoiceProcessor`, even with a mocked `RestTemplate`); tests for pending-invoice save (`savePendingInvoice`); tests for the reprocess "skip already-reprocessed" logic (Section 13); tests for the atomic finalize endpoint (Section 10) and its transactional rollback behavior; tests for URL validation (`isValidInvoiceUrl`) and failed-import persistence (Section 5); tests for field-casing compatibility between Python and the DTOs; tests for worksite matching (`normalizeWorksiteName`, `findByName`); tests for material matching (`NameCleaner`, `findByNameIgnoreCase` vs. `findByName`).

## 19. Known Risks / Technical Debt

| Issue | Category |
|---|---|
| No worksite matching/resolution happens anywhere in the pending-invoice save path | **Missing validation** |
| Material matching differs between the pending path (raw, case-insensitive) and the final-invoice path (`NameCleaner`-normalized, exact) | **Maintainability issue** |
| No server-side deduplication of repeated/duplicate SMS uploads (same content/timestamp can be saved twice) | **Missing validation** |
| No retry or dead-letter re-queueing for a failed invoice import — a `PYTHON_PROCESSING_FAILED` row must be manually re-submitted | **Weak error handling** |
| No pending-invoice edit endpoint — a pending invoice can only be confirmed/finalized or deleted, never corrected in place | **Missing validation** |
| Status-message processing failures are counted (`statusFailed`) but not persisted anywhere for later review, unlike invoice failures | **Missing validation** |
| Reprocessing has no per-invoice trigger/UI and no cleanup of superseded original rows (Section 13) | **Maintainability issue** |
| Python base URL is a hardcoded string literal, not environment-configured | **Security/config concern** |
| All invoice/SMS endpoints are unauthenticated with fully open CORS | **Security/config concern** |
| Only one automated test exists (`contextLoads`) — no coverage of SMS processing, URL validation, reprocessing, or the finalize endpoint | **Maintainability issue** |

**Fixed since the last update to this document** (kept here for traceability, not as current risks):
- ~~`PythonInvoiceProcessor` self-POSTs to this backend's own production URL~~ — now saves in-process via `PendingInvoiceService` (Section 8).
- ~~Repeated `/pending/fix-unmatched` calls create duplicate pending invoices~~ — originals with an existing reprocessed child are now skipped (Section 13).
- ~~No validation that SMS `content` is a well-formed URL before calling Python~~ — now validated; invalid URLs are skipped before Python is called (Section 5).
- ~~Invoice processing failures are silently swallowed~~ — now counted (`SmsProcessingSummaryDTO`) and persisted (`failed_invoice_imports`) — see Section 5.
- ~~No visibility into failed invoice imports~~ — reviewable via `GET /api/invoices/sms-import-failures` (Section 5).
- ~~Tests connect to the Railway/production database~~ — tests now use an isolated H2 profile (Section 18).
- ~~No backend path from `PendingInvoice` to final `Invoice`~~ — see Section 10.

## 20. Open Frontend Questions

1. Who builds the Android deep link (`baba.intfit.sms_uploader://extract?...`) — is it the frontend, using the `latest-sms-datetime`/`latest-saved-date` endpoints documented in Section 14?
2. Which screen lists pending invoices, and does it call `GET /api/invoices/pending` directly?
3. How are pending invoices edited before confirmation — is there a PUT/PATCH endpoint expected that doesn't currently exist for `PendingInvoice`?
4. ~~What does the "confirm" button in the frontend actually do...~~ — **Resolved:** per the frontend README, it now calls `POST /api/invoices/pending/{id}/finalize` exclusively (Section 10). It no longer calls `PATCH /confirm` or `POST /api/invoices` separately.
5. ~~If it calls both, in what order, and what happens if the second call fails...~~ — **Resolved:** it no longer calls both; the new endpoint is atomic, so this failure window no longer applies to pending confirmation.
6. How is a worksite assigned or corrected for a pending invoice, given no backend endpoint for that currently exists?
7. How does the frontend handle material matching/editing for pending-invoice items — does it expose the raw, uncleaned material names created by the pending-invoice path?
8. How is `totalMatch = false` surfaced to the user, and does the frontend distinguish it from a fully-matched invoice?
9. When and how is `POST /api/invoices/pending/fix-unmatched` triggered — user-initiated per invoice, a global button, or scheduled?
10. How are status messages shown and applied in the frontend — does it use `assign`/`unassign`/`apply` as implemented, or expect additional behavior?
11. What exact response shapes does the frontend expect from `GET /api/invoices/pending` and related endpoints — does it match the camelCase/snake_case mix documented in Section 15?

## 21. Quick Reference

- **Android upload endpoint:** `POST /api/invoices/sms-invoices/upload` — now returns a `SmsProcessingSummaryDTO` (counts of processed/skipped/failed messages), not an empty `200 OK` (Section 4).
- **Python endpoints called by this backend:** `POST /process-invoice`, `POST /fix-mismatched` (base URL hardcoded in `PythonInvoiceProcessor.java`)
- **Pending invoice save is in-process:** `PythonInvoiceProcessor` calls `PendingInvoiceService.savePendingInvoice(...)` directly (Section 8) — no more self-POST. `POST /api/invoices/pending/upload` **remains available** for backward-compatible/manual use.
- **Failed invoice import review:** `GET /api/invoices/sms-import-failures?limit=50` — recent invoice-import failures, newest first (Section 5).
- **Pending invoice management:** `GET /api/invoices/pending`, **`POST /api/invoices/pending/{id}/finalize` (current, atomic confirm path)**, `PATCH /api/invoices/pending/{id}/confirm` (old, backward-compatible only), `DELETE /api/invoices/pending/{id}`, `POST /api/invoices/pending/fix-unmatched` (now skips already-reprocessed originals — Section 13)
- **Deep-link timestamp helpers:** `GET /api/invoices/pending/latest-sms-datetime`, `GET /api/invoices/pending/latest-business-datetime`, `GET /api/status-messages/latest-saved-date`
- **Status message endpoints:** `GET /api/status-messages`, `GET /api/status-messages/latest-20`, `GET /api/status-messages/unassigned`, `GET /api/status-messages/by-worksite/{worksiteId}`, `PATCH /api/status-messages/{id}/assign/{worksiteId}`, `PATCH /api/status-messages/{id}/unassign`, `POST /api/status-messages/{id}/apply`, `GET /api/status-messages/since-latest-balance`, `DELETE /api/status-messages/{id}`

**Key mental model:**
Android sends raw SMS → Backend validates the URL, then calls Python → Python returns JSON → Backend saves the result as a PendingInvoice **in-process** (invalid URLs and processing failures are counted and persisted for review) → Frontend reviews it → Frontend calls the backend's `POST /api/invoices/pending/{id}/finalize` → Backend creates the final Invoice and confirms the pending invoice atomically, in one transaction.
