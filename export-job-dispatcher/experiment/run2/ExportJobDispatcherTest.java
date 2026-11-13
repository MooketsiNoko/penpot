package app.penpot.exporter.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExportJobDispatcherTest {

    private ExportJobDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new ExportJobDispatcher(2);
    }

    // ---------- registerWorker ----------

    @Test
    void registerWorker_newWorkerStartsWithZeroActiveJobs() {
        dispatcher.registerWorker("w1");
        assertEquals(0, dispatcher.getActiveJobCount("w1"));
    }

    @Test
    void registerWorker_nullIdRejected() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.registerWorker(null));
    }

    @Test
    void registerWorker_emptyIdRejected() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.registerWorker(""));
    }

    @Test
    void registerWorker_blankIdRejected() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.registerWorker("   "));
    }

    @Test
    void registerWorker_duplicateIdRejected() {
        dispatcher.registerWorker("w1");
        assertThrows(IllegalArgumentException.class, () -> dispatcher.registerWorker("w1"));
    }

    @Test
    void registerWorker_duplicateDoesNotResetActiveJobs() {
        dispatcher.registerWorker("w1");
        dispatcher.dispatch("j1");
        assertThrows(IllegalArgumentException.class, () -> dispatcher.registerWorker("w1"));
        assertEquals(1, dispatcher.getActiveJobCount("w1"));
    }

    @Test
    void registerWorker_multipleDistinctWorkersAccepted() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");
        dispatcher.registerWorker("w3");
        assertEquals(0, dispatcher.getActiveJobCount("w1"));
        assertEquals(0, dispatcher.getActiveJobCount("w2"));
        assertEquals(0, dispatcher.getActiveJobCount("w3"));
    }

    // ---------- dispatch basics ----------

    @Test
    void dispatch_assignsJobToSingleWorkerAndReturnsItsId() {
        dispatcher.registerWorker("w1");
        Optional<String> result = dispatcher.dispatch("j1");
        assertTrue(result.isPresent());
        assertEquals("w1", result.get());
        assertEquals(Optional.of("w1"), dispatcher.getAssignedWorker("j1"));
        assertEquals(1, dispatcher.getActiveJobCount("w1"));
    }

    @Test
    void dispatch_jobCountedOnExactlyOneWorker() {
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
    void dispatch_noWorkersReturnsEmpty() {
        assertEquals(Optional.empty(), dispatcher.dispatch("j1"));
        assertEquals(Optional.empty(), dispatcher.getAssignedWorker("j1"));
    }

    @Test
    void dispatch_nullJobIdRejected() {
        dispatcher.registerWorker("w1");
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(null));
    }

    @Test
    void dispatch_blankJobIdRejected() {
        dispatcher.registerWorker("w1");
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(" "));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(""));
    }

    @Test
    void dispatch_invalidJobDoesNotChangeCounts() {
        dispatcher.registerWorker("w1");
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(null));
        assertEquals(0, dispatcher.getActiveJobCount("w1"));
    }

    // ---------- selection policy ----------

    @Test
    void dispatch_tieGoesToFirstRegisteredWorker() {
        dispatcher.registerWorker("w2");
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w3");
        assertEquals(Optional.of("w2"), dispatcher.dispatch("j1"));
    }

    @Test
    void dispatch_picksWorkerWithFewestActiveJobs() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");
        assertEquals(Optional.of("w1"), dispatcher.dispatch("j1"));
        assertEquals(Optional.of("w2"), dispatcher.dispatch("j2"));
        assertEquals(Optional.of("w1"), dispatcher.dispatch("j3"));
        assertEquals(Optional.of("w2"), dispatcher.dispatch("j4"));
    }

    @Test
    void dispatch_prefersLessLoadedWorkerOverEarlierRegistered() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");
        dispatcher.dispatch("j1"); // w1
        dispatcher.dispatch("j2"); // w2
        dispatcher.dispatch("j3"); // w1 (tie -> first)
        dispatcher.markJobFinished("j2"); // w2 now 0, w1 has 2
        assertEquals(Optional.of("w2"), dispatcher.dispatch("j4"));
    }

    @Test
    void dispatch_afterFinishTieAmongWorkersGoesToFirstRegistered() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");
        dispatcher.dispatch("j1"); // w1
        dispatcher.dispatch("j2"); // w2
        dispatcher.markJobFinished("j1");
        dispatcher.markJobFinished("j2");
        assertEquals(Optional.of("w1"), dispatcher.dispatch("j3"));
    }

    // ---------- capacity ----------

    @Test
    void dispatch_neverExceedsMaxJobsPerWorker() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");
        for (int i = 0; i < 20; i++) {
            dispatcher.dispatch("j" + i);
            assertTrue(dispatcher.getActiveJobCount("w1") <= 2);
            assertTrue(dispatcher.getActiveJobCount("w2") <= 2);
        }
        assertEquals(2, dispatcher.getActiveJobCount("w1"));
        assertEquals(2, dispatcher.getActiveJobCount("w2"));
    }

    @Test
    void dispatch_allWorkersFullReturnsEmpty() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");
        for (int i = 0; i < 4; i++) {
            assertTrue(dispatcher.dispatch("j" + i).isPresent());
        }
        assertEquals(Optional.empty(), dispatcher.dispatch("j4"));
        assertEquals(Optional.empty(), dispatcher.getAssignedWorker("j4"));
        assertEquals(2, dispatcher.getActiveJobCount("w1"));
        assertEquals(2, dispatcher.getActiveJobCount("w2"));
    }

    @Test
    void dispatch_capacityOneAllowsSingleJobPerWorker() {
        ExportJobDispatcher d = new ExportJobDispatcher(1);
        d.registerWorker("w1");
        assertEquals(Optional.of("w1"), d.dispatch("j1"));
        assertEquals(Optional.empty(), d.dispatch("j2"));
    }

    @Test
    void dispatch_workerRegisteredLaterReceivesJobsWhenOthersFull() {
        dispatcher.registerWorker("w1");
        dispatcher.dispatch("j1");
        dispatcher.dispatch("j2");
        assertEquals(Optional.empty(), dispatcher.dispatch("j3"));
        dispatcher.registerWorker("w2");
        assertEquals(Optional.of("w2"), dispatcher.dispatch("j3"));
    }

    @Test
    void dispatch_distributionAcrossWorkersIsBalanced() {
        ExportJobDispatcher d = new ExportJobDispatcher(10);
        d.registerWorker("a");
        d.registerWorker("b");
        d.registerWorker("c");
        Map<String, Integer> counts = new HashMap<>();
        List<String> order = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            String w = d.dispatch("j" + i).orElseThrow();
            counts.merge(w, 1, Integer::sum);
            order.add(w);
        }
        assertEquals(3, counts.get("a"));
        assertEquals(3, counts.get("b"));
        assertEquals(3, counts.get("c"));
        assertEquals(List.of("a", "b", "c", "a", "b", "c", "a", "b", "c"), order);
    }

    // ---------- markJobFinished ----------

    @Test
    void markJobFinished_freesSlotForLaterDispatch() {
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
    void markJobFinished_removesAssignment() {
        dispatcher.registerWorker("w1");
        dispatcher.dispatch("j1");
        dispatcher.markJobFinished("j1");
        assertEquals(Optional.empty(), dispatcher.getAssignedWorker("j1"));
        assertEquals(0, dispatcher.getActiveJobCount("w1"));
    }

    @Test
    void markJobFinished_onlyAffectsAssignedWorker() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");
        dispatcher.dispatch("j1"); // w1
        dispatcher.dispatch("j2"); // w2
        dispatcher.markJobFinished("j1");
        assertEquals(0, dispatcher.getActiveJobCount("w1"));
        assertEquals(1, dispatcher.getActiveJobCount("w2"));
    }

    @Test
    void markJobFinished_nullRejected() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.markJobFinished(null));
    }

    @Test
    void markJobFinished_blankRejected() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.markJobFinished(""));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.markJobFinished("  "));
    }

    @Test
    void markJobFinished_unknownJobRejected() {
        dispatcher.registerWorker("w1");
        assertThrows(IllegalArgumentException.class, () -> dispatcher.markJobFinished("nope"));
        assertEquals(0, dispatcher.getActiveJobCount("w1"));
    }

    @Test
    void markJobFinished_calledTwiceRejectedSecondTime() {
        dispatcher.registerWorker("w1");
        dispatcher.dispatch("j1");
        dispatcher.markJobFinished("j1");
        assertThrows(IllegalArgumentException.class, () -> dispatcher.markJobFinished("j1"));
        assertEquals(0, dispatcher.getActiveJobCount("w1"));
    }

    @Test
    void markJobFinished_jobRejectedForFullPoolIsUnknown() {
        dispatcher.registerWorker("w1");
        dispatcher.dispatch("j1");
        dispatcher.dispatch("j2");
        dispatcher.dispatch("j3"); // rejected, empty
        assertThrows(IllegalArgumentException.class, () -> dispatcher.markJobFinished("j3"));
        assertEquals(2, dispatcher.getActiveJobCount("w1"));
    }

    @Test
    void markJobFinished_finishedJobIdCanBeDispatchedAgain() {
        dispatcher.registerWorker("w1");
        dispatcher.dispatch("j1");
        dispatcher.markJobFinished("j1");
        assertEquals(Optional.of("w1"), dispatcher.dispatch("j1"));
        assertEquals(1, dispatcher.getActiveJobCount("w1"));
    }

    // ---------- getters ----------

    @Test
    void getActiveJobCount_unknownWorkerRejected() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.getActiveJobCount("ghost"));
    }

    @Test
    void getActiveJobCount_nullWorkerRejected() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.getActiveJobCount(null));
    }

    @Test
    void getAssignedWorker_unknownJobIsEmpty() {
        assertFalse(dispatcher.getAssignedWorker("unknown").isPresent());
    }

    @Test
    void getAssignedWorker_tracksEachJobSeparately() {
        dispatcher.registerWorker("w1");
        dispatcher.registerWorker("w2");
        dispatcher.dispatch("j1");
        dispatcher.dispatch("j2");
        assertEquals(Optional.of("w1"), dispatcher.getAssignedWorker("j1"));
        assertEquals(Optional.of("w2"), dispatcher.getAssignedWorker("j2"));
    }

    // ---------- constructor ----------

    @Test
    void constructor_nonPositiveCapacityRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ExportJobDispatcher(0));
        assertThrows(IllegalArgumentException.class, () -> new ExportJobDispatcher(-1));
    }
}
