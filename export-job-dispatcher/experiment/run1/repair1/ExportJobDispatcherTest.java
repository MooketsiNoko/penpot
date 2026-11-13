package app.penpot.exporter.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExportJobDispatcherTest {

    private ExportJobDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new ExportJobDispatcher(2);
    }

    // ---------- registerWorker ----------

    @Test
    @DisplayName("registerWorker adds a worker with zero active jobs")
    void registerWorkerStartsWithZeroJobs() {
        dispatcher.registerWorker("w1");
        assertEquals(0, dispatcher.getActiveJobCount("w1"));
    }

    @Test
    @DisplayName("registerWorker rejects null worker id")
    void registerWorkerRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.registerWorker(null));
    }

    @Test
    @DisplayName("registerWorker rejects empty and blank worker ids")
    void registerWorkerRejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.registerWorker(""));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.registerWorker("   "));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.registerWorker("\t\n"));
    }

    @Test
    @DisplayName("registerWorker rejects duplicate worker id")
    void registerWorkerRejectsDuplicate() {
        dispatcher.registerWorker("w1");
        assertThrows(IllegalArgumentException.class, () -> dispatcher.registerWorker("w1"));
    }

    @Test
    @DisplayName("a rejected duplicate does not reset the existing worker's load")
    void duplicateRegistrationKeepsExistingLoad() {
        dispatcher.registerWorker("w1");
        dispatcher.dispatch("j1");
        assertThrows(IllegalArgumentException.class, () -> dispatcher.registerWorker("w1"));
        assertEquals(1, dispatcher.getActiveJobCount("w1"));
    }

    // ---------- dispatch basics ----------

    @Test
    @DisplayName("dispatch returns the id of the worker that got the job")
    void dispatchReturnsWorkerId() {
        dispatcher.registerWorker("w1");
        Optional<String> result = dispatcher.dispatch("j1");
        assertEquals(Optional.of("w1"), result);
    }

    @Test
    @DisplayName("dispatch assigns the job to exactly one worker")
    void dispatchAssignsToExactlyOneWorker() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");
        dispatcher.registerWorker("w3");

        String assigned = dispatcher.dispatch("j1").orElseThrow();

        int total = dispatcher.getActiveJobCount("w1")
                + dispatcher.getActiveJobCount("w2")
                + dispatcher.getActiveJobCount("w3");
        assertEquals(1, total);
        assertEquals(1, dispatcher.getActiveJobCount(assigned));
        assertEquals(Optional.of(assigned), dispatcher.getAssignedWorker("j1"));
    }

    @Test
    @DisplayName("dispatch with no workers returns empty")
    void dispatchWithNoWorkers() {
        assertEquals(Optional.empty(), dispatcher.dispatch("j1"));
        assertEquals(Optional.empty(), dispatcher.getAssignedWorker("j1"));
    }

    @Test
    @DisplayName("dispatch rejects null and blank job ids")
    void dispatchRejectsInvalidJobId() {
        dispatcher.registerWorker("w1");
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(null));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(""));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch("  "));
        assertEquals(0, dispatcher.getActiveJobCount("w1"));
    }

    // ---------- load balancing ----------

    @Test
    @DisplayName("ties go to the first registered worker")
    void tieGoesToFirstRegistered() {
        dispatcher.registerWorker("w2");
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w3");

        assertEquals(Optional.of("w2"), dispatcher.dispatch("j1"));
    }

    @Test
    @DisplayName("worker with the fewest active jobs is chosen")
    void leastLoadedWorkerChosen() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");
        dispatcher.registerWorker("w3");

        assertEquals(Optional.of("w1"), dispatcher.dispatch("j1"));
        assertEquals(Optional.of("w2"), dispatcher.dispatch("j2"));
        assertEquals(Optional.of("w3"), dispatcher.dispatch("j3"));
        // all at 1, tie goes to first registered
        assertEquals(Optional.of("w1"), dispatcher.dispatch("j4"));
        assertEquals(Optional.of("w2"), dispatcher.dispatch("j5"));
    }

    @Test
    @DisplayName("least loaded worker is preferred even if registered later")
    void lateRegisteredWorkerWithFewerJobsWins() {
        dispatcher.registerWorker("w1");
        dispatcher.dispatch("j1");
        dispatcher.dispatch("j2"); // w1 now has 2 (full)
        dispatcher.registerWorker("w2");

        assertEquals(Optional.of("w2"), dispatcher.dispatch("j3"));
    }

    @Test
    @DisplayName("after a job finishes, the freed worker becomes least loaded")
    void freedWorkerBecomesLeastLoaded() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");

        dispatcher.dispatch("j1"); // w1
        dispatcher.dispatch("j2"); // w2
        dispatcher.dispatch("j3"); // w1 (2)
        dispatcher.markJobFinished("j2"); // w2 (0)

        assertEquals(Optional.of("w2"), dispatcher.dispatch("j4"));
    }

    @Test
    @DisplayName("ties among partially loaded workers still go to the first registered")
    void tieAfterFinishGoesToFirst() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");

        dispatcher.dispatch("j1"); // w1
        dispatcher.dispatch("j2"); // w2
        dispatcher.dispatch("j3"); // w1 (2)
        dispatcher.dispatch("j4"); // w2 (2)
        dispatcher.markJobFinished("j3"); // w1 (1)
        dispatcher.markJobFinished("j4"); // w2 (1)

        assertEquals(Optional.of("w1"), dispatcher.dispatch("j5"));
    }

    // ---------- capacity ----------

    @Test
    @DisplayName("dispatch returns empty when all workers are full")
    void dispatchEmptyWhenAllFull() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");

        for (int i = 0; i < 4; i++) {
            assertTrue(dispatcher.dispatch("j" + i).isPresent());
        }
        assertEquals(Optional.empty(), dispatcher.dispatch("overflow"));
        assertEquals(Optional.empty(), dispatcher.getAssignedWorker("overflow"));
    }

    @Test
    @DisplayName("no worker ever exceeds maxJobsPerWorker")
    void neverExceedsMax() {
        int max = 3;
        ExportJobDispatcher d = new ExportJobDispatcher(max);
        d.registerWorker("a");
        d.registerWorker("b");
        d.registerWorker("c");

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 50; i++) {
            d.dispatch("job-" + i).ifPresent(w -> counts.merge(w, 1, Integer::sum));
            for (String w : new String[] {"a", "b", "c"}) {
                assertTrue(d.getActiveJobCount(w) <= max);
            }
        }
        assertEquals(max, d.getActiveJobCount("a"));
        assertEquals(max, d.getActiveJobCount("b"));
        assertEquals(max, d.getActiveJobCount("c"));
        assertEquals(9, counts.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    @DisplayName("a rejected dispatch leaves no job assignment behind")
    void rejectedDispatchLeavesNoState() {
        ExportJobDispatcher d = new ExportJobDispatcher(1);
        d.registerWorker("w1");
        d.dispatch("j1");
        assertEquals(Optional.empty(), d.dispatch("j2"));
        assertEquals(1, d.getActiveJobCount("w1"));
        assertEquals(Optional.empty(), d.getAssignedWorker("j2"));
    }

    @Test
    @DisplayName("capacity of one allows a single job per worker")
    void capacityOne() {
        ExportJobDispatcher d = new ExportJobDispatcher(1);
        d.registerWorker("w1");
        d.registerWorker("w2");
        assertEquals(Optional.of("w1"), d.dispatch("j1"));
        assertEquals(Optional.of("w2"), d.dispatch("j2"));
        assertTrue(d.dispatch("j3").isEmpty());
    }

    // ---------- markJobFinished ----------

    @Test
    @DisplayName("markJobFinished frees a slot so a later dispatch can use it")
    void finishFreesSlot() {
        ExportJobDispatcher d = new ExportJobDispatcher(1);
        d.registerWorker("w1");
        d.dispatch("j1");
        assertTrue(d.dispatch("j2").isEmpty());

        d.markJobFinished("j1");

        assertEquals(0, d.getActiveJobCount("w1"));
        assertEquals(Optional.of("w1"), d.dispatch("j2"));
    }

    @Test
    @DisplayName("markJobFinished clears the job's assignment")
    void finishClearsAssignment() {
        dispatcher.registerWorker("w1");
        dispatcher.dispatch("j1");
        assertEquals(Optional.of("w1"), dispatcher.getAssignedWorker("j1"));

        dispatcher.markJobFinished("j1");

        assertEquals(Optional.empty(), dispatcher.getAssignedWorker("j1"));
    }

    @Test
    @DisplayName("markJobFinished rejects null and blank job ids")
    void finishRejectsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.markJobFinished(null));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.markJobFinished(""));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.markJobFinished("   "));
    }

    @Test
    @DisplayName("markJobFinished rejects unknown job ids")
    void finishRejectsUnknown() {
        dispatcher.registerWorker("w1");
        assertThrows(IllegalArgumentException.class, () -> dispatcher.markJobFinished("nope"));
    }

    @Test
    @DisplayName("finishing the same job twice fails the second time and does not underflow")
    void finishTwiceFails() {
        dispatcher.registerWorker("w1");
        dispatcher.dispatch("j1");
        dispatcher.markJobFinished("j1");

        assertThrows(IllegalArgumentException.class, () -> dispatcher.markJobFinished("j1"));
        assertEquals(0, dispatcher.getActiveJobCount("w1"));
    }

    @Test
    @DisplayName("finishing a job only affects the worker that held it")
    void finishOnlyAffectsOwner() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");
        dispatcher.dispatch("j1"); // w1
        dispatcher.dispatch("j2"); // w2

        dispatcher.markJobFinished("j1");

        assertEquals(0, dispatcher.getActiveJobCount("w1"));
        assertEquals(1, dispatcher.getActiveJobCount("w2"));
        assertEquals(Optional.of("w2"), dispatcher.getAssignedWorker("j2"));
    }

    // ---------- getActiveJobCount / getAssignedWorker ----------

    @Test
    @DisplayName("getActiveJobCount reports the load of each registered worker")
    void activeJobCountForRegisteredWorkers() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");
        assertEquals(0, dispatcher.getActiveJobCount("w1"));
        assertEquals(0, dispatcher.getActiveJobCount("w2"));

        dispatcher.dispatch("j1"); // w1
        assertEquals(1, dispatcher.getActiveJobCount("w1"));
        assertEquals(0, dispatcher.getActiveJobCount("w2"));
    }

    @Test
    @DisplayName("getAssignedWorker returns empty for unknown jobs")
    void assignedWorkerUnknownJob() {
        dispatcher.registerWorker("w1");
        assertFalse(dispatcher.getAssignedWorker("unknown").isPresent());
    }

    @Test
    @DisplayName("distinct job ids are tracked independently")
    void distinctJobsTrackedIndependently() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");
        dispatcher.dispatch("j1");
        dispatcher.dispatch("j2");

        assertEquals(Optional.of("w1"), dispatcher.getAssignedWorker("j1"));
        assertEquals(Optional.of("w2"), dispatcher.getAssignedWorker("j2"));
        assertEquals(1, dispatcher.getActiveJobCount("w1"));
        assertEquals(1, dispatcher.getActiveJobCount("w2"));
    }

    @Test
    @DisplayName("a finished job id can be dispatched again")
    void finishedJobCanBeRedispatched() {
        dispatcher.registerWorker("w1");
        dispatcher.dispatch("j1");
        dispatcher.markJobFinished("j1");

        assertEquals(Optional.of("w1"), dispatcher.dispatch("j1"));
        assertEquals(1, dispatcher.getActiveJobCount("w1"));
    }
}
