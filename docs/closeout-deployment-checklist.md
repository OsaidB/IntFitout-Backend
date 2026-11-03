# Closeout save/recovery batch — deployment checklist

One coordinated sequence.

**The schema change is already on production and verified.** `closeout_drafts.version` is
`bigint`, `NOT NULL`, `DEFAULT 0`, confirmed 2026-11-03. **Do not run 007's `ADD COLUMN`
statement** — it would fail with "Duplicate column name 'version'". How the column got there,
and what else that startup did and did not do, is recorded in
`docs/closeout-version-incident.md`.

Preserving existing measurements is the priority throughout: no step reads, rewrites or
recalculates a draft's JSON, and no finalized record is touched.

---

## 0. Before anything

- [ ] **Take a fresh backup and verify it restores.** A dump that has not been restored
      somewhere is not yet a backup. Restore into a scratch schema and confirm
      `SELECT COUNT(*) FROM closeout_drafts` matches production.
      The pre-correction backup from the incident is at
      `C:\Users\OAB2K\Documents\IntFitout-backups\railway-after-closeout-incident-20261103-064015.sql`;
      take a new one for this deployment rather than relying on it.
- [ ] Record the current row count and newest `updated_at` from `closeout_drafts`.
- [ ] Confirm nobody is mid-closeout. A device holding unsaved measurements through the
      cutover keeps them locally, but cannot sync until its app is updated.

## 1. Confirm the schema, do not change it

- [ ] Verify, do not alter:
      ```sql
      SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
        FROM information_schema.COLUMNS
       WHERE TABLE_SCHEMA = DATABASE()
         AND TABLE_NAME   = 'closeout_drafts'
         AND COLUMN_NAME  = 'version';
      ```
      Expect `bigint / NO / 0`. If it differs, stop and reconcile before deploying.
- [ ] `SELECT COUNT(*) AS drafts, COUNT(version) AS with_version FROM closeout_drafts;`
      — the two numbers must match.
- [ ] `SHOW INDEX FROM closeout_drafts WHERE Key_name = 'uk_closeout_drafts_master_worksite';`
      — expect one row. This unique key decides the concurrent-create race; without it two
      drafts could exist for one worksite.
- [ ] Confirm `001`–`006` are applied. `007` is **verification-only** for production now.

## 2. Stop the old writers before enabling the new contract

The old backend accepts any save with no revision. The new one refuses those. If both are
live at once, the old one remains an unrestricted overwrite path and the protection is only
partial.

- [ ] Scale the old backend to zero, or remove it from the load balancer, **before** the new
      one serves traffic. Do not run the two side by side.
- [ ] If the deployment is rolling and that is impossible, accept explicitly that the overlap
      window has no protection, and keep it to minutes.

## 3. Deploy the backend

- [ ] Deploy. Confirm startup adds nothing further to `closeout_drafts` —
      `ddl-auto=update` should now be a no-op for this table.
- [ ] Smoke the contract on a **disposable** closeout (step 7), never a real one.

## 4. Handle existing local drafts on older APKs

This is the step most likely to lose work if skipped.

A phone on the old APK may hold measurements in `AsyncStorage` that never reached the server.
After the cutover its saves are refused with **428**, and **the old build has no code to show
that** — its save path logs the failure and carries on, so the user sees nothing and believes
the work is saved. Do not rely on the app to communicate this.

- [ ] Tell users out of band, **before** the backend cutover: finish and sync any open
      closeout, and start no new measurements until the app is updated.
- [ ] Keep the gap between backend deploy and app release short, ideally minutes.
- [ ] After updating, the new build detects a local draft with no revision metadata and
      surfaces it as a decision (`CONFLICT_REASON.NO_REVISION`), withholding the revision so
      that draft cannot silently overwrite the server. Verified by test.
- [ ] `app/debug/closeout-recovery.jsx` still dumps raw local drafts if anything needs
      recovering by hand.

## 5. Release the app

- [ ] Order is **backend first, then app**. The new app needs the revision the new backend
      returns; against the old backend it would get none and could not save safely.
- [ ] Confirm the installed build is the new one before anyone resumes measuring.

## 6. Local verification, any time

Never use `mvn spring-boot:run -Dspring-boot.run.profiles=test`. It does not isolate
anything: `application-test.properties` is in `src/test/resources`, off the `spring-boot:run`
classpath, **and** the `h2` dependency is `test`-scoped so H2 cannot load there either. That
combination connected a development startup to production on 2026-11-02.

- [ ] Use `scripts\run-local-isolated.ps1`. It creates its own digest-pinned MySQL container
      published on loopback only, passes `spring.datasource.*` explicitly with no profile,
      blanks the production and messaging variables in the child process, and refuses to
      report readiness until it has confirmed the live `jdbcUrl` from the running process's
      own Hikari log.
- [ ] `scripts\run-local-isolated.ps1 -Stop` stops the forked JVM and destroys the container.

## 7. Smoke test, on a disposable closeout

Create a throwaway master worksite and work only in that one.

- [ ] Add two measurements → status goes `Saving…` then `Saved`.
- [ ] Delete one → the confirmation names its room and dimensions → **Cancel** → both still
      there, unchanged.
- [ ] Delete again → **Delete** → gone, Undo bar appears → **Undo** → the row returns in its
      original position.
- [ ] Airplane mode, edit → **“Saved on this device · not yet synced”**, never a bare
      “Saved”. Restore the network → **Retry** → `Saved`.
- [ ] Edit, then navigate away immediately → the edit survives.
- [ ] Two devices: edit on B with its network down, save on A, reopen B → B shows
      “changed on another device”, keeps its own measurements, and **View other version**
      lists A's. Pick either; the discarded version stays recoverable.
- [ ] While unsynced, Summary → Finalize refuses with a reason and the button is disabled.
- [ ] Sync, confirm both sides agree, finalize, and check the stored totals match the
      screen's quantities.
- [ ] Delete the disposable closeout.

## 8. Rollback

- [ ] Rolling the backend back re-opens the unversioned overwrite path. The `version` column
      is harmless to leave in place — **do not drop it** while a backend with `@Version`
      mapped is live, or every draft read and write fails.
- [ ] If the app must roll back, roll the backend back first, reversing the deploy order.

---

### Still open, deliberately

- The **rounding mismatch** between the screen and the finalized record
  (`docs/closeout-rounding-discrepancy.md`) is recorded and pinned by tests, not changed.
- Room views, floors and measurement numbering are deferred
  (`features/closeout/NEXT-BATCH.md` in the frontend repo).
