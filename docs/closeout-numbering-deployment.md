# Closeout usability batch — deployment checklist

Room views, floor context and permanent measurement numbers. One coordinated sequence.

The release order matters more than usual here, because **revision checking alone does not
protect the new metadata**. The app build currently installed sends a genuinely valid revision
while dropping JSON fields it does not recognise, so it would round-trip every draft with the
measurement numbers erased. That is why the server has a separate rule, and why the window
between backend and app release must be short.

That rule is a **capability declaration**, not a guess about who is calling. A client that
preserves measurement metadata says so on every save:

```
metadataVersion: 1          (in the request body)
X-Closeout-Metadata: 1      (as a request header; either is accepted)
```

The server treats the two kinds of caller differently, and the difference is the whole point:

| Caller | A row arrives without a number the server holds | Result |
|---|---|---|
| **Declares `metadataVersion: 1`** | its copy predates allocation | **reconciled** — see below |
| **Declares nothing** | it dropped the field | **428 `DRAFT_METADATA_REQUIRED`**, nothing written |

A build released before numbering cannot send the declaration, so it stays blocked by
construction rather than by detection.

### What "reconciled" means

A current client legitimately holds rows with no numbers: it loaded the draft before any were
allocated, another device saved first, and the user then resolved the conflict by keeping
their own work. Refusing that client would strand it permanently.

So for a declaring client the server **restores the authoritative number by stable row id**,
and takes nothing else from the stored copy. The caller's dimensions, room, floor, notes,
additions and deletions all stand. The stored number wins even if the caller sent a different
one, which is what guarantees an existing measurement never acquires a new number — and what
stops a stale caller giving two measurements the same one. The high-water mark only ever
rises, so a number is never reissued.

---

## 0. Before anything

- [ ] **Fresh backup, and verify it restores** into a scratch schema. Confirm
      `SELECT COUNT(*) FROM closeout_drafts` and `FROM closeout_final_row` match production.
- [ ] Record `SELECT COUNT(*) FROM closeout_drafts` and the newest `updated_at`.
- [ ] Confirm nobody is mid-closeout. A device holding unsaved measurements through the
      cutover keeps them locally but cannot sync until its app is updated.

## 1. Apply migration 008

`docs/sql/008-closeout-measurement-numbering.sql` — **prepared, not applied.** Three additive
columns:

| Table | Column | |
|---|---|---|
| `closeout_drafts` | `next_measurement_seq BIGINT NOT NULL DEFAULT 1` | the allocation high-water mark |
| `closeout_final_row` | `measurement_seq BIGINT NULL` | the number a finalized measurement carried |
| `closeout_final_row` | `floor VARCHAR(80) NULL` | the floor it was taken on |

- [ ] `008` is the next unused number: `001`–`007` are present and applied.
- [ ] Run the section 0 precondition queries first; skip any statement whose column exists.
- [ ] Apply, then run the section 2 verification queries. Expect `bigint / NO / 1` for the
      mark, and `with_number = 0` for finalized rows — **historical records are not
      backfilled**, by design.

No existing row is rewritten. No total is recalculated. No formula or rounding rule changes.

## 2. Stop the old writers, then deploy the backend

- [ ] Scale the old backend to zero or remove it from the load balancer **before** the new one
      serves traffic. Running both leaves the old unprotected write path open.
- [ ] Deploy. Confirm startup adds nothing further to either table.

From this point the capability rule above is in force:

- a save **declaring `metadataVersion: 1`** is accepted, and any row whose number it is
  missing is reconciled from the stored assignment by row id;
- a save **declaring nothing** that arrives with numbers missing from rows stored **with**
  them is refused with **428 `DRAFT_METADATA_REQUIRED`**, and nothing is written.

The installed app maps 428 to "please update the app" and keeps its work locally, which is the
same behaviour it already has for a missing revision. It cannot send the declaration, so from
this point until it is updated it cannot save to the server — see step 3.

## 3. Release the app

- [ ] Order is **migration, then backend, then app**. The new app displays numbers the new
      backend allocates; against the old backend it would show everything as Pending.
- [ ] The new build is the one that sends `metadataVersion: 1`, so it is also the only build
      that can save once the backend is live. That is why this step follows the backend
      immediately rather than at leisure.
- [ ] **Keep the gap short.** Until a device is updated it cannot sync at all — it is not
      losing work, but it is not saving to the server either.
- [ ] Tell users out of band before the backend cutover, as in the previous batch: finish and
      sync open closeouts, then update.
- [ ] A device updated **after** the cutover is still fine even if its local draft predates
      allocation. It now declares `metadataVersion: 1`, so its first save is reconciled
      rather than refused and its unsynced measurements reach the server intact, keeping
      their own rooms, floors, dimensions and notes.

## 4. Smoke test, on a disposable closeout

Create a throwaway master worksite and work only in that one.

- [ ] Add two measurements → each shows **Pending**, then **#1** and **#2** once saved.
- [ ] Delete #1, add another → the new one is **#3**, never a reused #1.
- [ ] Duplicate a measurement → the copy shows **Pending**, then its own new number; the
      original keeps its number.
- [ ] Set "Floor for new rows" to 2, add a measurement → it carries floor 2. An existing
      measurement with no floor **stays** without one.
- [ ] Tap **Rooms** in the header → rooms listed under their floors. Two rooms with the same
      name on different floors appear separately. Open one → its measurements appear grouped
      by work type, with numbers, dimensions and per-unit totals. A room with both m² and
      count work shows **two** totals, never one combined figure.
- [ ] Long-press a room chip in the editor → the same room view opens.
- [ ] Focus a floor → its rows gain a thin border **and** a "Floor N" badge; every other
      measurement stays visible. Switch to **Show only** → the rest are hidden and the hint
      says so. **Clear** restores everything.
- [ ] Two devices: edit on B offline, save on A, reopen B → conflict. Choose **Keep mine** →
      B's measurements keep the numbers they had, and any new row gets a number above every
      one previously issued.
- [ ] The same, but with B holding rows from **before** numbering existed: A saves first and
      the server allocates, then B keeps its own work. B must save successfully — **not** be
      told to update the app — and its rows must come back carrying the numbers A's save
      allocated, with B's own rooms, floors, dimensions, notes and offline additions intact.
- [ ] Finalize, then check the stored row carries `measurement_seq` and `floor`.
- [ ] Delete the disposable closeout.

## 5. Local verification, any time

- [ ] Use `scripts\run-local-isolated.ps1` only. Never
      `mvn spring-boot:run -Dspring-boot.run.profiles=test` — that does not isolate anything
      and once connected a development startup to production.
- [ ] `scripts\run-local-isolated.ps1 -Stop` stops the forked JVM and destroys the database.

## 6. Rollback

- [ ] Roll the **app** back first, then the backend. A new app against an old backend shows
      every measurement as Pending; a rolled-back app no longer declares
      `metadataVersion: 1`, so against the new backend it is refused with 428 and keeps its
      work locally. Do not leave that pairing in place.
- [ ] **Do not drop the three columns** while a backend that maps them is running. Dropping
      `next_measurement_seq` would restart numbering from 1 and reissue numbers people have
      already written down, so prefer leaving the columns in place.

---

## What the numbers mean, and do not mean

A measurement's number records **the order in which the server first accepted it**. It is not
proof of the order anyone typed them, and it is not claimed to be. Two people measuring
offline have their work numbered in whatever order their devices reconnect. That is the honest
limit of any server-allocated sequence.

Measurements taken **before this feature** are numbered on the first save that carries them,
in the order they appear in the draft — type order, then row order within the type. That is
deterministic, so the same draft always produces the same assignment, but it is a
reconstruction. **Their original entry order cannot be proven** and nothing here pretends
otherwise.

Once allocated, a number never changes: not on edit, not on reorder, not on filtering, not
when a row moves room or floor, and not when another measurement is deleted. A deleted
measurement's number is retired and never reissued.
