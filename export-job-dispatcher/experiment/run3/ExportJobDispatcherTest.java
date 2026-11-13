package app.penpot.exporter.dispatcher;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    @DisplayName("registerWorker accepts a valid worker id and starts it with zero jobs")
    void registerWorkerValid() {
        dispatcher.registerWorker("w1");
        assertEquals(0, dispatcher.getActiveJobCount("w1"));
    }

    @Test
    @DisplayName("registerWorker rejects null id")
    void registerWorkerNull() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.registerWorker(null));
    }

    @Test
    @DisplayName("registerWorker rejects empty id")
    void registerWorkerEmpty() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.registerWorker(""));
    }

    @Test
    @DisplayName("registerWorker rejects blank id")
    void registerWorkerBlank() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.registerWorker("   "));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.registerWorker("\t\n"));
    }

    @Test
    @DisplayName("registerWorker rejects duplicate id")
    void registerWorkerDuplicate() {
        dispatcher.registerWorker("w1");
        assertThrows(IllegalArgumentException.class, () -> dispatcher.registerWorker("w1"));
    }

    @Test
    @DisplayName("duplicate registration does not reset the active job count")
    void duplicateRegistrationKeepsState() {
        dispatcher.registerWorker("w1");
        dispatcher.dispatch("j1");
        assertThrows(IllegalArgumentException.class, () -> dispatcher.registerWorker("w1"));
        assertEquals(1, dispatcher.getActiveJobCount("w1"));
    }

    @Test
    @DisplayName("multiple distinct workers can be registered")
    void registerMultipleWorkers() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");
        dispatcher.registerWorker("w3");
        assertEquals(0, dispatcher.getActiveJobCount("w1"));
        assertEquals(0, dispatcher.getActiveJobCount("w2"));
        assertEquals(0, dispatcher.getActiveJobCount("w3"));
    }

    // ---------- dispatch: basics ----------

    @Test
    @DisplayName("dispatch returns the assigned worker id and records the assignment")
    void dispatchAssignsWorker() {
        dispatcher.registerWorker("w1");
        Optional<String> result = dispatcher.dispatch("j1");
        assertTrue(result.isPresent());
        assertEquals("w1", result.get());
        assertEquals(Optional.of("w1"), dispatcher.getAssignedWorker("j1"));
        assertEquals(1, dispatcher.getActiveJobCount("w1"));
    }

    @Test
    @DisplayName("dispatch assigns a job to exactly one worker")
    void dispatchExactlyOneWorker() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");
        dispatcher.registerWorker("w3");
        dispatcher.dispatch("j1");
        int total = dispatcher.getActiveJobCount("w1")
                + dispatcher.getActiveJobCount("w2")
                + dispatcher.getActiveJobCount("w3");
        assertEquals(1, total);
    }

    @Test
    @DisplayName("dispatch rejects null job id")
    void dispatchNullJob() {
        dispatcher.registerWorker("w1");
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(null));
    }

    @Test
    @DisplayName("dispatch rejects blank job id")
    void dispatchBlankJob() {
        dispatcher.registerWorker("w1");
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(""));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch("  "));
    }

    @Test
    @DisplayName("dispatch with no workers returns empty")
    void dispatchNoWorkers() {
        assertEquals(Optional.empty(), dispatcher.dispatch("j1"));
        assertEquals(Optional.empty(), dispatcher.getAssignedWorker("j1"));
    }

    // ---------- dispatch: load balancing ----------

    @Test
    @DisplayName("tie goes to the first registered worker")
    void tieGoesToFirstRegistered() {
        dispatcher.registerWorker("b");
        dispatcher.registerWorker("a");
        dispatcher.registerWorker("c");
        assertEquals(Optional.of("b"), dispatcher.dispatch("j1"));
    }

    @Test
    @DisplayName("jobs are spread across workers by fewest active jobs")
    void roundRobinLikeSpread() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");
        dispatcher.registerWorker("w3");

        assertEquals(Optional.of("w1"), dispatcher.dispatch("j1"));
        assertEquals(Optional.of("w2"), dispatcher.dispatch("j2"));
        assertEquals(Optional.of("w3"), dispatcher.dispatch("j3"));
        assertEquals(Optional.of("w1"), dispatcher.dispatch("j4"));
        assertEquals(Optional.of("w2"), dispatcher.dispatch("j5"));
        assertEquals(Optional.of("w3"), dispatcher.dispatch("j6"));
    }

    @Test
    @DisplayName("worker with the fewest active jobs is chosen after a job finishes")
    void fewestActiveChosenAfterFinish() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");
        dispatcher.registerWorker("w3");

        dispatcher.dispatch("j1"); // w1
        dispatcher.dispatch("j2"); // w2
        dispatcher.dispatch("j3"); // w3
        dispatcher.dispatch("j4"); // w1 (2)
        dispatcher.dispatch("j5"); // w2 (2)
        // w1=2, w2=2, w3=1
        dispatcher.markJobFinished("j2"); // w2=1
        // w1=2, w2=1, w3=1 -> tie between w2 and w3, w2 registered first
        assertEquals(Optional.of("w2"), dispatcher.dispatch("j7"));
        // w1=2, w2=2, w3=1
        assertEquals(Optional.of("w3"), dispatcher.dispatch("j8"));
    }

    @Test
    @DisplayName("a less loaded later worker beats an earlier more loaded worker")
    void lessLoadedLaterWorkerWins() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");
        dispatcher.dispatch("j1"); // w1
        assertEquals(Optional.of("w2"), dispatcher.dispatch("j2"));
        dispatcher.markJobFinished("j1"); // w1=0, w2=1
        assertEquals(Optional.of("w1"), dispatcher.dispatch("j3"));
    }

    @Test
    @DisplayName("a worker registered later starts at zero and gets the next jobs")
    void lateWorkerGetsJobs() {
        dispatcher.registerWorker("w1");
        dispatcher.dispatch("j1");
        dispatcher.dispatch("j2");
        dispatcher.registerWorker("w2");
        assertEquals(Optional.of("w2"), dispatcher.dispatch("j3"));
        assertEquals(Optional.of("w2"), dispatcher.dispatch("j4"));
        assertEquals(Optional.empty(), dispatcher.dispatch("j5"));
    }

    // ---------- dispatch: capacity ----------

    @Test
    @DisplayName("dispatch returns empty when the only worker is full")
    void singleWorkerFull() {
        dispatcher.registerWorker("w1");
        assertTrue(dispatcher.dispatch("j1").isPresent());
        assertTrue(dispatcher.dispatch("j2").isPresent());
        assertEquals(Optional.empty(), dispatcher.dispatch("j3"));
        assertEquals(2, dispatcher.getActiveJobCount("w1"));
        assertEquals(Optional.empty(), dispatcher.getAssignedWorker("j3"));
    }

    @Test
    @DisplayName("dispatch returns empty when all workers are full")
    void allWorkersFull() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");
        for (int i = 0; i < 4; i++) {
            assertTrue(dispatcher.dispatch("j" + i).isPresent());
        }
        assertEquals(Optional.empty(), dispatcher.dispatch("extra"));
        assertEquals(2, dispatcher.getActiveJobCount("w1"));
        assertEquals(2, dispatcher.getActiveJobCount("w2"));
    }

    @Test
    @DisplayName("no worker ever exceeds maxJobsPerWorker")
    void neverExceedsMax() {
        int max = 3;
        ExportJobDispatcher d = new ExportJobDispatcher(max);
        d.registerWorker("w1");
        d.registerWorker("w2");
        d.registerWorker("w3");
        int accepted = 0;
        for (int i = 0; i < 50; i++) {
            if (d.dispatch("job-" + i).isPresent()) {
                accepted++;
            }
            assertTrue(d.getActiveJobCount("w1") <= max);
            assertTrue(d.getActiveJobCount("w2") <= max);
            assertTrue(d.getActiveJobCount("w3") <= max);
        }
        assertEquals(9, accepted);
    }

    @Test
    @DisplayName("maxJobsPerWorker of one allows a single job per worker")
    void maxOne() {
        ExportJobDispatcher d = new ExportJobDispatcher(1);
        d.registerWorker("w1");
        d.registerWorker("w2");
        assertEquals(Optional.of("w1"), d.dispatch("j1"));
        assertEquals(Optional.of("w2"), d.dispatch("j2"));
        assertEquals(Optional.empty(), d.dispatch("j3"));
    }

    @Test
    @DisplayName("full workers are skipped in favour of workers with free slots")
    void fullWorkersSkipped() {
        ExportJobDispatcher d = new ExportJobDispatcher(1);
        d.registerWorker("w1");
        d.registerWorker("w2");
        d.dispatch("j1"); // w1
        d.dispatch("j2"); // w2
        d.markJobFinished("j2"); // w2 free
        assertEquals(Optional.of("w2"), d.dispatch("j3"));
    }

    // ---------- markJobFinished ----------

    @Test
    @DisplayName("markJobFinished frees a slot so a later dispatch can use it")
    void finishFreesSlot() {
        dispatcher.registerWorker("w1");
        dispatcher.dispatch("j1");
        dispatcher.dispatch("j2");
        assertEquals(Optional.empty(), dispatcher.dispatch("j3"));

        dispatcher.markJobFinished("j1");
        assertEquals(1, dispatcher.getActiveJobCount("w1"));
        assertEquals(Optional.of("w1"), dispatcher.dispatch("j3"));
        assertEquals(2, dispatcher.getActiveJobCount("w1"));
    }

    @Test
    @DisplayName("markJobFinished removes the job assignment")
    void finishRemovesAssignment() {
        dispatcher.registerWorker("w1");
        dispatcher.dispatch("j1");
        dispatcher.markJobFinished("j1");
        assertEquals(Optional.empty(), dispatcher.getAssignedWorker("j1"));
        assertEquals(0, dispatcher.getActiveJobCount("w1"));
    }

    @Test
    @DisplayName("markJobFinished rejects null job id")
    void finishNull() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.markJobFinished(null));
    }

    @Test
    @DisplayName("markJobFinished rejects blank job id")
    void finishBlank() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.markJobFinished(""));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.markJobFinished("   "));
    }

    @Test
    @DisplayName("markJobFinished rejects unknown job id")
    void finishUnknown() {
        dispatcher.registerWorker("w1");
        assertThrows(IllegalArgumentException.class, () -> dispatcher.markJobFinished("nope"));
    }

    @Test
    @DisplayName("markJobFinished rejects a job that was never accepted because workers were full")
    void finishRejectedJob() {
        dispatcher.registerWorker("w1");
        dispatcher.dispatch("j1");
        dispatcher.dispatch("j2");
        assertEquals(Optional.empty(), dispatcher.dispatch("j3"));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.markJobFinished("j3"));
        assertEquals(2, dispatcher.getActiveJobCount("w1"));
    }

    @Test
    @DisplayName("markJobFinished twice fails the second time and does not go negative")
    void finishTwice() {
        dispatcher.registerWorker("w1");
        dispatcher.dispatch("j1");
        dispatcher.markJobFinished("j1");
        assertThrows(IllegalArgumentException.class, () -> dispatcher.markJobFinished("j1"));
        assertEquals(0, dispatcher.getActiveJobCount("w1"));
    }

    @Test
    @DisplayName("a finished job id can be dispatched again")
    void redispatchAfterFinish() {
        dispatcher.registerWorker("w1");
        dispatcher.dispatch("j1");
        dispatcher.markJobFinished("j1");
        assertEquals(Optional.of("w1"), dispatcher.dispatch("j1"));
        assertEquals(1, dispatcher.getActiveJobCount("w1"));
    }

    // ---------- getActiveJobCount / getAssignedWorker ----------

    @Test
    @DisplayName("getActiveJobCount rejects null, blank and unknown worker ids")
    void activeCountInvalid() {
        dispatcher.registerWorker("w1");
        assertThrows(IllegalArgumentException.class, () -> dispatcher.getActiveJobCount(null));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.getActiveJobCount(" "));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.getActiveJobCount("unknown"));
    }

    @Test
    @DisplayName("getAssignedWorker returns empty for unknown job and rejects null or blank")
    void assignedWorkerEdge() {
        assertEquals(Optional.empty(), dispatcher.getAssignedWorker("unknown"));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.getAssignedWorker(null));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.getAssignedWorker(" "));
    }

    @Test
    @DisplayName("each job is tracked against its own worker")
    void assignmentsTrackedPerJob() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");
        dispatcher.dispatch("j1");
        dispatcher.dispatch("j2");
        assertEquals(Optional.of("w1"), dispatcher.getAssignedWorker("j1"));
        assertEquals(Optional.of("w2"), dispatcher.getAssignedWorker("j2"));
    }

    // ---------- misc / invariants ----------

    @Test
    @DisplayName("dispatching the same job id twice does not double-assign it")
    void duplicateJobDispatch() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");
        dispatcher.dispatch("j1");
        // Behaviour is either rejection or an idempotent result; in both cases
        // the job must be tracked against exactly one worker with a single slot used.
        try {
            dispatcher.dispatch("j1");
        } catch (IllegalArgumentException expected) {
            // acceptable
        }
        int total = dispatcher.getActiveJobCount("w1") + dispatcher.getActiveJobCount("w2");
        assertEquals(1, total);
    }

    @Test
    @DisplayName("worker ids are case sensitive")
    void caseSensitiveWorkerIds() {
        dispatcher.registerWorker("w1");
        assertDoesNotThrow(() -> dispatcher.registerWorker("W1"));
        assertEquals(0, dispatcher.getActiveJobCount("W1"));
    }

    @Test
    @DisplayName("every dispatched job maps to a registered worker and counts add up")
    void invariantsHold() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");
        dispatcher.registerWorker("w3");
        Set<String> workers = new HashSet<>(Set.of("w1", "w2", "w3"));
        Map<String, Integer> expected = new HashMap<>();
        for (int i = 0; i < 6; i++) {
            String job = "j" + i;
            Optional<String> w = dispatcher.dispatch(job);
            assertTrue(w.isPresent());
            assertTrue(workers.contains(w.get()));
            assertEquals(w, dispatcher.getAssignedWorker(job));
            expected.merge(w.get(), 1, Integer::sum);
        }
        for (String w : workers) {
            assertEquals(expected.get(w), dispatcher.getActiveJobCount(w));
        }
        assertFalse(dispatcher.dispatch("overflow").isPresent());
    }
}
