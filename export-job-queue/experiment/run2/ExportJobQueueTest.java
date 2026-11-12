package app.penpot.exporter.queue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.stubbing.Answer;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ExportJobQueueTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration LONG_TIMEOUT = Duration.ofSeconds(10);
    private static final byte[] BYTES = {1, 2, 3, 4};

    private Renderer renderer;
    private ExportJobQueue queue;
    /** Shared gate that blocking renders wait on; always opened in tearDown. */
    private CountDownLatch gate;

    @BeforeEach
    void setUp() {
        renderer = mock(Renderer.class);
        gate = new CountDownLatch(1);
    }

    @AfterEach
    void tearDown() {
        gate.countDown();
        if (queue != null) {
            queue.close();
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ExportJobQueue newQueue(int maxConcurrent, int maxRetries, Duration timeout) {
        queue = new ExportJobQueue(renderer, maxConcurrent, maxRetries, timeout);
        return queue;
    }

    private static ExportRequest req(String fileId) {
        return new ExportRequest(fileId, ExportFormat.PNG);
    }

    /** Render answer that blocks on the shared gate, then returns BYTES. */
    private Answer<byte[]> blockOnGate() {
        return inv -> {
            gate.await();
            return BYTES;
        };
    }

    /** Render answer that sleeps for a long time and records interruption. */
    private static Answer<byte[]> hang(CountDownLatch started, CountDownLatch interrupted) {
        return inv -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
            return BYTES;
        };
    }

    private static void awaitTerminal(ExportJob job) throws InterruptedException {
        assertTrue(job.awaitCompletion(WAIT), "job " + job.getId() + " did not finish in time");
    }

    private static void waitUntil(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("Timed out waiting: " + message);
            }
            Thread.sleep(10);
        }
    }

    // ------------------------------------------------------------------
    // Constructor and configuration
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        void storesConfiguration() {
            newQueue(4, 2, Duration.ofSeconds(3));
            assertEquals(4, queue.getMaxConcurrent());
            assertEquals(2, queue.getMaxRetries());
            assertEquals(Duration.ofSeconds(3), queue.getTimeout());
        }

        @Test
        void acceptsBoundaryValues() {
            newQueue(1, 0, Duration.ofNanos(1));
            assertEquals(1, queue.getMaxConcurrent());
            assertEquals(0, queue.getMaxRetries());
            assertEquals(Duration.ofNanos(1), queue.getTimeout());
        }

        @Test
        void newQueueIsEmpty() {
            newQueue(2, 1, WAIT);
            assertEquals(0, queue.runningCount());
            assertEquals(0, queue.pendingCount());
        }

        @Test
        void rejectsNullRenderer() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ExportJobQueue(null, 1, 0, WAIT));
        }

        @Test
        void rejectsZeroMaxConcurrent() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ExportJobQueue(renderer, 0, 0, WAIT));
        }

        @Test
        void rejectsNegativeMaxConcurrent() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ExportJobQueue(renderer, -1, 0, WAIT));
        }

        @Test
        void rejectsNegativeMaxRetries() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ExportJobQueue(renderer, 1, -1, WAIT));
        }

        @Test
        void rejectsNullTimeout() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ExportJobQueue(renderer, 1, 0, null));
        }

        @Test
        void rejectsZeroTimeout() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ExportJobQueue(renderer, 1, 0, Duration.ZERO));
        }

        @Test
        void rejectsNegativeTimeout() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ExportJobQueue(renderer, 1, 0, Duration.ofMillis(-1)));
        }
    }

    // ------------------------------------------------------------------
    // 1. Submission: unique ID, PENDING
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("1. Submit")
    class Submit {

        @Test
        void submittedJobHasUuidAndStartsPending() throws Exception {
            when(renderer.render(any(ExportRequest.class))).thenAnswer(blockOnGate());
            newQueue(1, 0, LONG_TIMEOUT);

            queue.submit(req("blocker"));
            waitUntil(() -> queue.runningCount() == 1, "blocker running");

            ExportRequest request = req("file-1");
            ExportJob job = queue.submit(request);

            assertEquals(JobStatus.PENDING, job.getStatus());
            assertDoesNotThrow(() -> UUID.fromString(job.getId()));
            assertSame(request, job.getRequest());
            assertEquals(0, job.getAttempts());
            assertNull(job.getLastError());
            assertNull(job.getResult());
            assertEquals(1, queue.pendingCount());
        }

        @Test
        void manySubmissionsGetUniqueIds() throws Exception {
            when(renderer.render(any(ExportRequest.class))).thenAnswer(blockOnGate());
            newQueue(1, 0, LONG_TIMEOUT);

            Set<String> ids = new HashSet<>();
            for (int i = 0; i < 50; i++) {
                ids.add(queue.submit(req("file-" + i)).getId());
            }
            assertEquals(50, ids.size());
        }

        @Test
        void identicalRequestsGetDifferentIds() throws Exception {
            when(renderer.render(any(ExportRequest.class))).thenReturn(BYTES);
            newQueue(2, 0, WAIT);

            ExportRequest request = new ExportRequest("same", ExportFormat.SVG);
            ExportJob first = queue.submit(request);
            ExportJob second = queue.submit(request);

            assertNotEquals(first.getId(), second.getId());
        }

        @Test
        void getJobReturnsSubmittedJob() throws Exception {
            when(renderer.render(any(ExportRequest.class))).thenReturn(BYTES);
            newQueue(1, 0, WAIT);

            ExportJob job = queue.submit(req("file-1"));

            assertSame(job, queue.getJob(job.getId()).orElseThrow());
        }

        @Test
        void getJobIsEmptyForNullOrUnknownId() {
            newQueue(1, 0, WAIT);
            assertTrue(queue.getJob(null).isEmpty());
            assertTrue(queue.getJob(UUID.randomUUID().toString()).isEmpty());
            assertTrue(queue.getJob("").isEmpty());
        }

        @Test
        void successfulJobStoresResult() throws Exception {
            ExportRequest request = new ExportRequest("file-1", ExportFormat.PDF);
            when(renderer.render(request)).thenReturn(BYTES);
            newQueue(1, 0, WAIT);

            ExportJob job = queue.submit(request);
            awaitTerminal(job);

            assertEquals(JobStatus.SUCCEEDED, job.getStatus());
            assertArrayEquals(BYTES, job.getResult());
            assertEquals(1, job.getAttempts());
            assertNull(job.getLastError());
            verify(renderer, times(1)).render(request);
        }

        @Test
        void emptyResultStillSucceeds() throws Exception {
            when(renderer.render(any(ExportRequest.class))).thenReturn(new byte[0]);
            newQueue(1, 0, WAIT);

            ExportJob job = queue.submit(req("empty"));
            awaitTerminal(job);

            assertEquals(JobStatus.SUCCEEDED, job.getStatus());
            assertArrayEquals(new byte[0], job.getResult());
        }

        @Test
        void submitRejectsNullRequest() {
            newQueue(1, 0, WAIT);
            assertThrows(IllegalArgumentException.class, () -> queue.submit(null));
            assertEquals(0, queue.pendingCount());
        }

        @Test
        void submitAfterCloseThrows() {
            newQueue(1, 0, WAIT);
            queue.close();
            assertThrows(IllegalStateException.class, () -> queue.submit(req("late")));
        }
    }

    // ------------------------------------------------------------------
    // 2. Concurrency limit and FIFO
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("2. Concurrency and FIFO")
    class Concurrency {

        @Test
        void neverRunsMoreThanMaxConcurrent() throws Exception {
            AtomicInteger current = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();
            when(renderer.render(any(ExportRequest.class))).thenAnswer(inv -> {
                int now = current.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                try {
                    gate.await();
                } finally {
                    current.decrementAndGet();
                }
                return BYTES;
            });
            newQueue(3, 0, LONG_TIMEOUT);

            List<ExportJob> jobs = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                jobs.add(queue.submit(req("file-" + i)));
            }

            waitUntil(() -> queue.runningCount() == 3, "3 jobs running");
            verify(renderer, after(200).times(3)).render(any(ExportRequest.class));
            assertEquals(3, queue.runningCount());
            assertEquals(7, queue.pendingCount());

            gate.countDown();
            for (ExportJob job : jobs) {
                awaitTerminal(job);
                assertEquals(JobStatus.SUCCEEDED, job.getStatus());
            }
            assertTrue(peak.get() <= 3, "peak concurrency was " + peak.get());
            assertEquals(0, queue.runningCount());
            assertEquals(0, queue.pendingCount());
        }

        @Test
        void waitingJobsRunInFifoOrder() throws Exception {
            List<String> order = Collections.synchronizedList(new ArrayList<>());
            when(renderer.render(any(ExportRequest.class))).thenAnswer(inv -> {
                ExportRequest r = inv.getArgument(0);
                order.add(r.fileId());
                if (r.fileId().equals("job-0")) {
                    gate.await();
                }
                return BYTES;
            });
            newQueue(1, 0, LONG_TIMEOUT);

            List<ExportJob> jobs = new ArrayList<>();
            jobs.add(queue.submit(req("job-0")));
            waitUntil(() -> queue.runningCount() == 1, "job-0 running");
            for (int i = 1; i <= 5; i++) {
                jobs.add(queue.submit(req("job-" + i)));
            }

            assertEquals(5, queue.pendingCount());
            for (int i = 1; i <= 5; i++) {
                assertEquals(JobStatus.PENDING, jobs.get(i).getStatus());
            }

            gate.countDown();
            for (ExportJob job : jobs) {
                awaitTerminal(job);
            }
            assertEquals(List.of("job-0", "job-1", "job-2", "job-3", "job-4", "job-5"), order);
        }

        @Test
        void freedSlotGoesToOldestPendingJob() throws Exception {
            Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
            for (String id : List.of("a", "b", "c", "d", "e")) {
                gates.put(id, new CountDownLatch(1));
            }
            when(renderer.render(any(ExportRequest.class))).thenAnswer(inv -> {
                ExportRequest r = inv.getArgument(0);
                gates.get(r.fileId()).await();
                return BYTES;
            });
            newQueue(2, 0, LONG_TIMEOUT);

            ExportJob a = queue.submit(req("a"));
            ExportJob b = queue.submit(req("b"));
            waitUntil(() -> queue.runningCount() == 2, "a and b running");
            ExportJob c = queue.submit(req("c"));
            ExportJob d = queue.submit(req("d"));
            ExportJob e = queue.submit(req("e"));

            try {
                gates.get("a").countDown();
                awaitTerminal(a);
                waitUntil(() -> c.getStatus() == JobStatus.RUNNING, "c running");

                assertEquals(JobStatus.RUNNING, b.getStatus());
                assertEquals(JobStatus.PENDING, d.getStatus());
                assertEquals(JobStatus.PENDING, e.getStatus());
                assertEquals(2, queue.runningCount());
                assertEquals(2, queue.pendingCount());
            } finally {
                gates.values().forEach(CountDownLatch::countDown);
            }
        }

        @Test
        void maxConcurrentLargerThanJobCountRunsAllAtOnce() throws Exception {
            when(renderer.render(any(ExportRequest.class))).thenAnswer(blockOnGate());
            newQueue(10, 0, LONG_TIMEOUT);

            for (int i = 0; i < 4; i++) {
                queue.submit(req("file-" + i));
            }
            waitUntil(() -> queue.runningCount() == 4, "all 4 running");
            assertEquals(0, queue.pendingCount());
        }
    }

    // ------------------------------------------------------------------
    // 3. Retries
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("3. Retries")
    class Retries {

        @Test
        void failsAfterAllAttemptsAreUsed() throws Exception {
            RuntimeException boom = new RuntimeException("boom");
            ExportRequest request = req("broken");
            when(renderer.render(request)).thenThrow(boom);
            newQueue(1, 2, WAIT);

            ExportJob job = queue.submit(request);
            awaitTerminal(job);

            assertEquals(JobStatus.FAILED, job.getStatus());
            assertEquals(3, job.getAttempts());
            assertSame(boom, job.getLastError());
            assertNull(job.getResult());
            verify(renderer, after(200).times(3)).render(request);
        }

        @Test
        void zeroRetriesMeansOneAttempt() throws Exception {
            when(renderer.render(any(ExportRequest.class))).thenThrow(new RuntimeException("once"));
            newQueue(1, 0, WAIT);

            ExportJob job = queue.submit(req("x"));
            awaitTerminal(job);

            assertEquals(JobStatus.FAILED, job.getStatus());
            assertEquals(1, job.getAttempts());
            verify(renderer, after(200).times(1)).render(any(ExportRequest.class));
        }

        @Test
        void succeedsOnLastAllowedAttempt() throws Exception {
            when(renderer.render(any(ExportRequest.class)))
                    .thenThrow(new RuntimeException("first"))
                    .thenThrow(new RuntimeException("second"))
                    .thenReturn(BYTES);
            newQueue(1, 2, WAIT);

            ExportJob job = queue.submit(req("flaky"));
            awaitTerminal(job);

            assertEquals(JobStatus.SUCCEEDED, job.getStatus());
            assertEquals(3, job.getAttempts());
            assertArrayEquals(BYTES, job.getResult());
        }

        @Test
        void lastErrorIsFromFinalAttempt() throws Exception {
            RuntimeException first = new RuntimeException("first");
            RuntimeException last = new RuntimeException("last");
            when(renderer.render(any(ExportRequest.class))).thenThrow(first).thenThrow(last);
            newQueue(1, 1, WAIT);

            ExportJob job = queue.submit(req("x"));
            awaitTerminal(job);

            assertEquals(JobStatus.FAILED, job.getStatus());
            assertSame(last, job.getLastError());
        }

        @Test
        void checkedExceptionsAreRetried() throws Exception {
            IOException io = new IOException("disk");
            when(renderer.render(any(ExportRequest.class))).thenThrow(io);
            newQueue(1, 1, WAIT);

            ExportJob job = queue.submit(req("x"));
            awaitTerminal(job);

            assertEquals(JobStatus.FAILED, job.getStatus());
            assertEquals(2, job.getAttempts());
            assertSame(io, job.getLastError());
        }

        @Test
        void retryRunsBeforeNextPendingJob() throws Exception {
            List<String> calls = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger aCalls = new AtomicInteger();
            when(renderer.render(any(ExportRequest.class))).thenAnswer(inv -> {
                ExportRequest r = inv.getArgument(0);
                calls.add(r.fileId());
                if (r.fileId().equals("a")) {
                    int n = aCalls.incrementAndGet();
                    if (n == 1) {
                        gate.await();
                    }
                    if (n < 3) {
                        throw new RuntimeException("fail " + n);
                    }
                }
                return BYTES;
            });
            newQueue(1, 2, LONG_TIMEOUT);

            ExportJob a = queue.submit(req("a"));
            waitUntil(() -> queue.runningCount() == 1, "a running");
            ExportJob b = queue.submit(req("b"));

            gate.countDown();
            awaitTerminal(a);
            awaitTerminal(b);

            assertEquals(List.of("a", "a", "a", "b"), calls);
            assertEquals(JobStatus.SUCCEEDED, a.getStatus());
            assertEquals(JobStatus.SUCCEEDED, b.getStatus());
        }

        @Test
        void failedJobFreesSlotForNextJob() throws Exception {
            ExportRequest bad = req("bad");
            ExportRequest good = req("good");
            when(renderer.render(bad)).thenThrow(new RuntimeException("nope"));
            when(renderer.render(good)).thenReturn(BYTES);
            newQueue(1, 1, WAIT);

            ExportJob badJob = queue.submit(bad);
            ExportJob goodJob = queue.submit(good);
            awaitTerminal(badJob);
            awaitTerminal(goodJob);

            assertEquals(JobStatus.FAILED, badJob.getStatus());
            assertEquals(JobStatus.SUCCEEDED, goodJob.getStatus());
            assertEquals(0, queue.runningCount());
        }
    }

    // ------------------------------------------------------------------
    // 4. Timeout
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("4. Timeout")
    class Timeouts {

        @Test
        void timedOutAttemptIsInterruptedAndFails() throws Exception {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            when(renderer.render(any(ExportRequest.class))).thenAnswer(hang(started, interrupted));
            newQueue(1, 0, Duration.ofMillis(100));

            ExportJob job = queue.submit(req("slow"));
            awaitTerminal(job);

            assertEquals(JobStatus.FAILED, job.getStatus());
            assertEquals(1, job.getAttempts());
            assertInstanceOf(TimeoutException.class, job.getLastError());
            assertNull(job.getResult());
            assertTrue(interrupted.await(WAIT.toMillis(), TimeUnit.MILLISECONDS),
                    "render thread was not interrupted");
        }

        @Test
        void timedOutAttemptIsRetried() throws Exception {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            when(renderer.render(any(ExportRequest.class)))
                    .thenAnswer(hang(started, interrupted))
                    .thenReturn(BYTES);
            newQueue(1, 1, Duration.ofMillis(100));

            ExportJob job = queue.submit(req("slow-then-fast"));
            awaitTerminal(job);

            assertEquals(JobStatus.SUCCEEDED, job.getStatus());
            assertEquals(2, job.getAttempts());
            assertArrayEquals(BYTES, job.getResult());
        }

        @Test
        void everyAttemptTimingOutEndsFailed() throws Exception {
            when(renderer.render(any(ExportRequest.class)))
                    .thenAnswer(hang(new CountDownLatch(1), new CountDownLatch(1)));
            newQueue(1, 2, Duration.ofMillis(100));

            ExportJob job = queue.submit(req("always-slow"));
            awaitTerminal(job);

            assertEquals(JobStatus.FAILED, job.getStatus());
            assertEquals(3, job.getAttempts());
            assertInstanceOf(TimeoutException.class, job.getLastError());
        }

        @Test
        void timeoutAppliesPerAttemptNotPerJob() throws Exception {
            // Each attempt takes ~600 ms (under the 1 s timeout), but two
            // attempts together take longer than the timeout.
            when(renderer.render(any(ExportRequest.class)))
                    .thenAnswer(inv -> {
                        Thread.sleep(600);
                        throw new RuntimeException("slow failure");
                    })
                    .thenAnswer(inv -> {
                        Thread.sleep(600);
                        return BYTES;
                    });
            newQueue(1, 1, Duration.ofSeconds(1));

            ExportJob job = queue.submit(req("x"));
            awaitTerminal(job);

            assertEquals(JobStatus.SUCCEEDED, job.getStatus());
            assertEquals(2, job.getAttempts());
        }

        @Test
        void fastRenderWithinTimeoutSucceeds() throws Exception {
            when(renderer.render(any(ExportRequest.class))).thenAnswer(inv -> {
                Thread.sleep(20);
                return BYTES;
            });
            newQueue(1, 0, Duration.ofSeconds(2));

            ExportJob job = queue.submit(req("fast"));
            awaitTerminal(job);

            assertEquals(JobStatus.SUCCEEDED, job.getStatus());
            assertNull(job.getLastError());
        }

        @Test
        void timedOutJobFreesSlot() throws Exception {
            ExportRequest slow = req("slow");
            ExportRequest next = req("next");
            when(renderer.render(slow)).thenAnswer(hang(new CountDownLatch(1), new CountDownLatch(1)));
            when(renderer.render(next)).thenReturn(BYTES);
            newQueue(1, 0, Duration.ofMillis(100));

            ExportJob slowJob = queue.submit(slow);
            ExportJob nextJob = queue.submit(next);
            awaitTerminal(slowJob);
            awaitTerminal(nextJob);

            assertEquals(JobStatus.FAILED, slowJob.getStatus());
            assertEquals(JobStatus.SUCCEEDED, nextJob.getStatus());
        }
    }

    // ------------------------------------------------------------------
    // 5. Cancellation and input validation
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("5. Cancel")
    class Cancel {

        @Test
        void cancelPendingJobNeverReachesRenderer() throws Exception {
            ExportRequest first = req("first");
            ExportRequest second = req("second");
            when(renderer.render(any(ExportRequest.class))).thenAnswer(blockOnGate());
            newQueue(1, 0, LONG_TIMEOUT);

            ExportJob running = queue.submit(first);
            waitUntil(() -> queue.runningCount() == 1, "first running");
            ExportJob pending = queue.submit(second);
            assertEquals(JobStatus.PENDING, pending.getStatus());

            assertTrue(queue.cancel(pending.getId()));
            assertEquals(JobStatus.CANCELLED, pending.getStatus());
            assertEquals(0, queue.pendingCount());

            gate.countDown();
            awaitTerminal(running);
            assertTrue(pending.awaitCompletion(WAIT));
            verify(renderer, after(300).never()).render(second);
            assertEquals(JobStatus.CANCELLED, pending.getStatus());
            assertNull(pending.getResult());
        }

        @Test
        void cancelRunningJobInterruptsAndIsNotRetried() throws Exception {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            when(renderer.render(any(ExportRequest.class))).thenAnswer(hang(started, interrupted));
            newQueue(1, 3, LONG_TIMEOUT);

            ExportJob job = queue.submit(req("long"));
            assertTrue(started.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));

            assertTrue(queue.cancel(job.getId()));
            assertEquals(JobStatus.CANCELLED, job.getStatus());
            assertTrue(interrupted.await(WAIT.toMillis(), TimeUnit.MILLISECONDS),
                    "render thread was not interrupted");
            assertTrue(job.awaitCompletion(WAIT));

            verify(renderer, after(300).times(1)).render(any(ExportRequest.class));
            assertEquals(JobStatus.CANCELLED, job.getStatus());
            assertEquals(1, job.getAttempts());
            assertNull(job.getResult());
        }

        @Test
        void cancelRunningJobFreesSlotForNextJob() throws Exception {
            ExportRequest slow = req("slow");
            ExportRequest next = req("next");
            CountDownLatch started = new CountDownLatch(1);
            when(renderer.render(slow)).thenAnswer(hang(started, new CountDownLatch(1)));
            when(renderer.render(next)).thenReturn(BYTES);
            newQueue(1, 0, LONG_TIMEOUT);

            ExportJob slowJob = queue.submit(slow);
            ExportJob nextJob = queue.submit(next);
            assertTrue(started.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));

            assertTrue(queue.cancel(slowJob.getId()));
            awaitTerminal(nextJob);
            assertEquals(JobStatus.SUCCEEDED, nextJob.getStatus());
        }

        @Test
        void cancelSucceededJobReturnsFalse() throws Exception {
            when(renderer.render(any(ExportRequest.class))).thenReturn(BYTES);
            newQueue(1, 0, WAIT);

            ExportJob job = queue.submit(req("done"));
            awaitTerminal(job);

            assertFalse(queue.cancel(job.getId()));
            assertEquals(JobStatus.SUCCEEDED, job.getStatus());
            assertArrayEquals(BYTES, job.getResult());
        }

        @Test
        void cancelFailedJobReturnsFalse() throws Exception {
            when(renderer.render(any(ExportRequest.class))).thenThrow(new RuntimeException("x"));
            newQueue(1, 0, WAIT);

            ExportJob job = queue.submit(req("bad"));
            awaitTerminal(job);

            assertFalse(queue.cancel(job.getId()));
            assertEquals(JobStatus.FAILED, job.getStatus());
        }

        @Test
        void cancelTwiceReturnsFalseSecondTime() throws Exception {
            when(renderer.render(any(ExportRequest.class))).thenAnswer(blockOnGate());
            newQueue(1, 0, LONG_TIMEOUT);

            queue.submit(req("blocker"));
            waitUntil(() -> queue.runningCount() == 1, "blocker running");
            ExportJob job = queue.submit(req("pending"));

            assertTrue(queue.cancel(job.getId()));
            assertFalse(queue.cancel(job.getId()));
            assertEquals(JobStatus.CANCELLED, job.getStatus());
        }

        @Test
        void cancelledJobIsStillFindable() throws Exception {
            when(renderer.render(any(ExportRequest.class))).thenAnswer(blockOnGate());
            newQueue(1, 0, LONG_TIMEOUT);

            queue.submit(req("blocker"));
            waitUntil(() -> queue.runningCount() == 1, "blocker running");
            ExportJob job = queue.submit(req("pending"));
            queue.cancel(job.getId());

            assertSame(job, queue.getJob(job.getId()).orElseThrow());
        }

        @Test
        void cancelRejectsNullId() {
            newQueue(1, 0, WAIT);
            assertThrows(IllegalArgumentException.class, () -> queue.cancel(null));
        }

        @Test
        void cancelRejectsEmptyId() {
            newQueue(1, 0, WAIT);
            assertThrows(IllegalArgumentException.class, () -> queue.cancel(""));
        }

        @Test
        void cancelRejectsWhitespaceId() {
            newQueue(1, 0, WAIT);
            assertThrows(IllegalArgumentException.class, () -> queue.cancel("   "));
        }

        @Test
        void cancelRejectsUnknownId() {
            newQueue(1, 0, WAIT);
            assertThrows(IllegalArgumentException.class,
                    () -> queue.cancel(UUID.randomUUID().toString()));
        }
    }

    // ------------------------------------------------------------------
    // close()
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Close")
    class Close {

        @Test
        void closeCancelsRunningAndPendingJobs() throws Exception {
            ExportRequest first = req("first");
            ExportRequest second = req("second");
            CountDownLatch started = new CountDownLatch(1);
            when(renderer.render(any(ExportRequest.class))).thenAnswer(hang(started, new CountDownLatch(1)));
            newQueue(1, 3, LONG_TIMEOUT);

            ExportJob running = queue.submit(first);
            assertTrue(started.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            ExportJob pending = queue.submit(second);

            queue.close();

            assertEquals(JobStatus.CANCELLED, running.getStatus());
            assertEquals(JobStatus.CANCELLED, pending.getStatus());
            assertTrue(running.awaitCompletion(WAIT));
            assertTrue(pending.awaitCompletion(WAIT));
            verify(renderer, after(300).never()).render(second);
            verify(renderer, times(1)).render(first);
        }

        @Test
        void closeLeavesFinishedJobsAlone() throws Exception {
            when(renderer.render(any(ExportRequest.class))).thenReturn(BYTES);
            newQueue(1, 0, WAIT);

            ExportJob job = queue.submit(req("done"));
            awaitTerminal(job);
            queue.close();

            assertEquals(JobStatus.SUCCEEDED, job.getStatus());
            assertArrayEquals(BYTES, job.getResult());
        }

        @Test
        void closeTwiceDoesNothing() {
            newQueue(1, 0, WAIT);
            queue.close();
            assertDoesNotThrow(() -> queue.close());
            assertThrows(IllegalStateException.class, () -> queue.submit(req("late")));
        }

        @Test
        void closeOnEmptyQueueSucceeds() {
            newQueue(1, 0, WAIT);
            assertDoesNotThrow(() -> queue.close());
            assertEquals(0, queue.runningCount());
            assertEquals(0, queue.pendingCount());
        }
    }
}
