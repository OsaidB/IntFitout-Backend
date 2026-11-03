# Closeout rounding: recorded discrepancy, open decision

**Status: recorded, not changed.** Nothing in this batch alters a formula, a rounding rule or
a stored total. This note exists so the decision is made deliberately rather than by accident.

## What differs

The app and the server apply the unit price at different moments.

| | Convention | Code |
|---|---|---|
| On screen while measuring | multiply **once**, after summing quantities | `calcTypeSubtotal` in `features/closeout/closeoutMath.js` |
| Stored when finalized | round **each row** to money, then sum | `CloseoutFinalizeServiceImpl` (`rowSubtotal = money(quantity × unitPrice)`) |

Both are internally consistent. They are different conventions, not a bug in either formula:
`Σ round(qᵢ · p) ≠ round(Σqᵢ · p)`.

## A concrete example

Four rows of 0.25 m × 0.5 m = 0.125 m² each, unit price 1.00:

- **Server:** `money(0.125 × 1.00)` = `0.13` per row, summed over four rows = **0.52**
- **App:** total quantity `0.5000`, `0.5 × 1.00` = **0.50**

A two-agorot difference on four rows. The quantities themselves agree exactly (`0.5000`).
The drift scales with the number of rows, not with the amounts, so a long type drifts more
than a large one.

This example is pinned by `roundingMismatchAgainstTheScreen()` in
`CloseoutFinalizeCharacterizationTest`, so neither side can move without a test going red.

## Rounding actually in force today

- `money(v)` = `v.setScale(2, HALF_UP)`
- `qty(v)` = `v.setScale(4, HALF_UP)`
- The app's `formatNumber` displays with `toFixed(2)`, after float arithmetic.

## The decision, when it is taken

**Changing the server** to multiply once per type would make the screen and the stored record
agree, but every **already finalized** closeout was computed the other way. Those records
must not be recalculated: they are the historical record of what was agreed. So a change
applies to new finalizations only, and old records keep their stored totals — which means two
conventions coexist in the data, distinguishable only by date.

**Changing the app** to round per row would make the screen match what gets stored, change no
stored record at all, and need no migration. It is the smaller and safer move, and it makes
the number you watch while measuring the number that gets filed.

**Doing nothing** is tenable: the difference is cents, and the quantities — which is what the
measuring is actually for — already agree exactly.

No recommendation is implemented here. The characterization test holds current behaviour in
place until you choose.
