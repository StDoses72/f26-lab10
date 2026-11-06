package edu.cmu.cs214.lab10.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import edu.cmu.cs214.lab10.domain.Booking;
import edu.cmu.cs214.lab10.domain.BookingResult;
import edu.cmu.cs214.lab10.domain.BookingStatus;
import edu.cmu.cs214.lab10.domain.Room;
import edu.cmu.cs214.lab10.service.BookingService;
import edu.cmu.cs214.lab10.store.InMemoryBookingStore;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/** Deterministic reproduction of the double-booking ticket. */
class RaceTest {

    @Test
    void concurrentBookingsForTheSameSlotConfirmOnlyOnce()
            throws InterruptedException, ExecutionException, TimeoutException {
        InMemoryBookingStore real = new InMemoryBookingStore();
        PausingStore gated = new PausingStore(real, "hasOverlap", 1);
        BookingService service = new BookingService(gated,
                List.of(new Room("WEH-5409", "Wean 5409", 12)));
        Callable<BookingResult> request = new Callable<>() {
            @Override
            public BookingResult call() {
                return service.book("WEH-5409", 900, 960);
            }
        };
        FutureTask<BookingResult> resultA = new FutureTask<>(request);
        FutureTask<BookingResult> resultB = new FutureTask<>(request);
        Thread customerA = new Thread(resultA, "customer-A");
        Thread customerB = new Thread(resultB, "customer-B");

        boolean paused;
        try {
            customerA.start();
            paused = gated.awaitPaused();
            customerB.start();
            customerB.join(PausingStore.TIMEOUT_MILLIS);
        } finally {
            gated.release();
            customerA.join(PausingStore.TIMEOUT_MILLIS);
            customerB.join(PausingStore.TIMEOUT_MILLIS);
        }

        // Future results surface worker failures instead of silently losing them.
        BookingResult bookingA = resultA.get(PausingStore.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        BookingResult bookingB = resultB.get(PausingStore.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        System.out.println("race.gate target=hasOverlap call=1 paused=" + paused
                + " fired=" + gated.fired() + " calls=" + gated.targetCallCount());
        System.out.println("race.results A=" + bookingA.outcome() + " B=" + bookingB.outcome());

        int confirmed = 0;
        for (Booking booking : real.bookingsForRoom("WEH-5409")) {
            if (booking.status() == BookingStatus.CONFIRMED && booking.overlaps(900, 960)) {
                confirmed++;
            }
        }
        System.out.println("race.confirmed count=" + confirmed);
        assertEquals(1, confirmed,
                "A room must have at most one CONFIRMED booking covering the same minute");
    }

    // ------------------------------------------------------------------
    // Wiring example 1: wrap the store
    //
    // PausingStore is a BookingStore that wraps another BookingStore. Build the
    // real store, wrap it, and hand the wrapper to the service. The service
    // cannot tell the difference.
    //
    //   InMemoryBookingStore real = new InMemoryBookingStore();
    //   PausingStore gated = new PausingStore(real, "bookingsForRoom", 1);
    //   BookingService service = new BookingService(gated, ROOMS);
    //
    // (ROOMS is the room list, as in BookingServiceTest.)
    //
    // Assert against `real`, not `gated`: you want the end state of the store,
    // and reading through the wrapper counts as a call.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Wiring example 2: aim the gate
    //
    // The two constructor arguments after the delegate are a method name and a
    // call index counting from 1 across all threads:
    //
    //   new PausingStore(real, "bookingsForRoom", 1)   // park call 1
    //   new PausingStore(real, "insert", 3)            // park call 3 of insert
    //
    // Valid method names are the four on BookingStore: hasOverlap, insert,
    // bookingsForRoom, cancelAndPromote. On the chosen call the wrapper
    // delegates, then parks the calling thread before returning the answer it
    // already computed. The gate fires once and once only.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Wiring example 3: the two-thread pattern
    //
    // Every wait below is bounded, so this pattern cannot hang no matter what
    // the code under test does. Do not add a sleep to it; a sleep is a guess,
    // and the latches are not. (Your test method needs
    // `throws InterruptedException` for the joins.)
    //
    // The scenario here is deliberately harmless: it parks a reader in
    // bookingsForRoom (the aim from example 1) while a writer books a
    // different room. It exists to show the choreography, not a bug.
    //
    //   Thread reader = new Thread(() -> service.listBookings("WEH-5409"));
    //   reader.start();
    //
    //   gated.awaitPaused();          // returns when a thread is parked, or
    //                                 // false after PausingStore.TIMEOUT_MILLIS
    //                                 // if the gated call never happened
    //
    //   Thread writer = new Thread(() -> service.book("GHC-4301", 720, 780));
    //   writer.start();
    //   writer.join(PausingStore.TIMEOUT_MILLIS);
    //
    //   gated.release();              // the parked reader returns and finishes
    //   reader.join(PausingStore.TIMEOUT_MILLIS);
    //                                 // note the writer needed no release: it
    //                                 // was never blocked by the parked reader
    //
    // Then assert on the end state, never on the order things happened in. For
    // the harmless scenario above, the writer's booking is stored whatever the
    // reader saw:
    //
    //   assertEquals(1, real.bookingsForRoom("GHC-4301").size());
    //
    // An end-state assertion still means something after you change the code
    // underneath it. A schedule assertion does not.
    // ------------------------------------------------------------------
}
