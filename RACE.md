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

At the end of milestone 2, the teammate's patch remained applied only to
reproduce this review evidence; it was not accepted as the final fix.
Commit `7eeb95d` preserves that review state. Milestone 3 below starts by
undoing it with `git apply -R proposed-fix.diff`.

---

## Milestone 3: the fix you would ship

**Which option, and what you changed: option 2, an atomic store operation.**
First reversed `proposed-fix.diff` and checked that `BookingService.java`
matched the shipped version at `a8dc8f5`. The fix is built on that version,
not on the teammate's recheck.

Added the plain, non-default method `boolean insertIfAvailable(Booking)` to
`BookingStore`. `InMemoryBookingStore` implements it as one synchronized
operation: check for overlapping CONFIRMED bookings using the existing
`overlapsConfirmed()` helper, then append only if the slot is free. Both steps
hold the monitor of the same store instance, the lock already used by all
other store operations, including cancellation/promotion.

`BookingService.book()` now makes that one atomic call and rejects the request
when it returns false. The incorrect class-level thread-safety explanation was
updated. The service still validates room/range before attempting the booking;
overlap rejections can now consume an ID because the candidate is constructed
before the atomic decision. IDs remain unique within one service, but are not
promised to be gap-free.

**Required interface/compiler check:** after adding the interface method and
real implementation, `mvn -B '-Dstyle.color=never' compile` failed at
2026-11-06 13:00:08 -05:00 with one compiler error: `PausingStore` did not
implement `insertIfAvailable(Booking)`. Added its direct delegation to the
wrapped store, as instructed. No default method or separate check/write
fallback was added.

**Implementation diff against the shipped code** (four production files):

```diff
diff --git a/src/main/java/edu/cmu/cs214/lab10/harness/PausingStore.java b/src/main/java/edu/cmu/cs214/lab10/harness/PausingStore.java
index 5e6b639..2217dfb 100644
--- a/src/main/java/edu/cmu/cs214/lab10/harness/PausingStore.java
+++ b/src/main/java/edu/cmu/cs214/lab10/harness/PausingStore.java
@@ -111,0 +112,5 @@ public final class PausingStore implements BookingStore {
+    @Override
+    public boolean insertIfAvailable(Booking booking) {
+        return delegate.insertIfAvailable(booking);
+    }
+
diff --git a/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java b/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java
index 514c3e1..7baed5b 100644
--- a/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java
+++ b/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java
@@ -24,5 +24,3 @@ import java.util.concurrent.atomic.AtomicLong;
- * and never written again, and every read and write of booking data goes
- * through {@link BookingStore}, whose methods are each synchronized. Since the
- * store is thread safe, the operations built on top of it are too. The only
- * other field that changes after construction is {@link BookingStats}, and its
- * numbers are documented as approximate.
+ * and never written again. Booking availability and insertion use one atomic
+ * {@link BookingStore} operation, as do cancellation and promotion. IDs use
+ * an atomic counter per service; {@link BookingStats} remains approximate.
@@ -70 +68,2 @@ public final class BookingService {
-        if (store.hasOverlap(roomId, startMinute, endMinute)) {
+        Booking booking = new Booking(nextId(), roomId, startMinute, endMinute, BookingStatus.CONFIRMED);
+        if (!store.insertIfAvailable(booking)) {
@@ -73,2 +71,0 @@ public final class BookingService {
-        Booking booking = new Booking(nextId(), roomId, startMinute, endMinute, BookingStatus.CONFIRMED);
-        store.insert(booking);
diff --git a/src/main/java/edu/cmu/cs214/lab10/store/BookingStore.java b/src/main/java/edu/cmu/cs214/lab10/store/BookingStore.java
index 3ae3544..d8e0375 100644
--- a/src/main/java/edu/cmu/cs214/lab10/store/BookingStore.java
+++ b/src/main/java/edu/cmu/cs214/lab10/store/BookingStore.java
@@ -30,0 +31,8 @@ public interface BookingStore {
+    /**
+     * Checks for overlapping CONFIRMED bookings and inserts the candidate only
+     * if none exist, atomically with respect to all other store operations.
+     *
+     * @return true if inserted, or false if the slot is taken and nothing changed
+     */
+    boolean insertIfAvailable(Booking booking);
+
diff --git a/src/main/java/edu/cmu/cs214/lab10/store/InMemoryBookingStore.java b/src/main/java/edu/cmu/cs214/lab10/store/InMemoryBookingStore.java
index f7fd85f..86331d6 100644
--- a/src/main/java/edu/cmu/cs214/lab10/store/InMemoryBookingStore.java
+++ b/src/main/java/edu/cmu/cs214/lab10/store/InMemoryBookingStore.java
@@ -38,0 +39,9 @@ public final class InMemoryBookingStore implements BookingStore {
+    @Override
+    public synchronized boolean insertIfAvailable(Booking booking) {
+        if (overlapsConfirmed(booking)) {
+            return false;
+        }
+        bookings.add(booking);
+        return true;
+    }
+
```

**Passing milestone-1 and milestone-2 tests:** ran
`mvn -B '-Dstyle.color=never' '-Dtest=RaceTest' test` (exit code 0).
Verbatim output excerpts:

```text
2026-11-06T18:01:01.325Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
2026-11-06T18:01:03.321Z INFO booking.rejected room=WEH-5409 start=900 end=960 reason=slot already held
race.gate target=hasOverlap call=1 paused=false fired=false calls=0
race.results A=CONFIRMED B=REJECTED
race.confirmed count=1
2026-11-06T18:01:03.334Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
2026-11-06T18:01:05.335Z INFO booking.rejected room=WEH-5409 start=900 end=960 reason=slot already held
race.gate target=hasOverlap call=2 paused=false fired=false calls=0
race.results A=CONFIRMED B=REJECTED
race.confirmed count=1
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.053 s -- in edu.cmu.cs214.lab10.harness.RaceTest
[INFO] BUILD SUCCESS
```

**Is the milestone-1 test still testing anything?** Yes, its final-state
assertion still checks that two requests produce just one stored confirmation.
However, it no longer forces the original race: `book()` no longer calls
`hasOverlap()` through `PausingStore`, and the new atomic method is a direct
pass-through. The diagnostics explicitly show `paused=false`, `fired=false`,
and `calls=0`. A finishes while the test waits two seconds for a gate that is
never reached; B then rejects. The milestone-2 test behaves the same way.
Their combined four-second runtime is expected, but their greens alone do
not demonstrate concurrent execution or prove atomicity. Both historical
tests were retained unchanged.

To exercise the new path under competing requests, added
`BookingServiceTest.concurrentServicesSharingAStoreConfirmOnlyOnce()`: two
different service instances share the real store, and both worker threads
reach a `CyclicBarrier` before calling `book()` on the same room and slot.
Bounded futures surface worker failures; ordinary `for` loops count outcomes
and join threads. It asserts one confirmation, one rejection, and exactly one
stored confirmation. The test covers competing callers; the atomicity argument
comes from the lock spanning both the scan and insertion, not from claiming
that one test run explores every possible interleaving.

**Full suite:** `mvn -B '-Dstyle.color=never' test` passed all 14 tests at
2026-11-06 13:01:47 -05:00 (exit code 0). Verbatim output excerpts:

```text
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.043 s -- in edu.cmu.cs214.lab10.domain.BookingTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.020 s -- in edu.cmu.cs214.lab10.harness.RaceTest
2026-11-06T18:01:47.233Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T18:01:47.233Z INFO booking.rejected room=WEH-5409 start=540 end=600 reason=slot already held
race.sharedStore confirmed=1 rejected=1 stored=1
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.019 s -- in edu.cmu.cs214.lab10.service.BookingServiceTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**What runs one at a time now / cost line:** for callers sharing one store,
the entire availability-check-plus-insert operation now runs one at a time.
Previously each store call was individually serialized, but another caller
could enter between a service's check and its insert. Now another booking,
waitlist insertion, snapshot read, or cancellation/promotion must wait for
both steps to finish. This includes different rooms and different service
instances sharing that store, so the larger critical section reduces
opportunities for concurrency. The scan is still linear in stored bookings;
validation, logging, and approximate statistics remain outside the store lock.
Separate store instances have separate monitors.

**What was left unfixed:**

- `BookingStats` lost updates and stale reads remain accepted approximate
  reporting; they do not control availability and exact counters would add work
  to a separate concern.
- Per-service ID sequences can still collide across different service instances
  (for example when they book different free slots); this is a separate issue
  from the ticket's distinct-ID double booking and needs its own ID policy.
- Raw `insert()` remains available and unchecked for existing callers such as
  waitlist insertion. Booking confirmation now uses the atomic method; redesigning
  or restricting the low-level storage API is outside this ticket.
- The catalog, immutable records, snapshots, logging formatter, and existing
  atomic cancellation/promotion needed no change for the ticket.
