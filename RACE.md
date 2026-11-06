# RACE.md

One section per milestone. Fill each one in order as you go.

The prediction in milestone 1 is written BEFORE you run your race test. A TA
may ask about it. Commit it on its own so the order is visible.

Keep it short and specific. Point at methods, call sites, and the invariant.

---

## Milestone 1: from the ticket to a test that fails every time

### Suspect list

Reviewed the shipped domain, service, store, and harness code. The teammate's
`proposed-fix.diff` has not been opened or applied.

| # | What you found (method, field, or class) | Verdict, in one line |
|---|---|---|
| 1 | `BookingService.book()`, lines 70-74 | Ticket cause: `hasOverlap()` and `insert()` are individually atomic, but their check-then-write sequence is not; two callers can both confirm the same slot. |
| 2 | `InMemoryBookingStore.bookings`, `hasOverlap()`, `insert()`, `size()` | The private list is protected by the store monitor; `insert()` intentionally does not recheck availability, so method-level locking does not protect the service's whole booking decision. |
| 3 | `bookingsForRoom()` / `BookingService.listBookings()` | Safe snapshot: copied under the store lock into an unmodifiable list of immutable `Booking` records; callers cannot mutate stored bookings. |
| 4 | `cancelAndPromote()` and its private helpers | Cancellation, availability checking, and promotion share the store lock; no intermediate vacancy escapes that operation, and the ticket excludes cancellations. |
| 5 | `BookingService.joinWaitlist()` | Inserts only WAITLISTED entries through the synchronized store; those entries do not block rooms or directly create a second confirmation. |
| 6 | `BookingStats` counters and the service's `stats` field | Real lost-update/stale-read risk, explicitly accepted for approximate reporting; booking decisions never read these counters, so this does not explain the ticket. |
| 7 | `BookingService.catalog`, `Room`, and `Booking` | Catalog is privately constructed and never changed afterward; records are immutable and status changes create replacement values, so concurrent calls do not mutate these shared values. |
| 8 | `BookingService.sequence` / `nextId()` | `AtomicLong` prevents duplicate increments within one service; separate service instances can reuse IDs because sequences are per instance, a separate issue from this ticket's distinct booking IDs. |
| 9 | `BookingLog.TIMESTAMP` / standard output | The shared formatter is immutable and thread-safe; logging does not decide availability, and the ticket confirms duplicate data rather than merely duplicate log lines. |
| 10 | `PausingStore` counters/latches and `StressRunner` | Test controls use atomic counters and latches/barriers; the wrapper deliberately holds an already computed result after a store call, while the stress runner only samples possible schedules. |

**Which one explains the ticket, and how you know.** Tie it to the invariant
the ticket names.

Finding 1 violates the invariant: a room may have at most one CONFIRMED
booking covering any given minute. `hasOverlap()` releases the store monitor
before `book()` calls `insert()`. Another caller can check the same still-free
slot in that gap; neither insert validates the earlier answer again. Both
requests can then succeed with different IDs and no exceptions, matching the
ticket. The thread-safety claim in `BookingService` lines 25-26 incorrectly
extends individual store-method safety to the compound service operation.

Baseline on 2026-11-06: `mvn -B '-Dstyle.color=never' test` passed all 11
existing tests (0 failures, 0 errors). Three separate 100-round `StressRunner`
runs reported 12, 19, and 12 bad rounds; this illustrates variability but does
not pin an interleaving. The existing service tests invoke callers sequentially.

### Predicted interleaving (write this before you run your race test)

Prediction recorded before implementing or running `RaceTest`. Both threads
call `book("WEH-5409", 900, 960)` on the same service and initially empty store.
Aim `PausingStore` at `hasOverlap`, call **1**. The wrapper pauses after the
delegate has computed its answer and released its monitor, before returning
that saved answer to the service. The test starts B after A reaches the gate,
waits for B to finish, then releases A; all waits remain bounded.

| Step | Thread A | Thread B |
|---|---|---|
| t1 | Validate the room/range; call `hasOverlap()` and compute `false` under the store lock. | |
| t2 | Release the store lock; pause in the wrapper before returning the saved `false`. | |
| t3 | | Validate the same room/range; call `hasOverlap()` (call 2), which returns `false` because the store is still empty. |
| t4 | | Generate `bk-1`, insert a CONFIRMED booking for `[900, 960)`, log confirmation, and finish. |
| t5 | Resume after the test releases the gate; receive the old `false` without rechecking. | |
| t6 | Generate `bk-2`, insert another CONFIRMED booking for `[900, 960)`, log confirmation, and finish. | |

**Predicted end state:** two distinct CONFIRMED bookings for WEH-5409 covering
the same 60 minutes. A correct-state assertion expecting one confirmed booking
will fail with `expected: <1> but was: <2>`.

The prediction above will remain unchanged when the results are added below.

### Your race test

Pending: implement the test after committing this prediction, then record two
separate failing runs with their timestamped logs here.

---

## Milestone 2: the patch a teammate sent

**Your milestone-1 test on the patched code.** Green or red. Paste the result.

**The re-aimed run.** Method name, call index, the pasted result, and one line
on why that aim tests the patch rather than sidestepping it.

**Your verdict.** Accept or reject, and what your milestone-1 result does and
does not show about the patch.

---

## Milestone 3: the fix you would ship

**Which option, and what you changed.** Name the lock or the atomic operation
and the two operations it now covers. Paste the diff of your change and the
passing run of your milestone-1 test and the full suite.

**Is your milestone-1 test still testing anything?** After your fix, describe
what that test now exercises. Did `PausingStore` pause a call during the green run? How do
you know?

**What runs one at a time now that ran in parallel before.** Name concretely which callers
queue behind which. "Nothing, it is just correct now" does not count as an
answer.

**What you left unfixed.** For anything your review flagged that you chose not
to fix, one line each on why.
