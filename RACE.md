# RACE.md

One section per milestone. Fill each one in order as you go.

The prediction in milestone 1 is written BEFORE you run your race test. A TA
may ask about it. Commit it on its own so the order is visible.

Keep it short and specific. Point at methods, call sites, and the invariant.

---

## Milestone 1: from the ticket to a test that fails every time

### Suspect list

At milestone 1, reviewed the shipped domain, service, store, and harness code.
The teammate's `proposed-fix.diff` had not been opened or applied at that point.

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

Local toolchain: Maven 3.9.16 and Oracle JDK 25.0.4.1, compiling with the
repository's Java 21 release setting. The CI workflow uses Temurin JDK 21.

**Prediction commit:** `882e701` (2026-11-06 12:39:17 -05:00), made before
implementing or running the race test. The first run below began afterward.

**Aim:** `new PausingStore(real, "hasOverlap", 1)`. A's first availability
answer is already `false` when it pauses, but its booking has not been inserted.
B's second check passes straight through the one-shot gate and still sees an
empty store. After B finishes, releasing A delivers the stale answer. This
forces the predicted check/check/insert/insert schedule without `sleep` or
repeated attempts.

The test uses two named threads and `FutureTask` results so worker exceptions
reach JUnit. It releases the gate in `finally`, uses bounded waits, and counts
CONFIRMED overlapping bookings directly in the real store using a `for` loop.
The assertion checks the business invariant, not a required thread order or
approximate statistics. Gate diagnostics are logged so later green runs can
be checked for whether they still exercise this pause.

Run each time from the repository root:

```powershell
mvn -B '-Dstyle.color=never' '-Dtest=RaceTest' test
```

**Run 1 — exit code 1, verbatim test-output excerpt:**

```text
[INFO] Running edu.cmu.cs214.lab10.harness.RaceTest
2026-11-06T17:40:00.165Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
2026-11-06T17:40:00.168Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=900 end=960
race.gate target=hasOverlap call=1 paused=true fired=true calls=2
race.results A=CONFIRMED B=CONFIRMED
race.confirmed count=2
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.057 s <<< FAILURE! -- in edu.cmu.cs214.lab10.harness.RaceTest
[ERROR] edu.cmu.cs214.lab10.harness.RaceTest.concurrentBookingsForTheSameSlotConfirmOnlyOnce -- Time elapsed: 0.038 s <<< FAILURE!
org.opentest4j.AssertionFailedError: A room must have at most one CONFIRMED booking covering the same minute ==> expected: <1> but was: <2>
```

**Run 2 — exit code 1, verbatim test-output excerpt:**

```text
[INFO] Running edu.cmu.cs214.lab10.harness.RaceTest
2026-11-06T17:40:07.638Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
2026-11-06T17:40:07.641Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=900 end=960
race.gate target=hasOverlap call=1 paused=true fired=true calls=2
race.results A=CONFIRMED B=CONFIRMED
race.confirmed count=2
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.063 s <<< FAILURE! -- in edu.cmu.cs214.lab10.harness.RaceTest
[ERROR] edu.cmu.cs214.lab10.harness.RaceTest.concurrentBookingsForTheSameSlotConfirmOnlyOnce -- Time elapsed: 0.042 s <<< FAILURE!
org.opentest4j.AssertionFailedError: A room must have at most one CONFIRMED booking covering the same minute ==> expected: <1> but was: <2>
```

**Prediction versus observation:** both runs match the predicted result: the
gate fired on call 1, both requests returned CONFIRMED, and the store contained
two overlapping confirmations with different IDs. The confirmations occur in
milliseconds, well before the gate's 2-second timeout; the outcome is not a
timeout or a worker exception. No correction to the prediction was needed.

**Full-suite check after adding the test:** `mvn -B '-Dstyle.color=never' test`
also exited 1 at 2026-11-06 12:40:22 -05:00. All original 11 tests still passed;
only the new invariant assertion failed, as required for milestone 1.

```text
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.038 s -- in edu.cmu.cs214.lab10.domain.BookingTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.018 s -- in edu.cmu.cs214.lab10.service.BookingServiceTest
[ERROR] Tests run: 12, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

At the end of milestone 1, production code was unchanged; milestones 2 and 3
were still pending. The following sections record later work.

---

## Milestone 2: the patch a teammate sent

Applied the supplied patch with `git apply proposed-fix.diff` after milestone 1.
`git diff --stat` confirmed exactly seven added lines in `BookingService.java`.
It adds a second `hasOverlap()` immediately before `insert()`, without making
the last check and the write atomic.

**Your milestone-1 test on the patched code: GREEN.** Ran the original test
before changing the test class:

```powershell
mvn -B '-Dstyle.color=never' '-Dtest=RaceTest#concurrentBookingsForTheSameSlotConfirmOnlyOnce' test
```

Verbatim output excerpts (exit code 0):

```text
2026-11-06T17:50:06.710Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
2026-11-06T17:50:06.715Z INFO booking.rejected room=WEH-5409 start=900 end=960 reason=slot already held
race.gate target=hasOverlap call=1 paused=true fired=true calls=4
race.results A=REJECTED B=CONFIRMED
race.confirmed count=1
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.055 s -- in edu.cmu.cs214.lab10.harness.RaceTest
[INFO] BUILD SUCCESS
```

The gate still fired, so this green result is not caused by missing the pause.
A pauses after its first check; B completes both of its checks and inserts;
when A resumes, the added second check sees B's booking and rejects A. This
shows the patch handles the milestone-1 schedule, not every possible schedule.

**The re-aimed run: RED.** Kept the original test and added
`concurrentBookingsAfterRecheckConfirmOnlyOnce()`. Both tests share the same
two-thread setup and final-state assertion; only the target call index differs.
The new aim is `new PausingStore(real, "hasOverlap", 2)`.

Because B starts only after A reaches the gate, call 2 is A's newly added
defensive recheck. Pausing after that check directly exercises the patch's
remaining check/write gap; it does not bypass the recheck.

| Step | Thread A | Thread B |
|---|---|---|
| t1 | First check (call 1) returns `false`; create booking `bk-1`. | |
| t2 | Second check (call 2) computes `false`; release the store lock and pause before returning that saved answer. | |
| t3 | | First check (call 3) returns `false`; create booking `bk-2`. |
| t4 | | Second check (call 4) returns `false`. |
| t5 | | Insert `bk-2` as CONFIRMED and finish. |
| t6 | Resume, receive the saved `false`, and insert `bk-1` as CONFIRMED. | |

```powershell
mvn -B '-Dstyle.color=never' '-Dtest=RaceTest#concurrentBookingsAfterRecheckConfirmOnlyOnce' test
```

Verbatim output excerpt (exit code 1):

```text
2026-11-06T17:50:29.452Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=900 end=960
2026-11-06T17:50:29.455Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
race.gate target=hasOverlap call=2 paused=true fired=true calls=4
race.results A=CONFIRMED B=CONFIRMED
race.confirmed count=2
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.062 s <<< FAILURE! -- in edu.cmu.cs214.lab10.harness.RaceTest
[ERROR] edu.cmu.cs214.lab10.harness.RaceTest.concurrentBookingsAfterRecheckConfirmOnlyOnce -- Time elapsed: 0.041 s <<< FAILURE!
org.opentest4j.AssertionFailedError: A room must have at most one CONFIRMED booking covering the same minute ==> expected: <1> but was: <2>
```

**Your verdict: REJECT the patch.** The re-aimed test still creates two
CONFIRMED bookings for the same room and minutes, violating the ticket's
invariant. Re-reading availability moves the race window to after the final
check; it does not close it. The second check at patched `BookingService.java`
lines 78-80 and the insert at line 81 are separate store calls. The added
comment claiming there is no remaining window is contradicted by this run.
The original green test proves one interleaving is handled; it does not prove
atomicity or exclude the demonstrated counterexample. Both runs report a
fired gate and finish well before its two-second timeout.

**Full-suite verification:** `mvn -B '-Dstyle.color=never' test` exited 1 at
2026-11-06 12:51:10 -05:00. The original 11 tests and milestone-1 race test pass;
only the new re-aimed test fails. It reproduced the same bad state again:

```text
2026-11-06T17:51:10.508Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=900 end=960
2026-11-06T17:51:10.509Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
race.gate target=hasOverlap call=2 paused=true fired=true calls=4
race.results A=CONFIRMED B=CONFIRMED
race.confirmed count=2
[ERROR] Tests run: 13, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

The teammate's patch remains applied only to reproduce this review evidence;
it is not accepted as the final fix. Before milestone 3, undo it with
`git apply -R proposed-fix.diff` and build the chosen fix on the shipped code.
Milestone 3 has not been started.

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
