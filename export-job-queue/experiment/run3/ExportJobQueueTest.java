package app.penpot.exporter.queue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.Set;
import java.util.UUID;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link ExportJobQueue}.
 *
 * <p>The renderer is a Mockito mock. Blocking renderers use latches so that
 * tests control exactly when an attempt starts and ends. Every wait has an
 * upper bound, and the class-level {@link Timeout} stops a hung test.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ExportJobQueueTest {

    private static final Duration AWAIT = Duration.ofSeconds(5);
    private static final Duration LONG_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(200);
    private static final long HANG_MILLIS = 10_000;
    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G'};

    private Renderer renderer;
    private ExportJobQueue queue;

    @BeforeEach
    void setUp() {
        renderer = mock(Renderer.class);
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.close();
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private ExportJobQueue newQueue(int maxConcurrent, int maxRetries, Duration timeout) {
        queue = new ExportJobQueue(renderer, maxConcurrent, maxRetries, timeout);
        return queue;
    }

    private static ExportRequest request(String fileId) {
        return new ExportRequest(fileId, ExportFormat.PNG);
    }

    private static void awaitCondition(BooleanSupplier condition, String description)
            throws InterruptedException {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("Timed out waiting for: " + description);
            }
            Thread.sleep(10);
        }
    }

    private static void awaitStatus(ExportJob job, JobStatus expected) throws InterruptedException {
        awaitCondition(() -> job.getStatus() == expected,
                "job " + job.getId() + " to reach " + expected);
    }

    private static void assertCompletes(ExportJob job) throws InterruptedException {
        assertTrue(job.awaitCompletion(AWAIT),
                "job " + job.getId() + " did not finish; status=" + job.getStatus());
        assertTrue(job.getStatus().isTerminal());
    }

    private static void assertAwait(CountDownLatch latch, String description)
            throws InterruptedException {
        assertTrue(latch.await(AWAIT.toMillis(), TimeUnit.MILLISECONDS),
                "Timed out waiting for: " + description);
    }

    /** Short pause used only to check that something does NOT happen. */
    private static void pause() throws InterruptedException {
        Thread.sleep(300);
    }

    // ---------------------------------------------------------------------
    // Constructor and getters
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("stores the configuration")
        void storesConfiguration() {
            ExportJobQueue q = newQueue(3, 2, Duration.ofSeconds(7));

            assertEquals(3, q.getMaxConcurrent());
            assertEquals(2, q.getMaxRetries());
            assertEquals(Duration.ofSeconds(7), q.getTimeout());
            assertEquals(0, q.runningCount());
            assertEquals(0, q.pendingCount());
        }

        @Test
        @DisplayName("accepts the smallest valid values")
        void acceptsBoundaryValues() {
            ExportJobQueue q = newQueue(1, 0, Duration.ofNanos(1));

            assertEquals(1, q.getMaxConcurrent());
            assertEquals(0, q.getMaxRetries());
            assertEquals(Duration.ofNanos(1), q.getTimeout());
        }

        @Test
        @DisplayName("rejects a null renderer")
        void rejectsNullRenderer() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ExportJobQueue(null, 1, 0, LONG_TIMEOUT));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
        @DisplayName("rejects maxConcurrent below 1")
        void rejectsBadMaxConcurrent(int maxConcurrent) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ExportJobQueue(renderer, maxConcurrent, 0, LONG_TIMEOUT));
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, Integer.MIN_VALUE})
        @DisplayName("rejects negative maxRetries")
        void rejectsBadMaxRetries(int maxRetries) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ExportJobQueue(renderer, 1, maxRetries, LONG_TIMEOUT));
        }

        @Test
        @DisplayName("rejects a null timeout")
        void rejectsNullTimeout() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ExportJobQueue(renderer, 1, 0, null));
        }

        @Test
        @DisplayName("rejects a zero timeout")
        void rejectsZeroTimeout() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ExportJobQueue(renderer, 1, 0, Duration.ZERO));
        }

        @Test
        @DisplayName("rejects a negative timeout")
        void rejectsNegativeTimeout() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ExportJobQueue(renderer, 1, 0, Duration.ofMillis(-1)));
        }
    }

    // ---------------------------------------------------------------------
    // Requirement 1: unique IDs, PENDING start, submit/getJob validation
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Submit and lookup")
    class SubmitTests {

        @Test
        @DisplayName("each submitted job gets a distinct UUID")
        void jobsGetUniqueUuidIds() throws Exception {
            when(renderer.render(any())).thenReturn(PNG_BYTES);
            ExportJobQueue q = newQueue(4, 0, LONG_TIMEOUT);

            Set<String> ids = new HashSet<>();
            List<ExportJob> jobs = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                ExportJob job = q.submit(request("file-" + i));
                assertNotNull(job.getId());
                ids.add(job.getId());
                jobs.add(job);
            }

            assertEquals(50, ids.size(), "IDs must be unique");
            for (String id : ids) {
                UUID parsed = assertDoesNotThrow(() -> UUID.fromString(id));
                assertEquals(parsed.toString(), id.toLowerCase());
            }
            for (ExportJob job : jobs) {
                assertCompletes(job);
            }
        }

        @Test
        @DisplayName("the same request submitted twice makes two jobs")
        void sameRequestTwiceMakesTwoJobs() throws Exception {
            when(renderer.render(any())).thenReturn(PNG_BYTES);
            ExportJobQueue q = newQueue(1, 0, LONG_TIMEOUT);
            ExportRequest req = request("same-file");

            ExportJob first = q.submit(req);
            ExportJob second = q.submit(req);

            assertFalse(first.getId().equals(second.getId()));
            assertCompletes(first);
            assertCompletes(second);
            verify(renderer, times(2)).render(req);
        }

        @Test
        @DisplayName("a job waiting for a free slot is PENDING with no attempts")
        void waitingJobIsPending() throws Exception {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExportRequest blocker = request("blocker");
            ExportRequest waiting = request("waiting");
            when(renderer.render(any())).thenAnswer(inv -> {
                if (blocker.equals(inv.getArgument(0))) {
                    started.countDown();
                    release.await();
                }
                return PNG_BYTES;
            });
            ExportJobQueue q = newQueue(1, 0, LONG_TIMEOUT);

            ExportJob running = q.submit(blocker);
            assertAwait(started, "blocker to start");
            ExportJob job = q.submit(waiting);

            assertEquals(JobStatus.PENDING, job.getStatus());
            assertSame(waiting, job.getRequest());
            assertEquals(0, job.getAttempts());
            assertNull(job.getLastError());
            assertNull(job.getResult());
            assertFalse(job.getStatus().isTerminal());
            assertEquals(1, q.pendingCount());
            assertEquals(1, q.runningCount());
            verify(renderer, never()).render(waiting);

            release.countDown();
            assertCompletes(running);
            assertCompletes(job);
        }

        @Test
        @DisplayName("getJob returns the submitted job")
        void getJobReturnsSubmittedJob() throws Exception {
            when(renderer.render(any())).thenReturn(PNG_BYTES);
            ExportJobQueue q = newQueue(1, 0, LONG_TIMEOUT);

            ExportJob job = q.submit(request("file"));

            assertTrue(q.getJob(job.getId()).isPresent());
            assertSame(job, q.getJob(job.getId()).get());
            assertCompletes(job);
        }

        @Test
        @DisplayName("getJob is empty for null, blank and unknown IDs")
        void getJobEmptyForBadIds() {
            ExportJobQueue q = newQueue(1, 0, LONG_TIMEOUT);

            assertTrue(q.getJob(null).isEmpty());
            assertTrue(q.getJob("").isEmpty());
            assertTrue(q.getJob(UUID.randomUUID().toString()).isEmpty());
        }

        @Test
        @DisplayName("submit rejects a null request")
        void submitRejectsNull() {
            ExportJobQueue q = newQueue(1, 0, LONG_TIMEOUT);

            assertThrows(IllegalArgumentException.class, () -> q.submit(null));
            assertEquals(0, q.pendingCount());
            assertEquals(0, q.runningCount());
        }
    }

    // ---------------------------------------------------------------------
    // Requirement 2: concurrency limit and FIFO order
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Concurrency and ordering")
    class ConcurrencyTests {

        @Test
        @DisplayName("never runs more than maxConcurrent jobs at once")
        void respectsConcurrencyLimit() throws Exception {
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maxActive = new AtomicInteger();
            AtomicInteger started = new AtomicInteger();
            CountDownLatch release = new CountDownLatch(1);
            when(renderer.render(any())).thenAnswer(inv -> {
                started.incrementAndGet();
                int now = active.incrementAndGet();
                maxActive.accumulateAndGet(now, Math::max);
                try {
                    release.await();
                    return PNG_BYTES;
                } finally {
                    active.decrementAndGet();
                }
            });
            ExportJobQueue q = newQueue(2, 0, LONG_TIMEOUT);

            List<ExportJob> jobs = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                jobs.add(q.submit(request("file-" + i)));
            }

            awaitCondition(() -> started.get() == 2, "two jobs to start");
            pause();
            assertEquals(2, started.get(), "a third job started too early");
            assertEquals(2, q.runningCount());
            assertEquals(3, q.pendingCount());
            long runningJobs = jobs.stream().filter(j -> j.getStatus() == JobStatus.RUNNING).count();
            long pendingJobs = jobs.stream().filter(j -> j.getStatus() == JobStatus.PENDING).count();
            assertEquals(2, runningJobs);
            assertEquals(3, pendingJobs);

            release.countDown();
            for (ExportJob job : jobs) {
                assertCompletes(job);
                assertEquals(JobStatus.SUCCEEDED, job.getStatus());
            }
            assertEquals(2, maxActive.get());
            assertEquals(5, started.get());
            awaitCondition(() -> q.runningCount() == 0 && q.pendingCount() == 0, "queue to drain");
        }

        @Test
        @DisplayName("runs all jobs at once when they fit under the limit")
        void runsJobsInParallelUpToLimit() throws Exception {
            CountDownLatch allStarted = new CountDownLatch(3);
            CountDownLatch release = new CountDownLatch(1);
            when(renderer.render(any())).thenAnswer(inv -> {
                allStarted.countDown();
                release.await();
                return PNG_BYTES;
            });
            ExportJobQueue q = newQueue(3, 0, LONG_TIMEOUT);

            List<ExportJob> jobs = List.of(
                    q.submit(request("a")), q.submit(request("b")), q.submit(request("c")));

            assertAwait(allStarted, "all three jobs to run at the same time");
            assertEquals(3, q.runningCount());
            assertEquals(0, q.pendingCount());

            release.countDown();
            for (ExportJob job : jobs) {
                assertCompletes(job);
            }
        }

        @Test
        @DisplayName("waiting jobs start in FIFO order")
        void waitingJobsStartInFifoOrder() throws Exception {
            List<String> order = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            when(renderer.render(any())).thenAnswer(inv -> {
                ExportRequest r = inv.getArgument(0);
                order.add(r.fileId());
                if (r.fileId().equals("first")) {
                    firstStarted.countDown();
                    release.await();
                }
                return PNG_BYTES;
            });
            ExportJobQueue q = newQueue(1, 0, LONG_TIMEOUT);

            List<ExportJob> jobs = new ArrayList<>();
            jobs.add(q.submit(request("first")));
            assertAwait(firstStarted, "first job to start");
            for (String id : List.of("b", "c", "d", "e")) {
                jobs.add(q.submit(request(id)));
            }
            assertEquals(4, q.pendingCount());

            release.countDown();
            for (ExportJob job : jobs) {
                assertCompletes(job);
            }
            assertEquals(List.of("first", "b", "c", "d", "e"), order);
        }

        @Test
        @DisplayName("a failed job frees its slot for the next job")
        void failedJobFreesSlot() throws Exception {
            ExportRequest bad = request("bad");
            ExportRequest good = request("good");
            when(renderer.render(bad)).thenThrow(new IOException("render failed"));
            when(renderer.render(good)).thenReturn(PNG_BYTES);
            ExportJobQueue q = newQueue(1, 0, LONG_TIMEOUT);

            ExportJob badJob = q.submit(bad);
            ExportJob goodJob = q.submit(good);

            assertCompletes(badJob);
            assertCompletes(goodJob);
            assertEquals(JobStatus.FAILED, badJob.getStatus());
            assertEquals(JobStatus.SUCCEEDED, goodJob.getStatus());
        }
    }

    // ---------------------------------------------------------------------
    // Requirement 3: retries and FAILED state
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Success and retries")
    class RetryTests {

        @Test
        @DisplayName("a successful render marks the job SUCCEEDED with the result")
        void successStoresResult() throws Exception {
            ExportRequest req = request("file");
            when(renderer.render(req)).thenReturn(PNG_BYTES);
            ExportJobQueue q = newQueue(1, 3, LONG_TIMEOUT);

            ExportJob job = q.submit(req);

            assertCompletes(job);
            assertEquals(JobStatus.SUCCEEDED, job.getStatus());
            assertArrayEquals(PNG_BYTES, job.getResult());
            assertEquals(1, job.getAttempts());
            assertNull(job.getLastError());
            verify(renderer, times(1)).render(req);
        }

        @Test
        @DisplayName("an empty result still counts as success")
        void emptyResultIsSuccess() throws Exception {
            when(renderer.render(any())).thenReturn(new byte[0]);
            ExportJobQueue q = newQueue(1, 0, LONG_TIMEOUT);

            ExportJob job = q.submit(request("empty"));

            assertCompletes(job);
            assertEquals(JobStatus.SUCCEEDED, job.getStatus());
            assertArrayEquals(new byte[0], job.getResult());
        }

        @Test
        @DisplayName("a job that always fails gets 1 + maxRetries attempts, then FAILED")
        void alwaysFailingJobIsRetriedThenFailed() throws Exception {
            ExportRequest req = request("file");
            RuntimeException error = new RuntimeException("boom");
            when(renderer.render(req)).thenThrow(error);
            ExportJobQueue q = newQueue(1, 2, LONG_TIMEOUT);

            ExportJob job = q.submit(req);

            assertCompletes(job);
            assertEquals(JobStatus.FAILED, job.getStatus());
            assertEquals(3, job.getAttempts());
            assertSame(error, job.getLastError());
            assertNull(job.getResult());

            pause();
            verify(renderer, times(3)).render(req);
        }

        @Test
        @DisplayName("maxRetries = 0 means one attempt only")
        void zeroRetriesMeansOneAttempt() throws Exception {
            ExportRequest req = request("file");
            Exception error = new Exception("checked failure");
            when(renderer.render(req)).thenThrow(error);
            ExportJobQueue q = newQueue(1, 0, LONG_TIMEOUT);

            ExportJob job = q.submit(req);

            assertCompletes(job);
            assertEquals(JobStatus.FAILED, job.getStatus());
            assertEquals(1, job.getAttempts());
            assertSame(error, job.getLastError());

            pause();
            verify(renderer, times(1)).render(req);
        }

        @Test
        @DisplayName("a job that fails then succeeds on a retry is SUCCEEDED")
        void failThenSucceed() throws Exception {
            ExportRequest req = request("file");
            when(renderer.render(req))
                    .thenThrow(new IOException("first"))
                    .thenThrow(new IOException("second"))
                    .thenReturn(PNG_BYTES);
            ExportJobQueue q = newQueue(1, 2, LONG_TIMEOUT);

            ExportJob job = q.submit(req);

            assertCompletes(job);
            assertEquals(JobStatus.SUCCEEDED, job.getStatus());
            assertEquals(3, job.getAttempts());
            assertArrayEquals(PNG_BYTES, job.getResult());
            verify(renderer, times(3)).render(req);
        }

        @Test
        @DisplayName("success on the last allowed attempt is not a failure")
        void succeedsOnLastAttempt() throws Exception {
            ExportRequest req = request("file");
            when(renderer.render(req))
                    .thenThrow(new IOException("first"))
                    .thenReturn(PNG_BYTES);
            ExportJobQueue q = newQueue(1, 1, LONG_TIMEOUT);

            ExportJob job = q.submit(req);

            assertCompletes(job);
            assertEquals(JobStatus.SUCCEEDED, job.getStatus());
            assertEquals(2, job.getAttempts());
        }

        @Test
        @DisplayName("lastError holds the error from the latest attempt")
        void lastErrorIsLatest() throws Exception {
            ExportRequest req = request("file");
            IOException first = new IOException("first");
            IllegalStateException second = new IllegalStateException("second");
            when(renderer.render(req)).thenThrow(first).thenThrow(second);
            ExportJobQueue q = newQueue(1, 1, LONG_TIMEOUT);

            ExportJob job = q.submit(req);

            assertCompletes(job);
            assertEquals(JobStatus.FAILED, job.getStatus());
            assertEquals(2, job.getAttempts());
            assertSame(second, job.getLastError());
        }

        @Test
        @DisplayName("a failed job stays FAILED and gets no more attempts")
        void failedJobIsFinal() throws Exception {
            ExportRequest req = request("file");
            when(renderer.render(req)).thenThrow(new IOException("boom"));
            ExportJobQueue q = newQueue(1, 1, LONG_TIMEOUT);

            ExportJob job = q.submit(req);

            assertCompletes(job);
            pause();
            assertEquals(JobStatus.FAILED, job.getStatus());
            assertEquals(2, job.getAttempts());
            verify(renderer, times(2)).render(req);
        }
    }

    // ---------------------------------------------------------------------
    // Requirement 4: per-attempt timeout
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Timeouts")
    class TimeoutTests {

        @Test
        @DisplayName("an attempt past the timeout is interrupted and the job FAILED")
        void timedOutAttemptIsInterruptedAndFails() throws Exception {
            CountDownLatch interrupted = new CountDownLatch(1);
            when(renderer.render(any())).thenAnswer(inv -> {
                try {
                    Thread.sleep(HANG_MILLIS);
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    throw e;
                }
                return PNG_BYTES;
            });
            ExportJobQueue q = newQueue(1, 0, SHORT_TIMEOUT);

            ExportJob job = q.submit(request("slow"));

            assertCompletes(job);
            assertEquals(JobStatus.FAILED, job.getStatus());
            assertEquals(1, job.getAttempts());
            assertInstanceOf(TimeoutException.class, job.getLastError());
            assertNull(job.getResult());
            assertAwait(interrupted, "renderer thread to be interrupted");
        }

        @Test
        @DisplayName("a timed-out attempt follows the retry rules")
        void timeoutIsRetried() throws Exception {
            ExportRequest req = request("slow");
            AtomicInteger interrupts = new AtomicInteger();
            when(renderer.render(req)).thenAnswer(inv -> {
                try {
                    Thread.sleep(HANG_MILLIS);
                } catch (InterruptedException e) {
                    interrupts.incrementAndGet();
                    throw e;
                }
                return PNG_BYTES;
            });
            ExportJobQueue q = newQueue(1, 2, SHORT_TIMEOUT);

            ExportJob job = q.submit(req);

            assertCompletes(job);
            assertEquals(JobStatus.FAILED, job.getStatus());
            assertEquals(3, job.getAttempts());
            assertInstanceOf(TimeoutException.class, job.getLastError());
            awaitCondition(() -> interrupts.get() == 3, "all three attempts to be interrupted");
            verify(renderer, times(3)).render(req);
        }

        @Test
        @DisplayName("a retry after a timeout can succeed")
        void timeoutThenSuccess() throws Exception {
            AtomicInteger calls = new AtomicInteger();
            when(renderer.render(any())).thenAnswer(inv -> {
                if (calls.incrementAndGet() == 1) {
                    Thread.sleep(HANG_MILLIS);
                }
                return PNG_BYTES;
            });
            ExportJobQueue q = newQueue(1, 1, SHORT_TIMEOUT);

            ExportJob job = q.submit(request("slow-then-fast"));

            assertCompletes(job);
            assertEquals(JobStatus.SUCCEEDED, job.getStatus());
            assertEquals(2, job.getAttempts());
            assertArrayEquals(PNG_BYTES, job.getResult());
        }

        @Test
        @DisplayName("the timeout applies to each attempt, not the whole job")
        void timeoutIsPerAttempt() throws Exception {
            // Each attempt takes ~200 ms (under the 500 ms timeout), but three
            // attempts together take ~600 ms (over it).
            AtomicInteger calls = new AtomicInteger();
            when(renderer.render(any())).thenAnswer(inv -> {
                Thread.sleep(200);
                if (calls.incrementAndGet() < 3) {
                    throw new IOException("transient");
                }
                return PNG_BYTES;
            });
            ExportJobQueue q = newQueue(1, 2, Duration.ofMillis(500));

            ExportJob job = q.submit(request("file"));

            assertCompletes(job);
            assertEquals(JobStatus.SUCCEEDED, job.getStatus());
            assertEquals(3, job.getAttempts());
        }

        @Test
        @DisplayName("an attempt that finishes within the timeout succeeds")
        void finishesWithinTimeout() throws Exception {
            when(renderer.render(any())).thenAnswer(inv -> {
                Thread.sleep(50);
                return PNG_BYTES;
            });
            ExportJobQueue q = newQueue(1, 0, Duration.ofSeconds(2));

            ExportJob job = q.submit(request("file"));

            assertCompletes(job);
            assertEquals(JobStatus.SUCCEEDED, job.getStatus());
            assertNull(job.getLastError());
        }

        @Test
        @DisplayName("a timed-out job frees its slot for the next job")
        void timedOutJobFreesSlot() throws Exception {
            ExportRequest slow = request("slow");
            ExportRequest fast = request("fast");
            when(renderer.render(any())).thenAnswer(inv -> {
                if (slow.equals(inv.getArgument(0))) {
                    Thread.sleep(HANG_MILLIS);
                }
                return PNG_BYTES;
            });
            ExportJobQueue q = newQueue(1, 0, SHORT_TIMEOUT);

            ExportJob slowJob = q.submit(slow);
            ExportJob fastJob = q.submit(fast);

            assertCompletes(slowJob);
            assertCompletes(fastJob);
            assertEquals(JobStatus.FAILED, slowJob.getStatus());
            assertEquals(JobStatus.SUCCEEDED, fastJob.getStatus());
        }
    }

    // ---------------------------------------------------------------------
    // Requirement 5: cancellation and input validation
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Cancel")
    class CancelTests {

        @Test
        @DisplayName("cancelling a PENDING job marks it CANCELLED and it never renders")
        void cancelPendingJob() throws Exception {
            CountDownLatch blockerStarted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExportRequest blocker = request("blocker");
            ExportRequest waiting = request("waiting");
            when(renderer.render(any())).thenAnswer(inv -> {
                if (blocker.equals(inv.getArgument(0))) {
                    blockerStarted.countDown();
                    release.await();
                }
                return PNG_BYTES;
            });
            ExportJobQueue q = newQueue(1, 3, LONG_TIMEOUT);

            ExportJob blockerJob = q.submit(blocker);
            assertAwait(blockerStarted, "blocker to start");
            ExportJob waitingJob = q.submit(waiting);
            assertEquals(JobStatus.PENDING, waitingJob.getStatus());

            assertTrue(q.cancel(waitingJob.getId()));
            assertEquals(JobStatus.CANCELLED, waitingJob.getStatus());
            assertTrue(waitingJob.awaitCompletion(AWAIT));
            assertEquals(0, q.pendingCount());

            release.countDown();
            assertCompletes(blockerJob);
            assertEquals(JobStatus.SUCCEEDED, blockerJob.getStatus());
            pause();

            assertEquals(JobStatus.CANCELLED, waitingJob.getStatus());
            assertEquals(0, waitingJob.getAttempts());
            assertNull(waitingJob.getResult());
            verify(renderer, never()).render(waiting);
        }

        @Test
        @DisplayName("cancelling a RUNNING job stops it and it is never retried")
        void cancelRunningJob() throws Exception {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            ExportRequest req = request("running");
            when(renderer.render(req)).thenAnswer(inv -> {
                started.countDown();
                try {
                    Thread.sleep(HANG_MILLIS);
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    throw e;
                }
                return PNG_BYTES;
            });
            ExportJobQueue q = newQueue(1, 3, LONG_TIMEOUT);

            ExportJob job = q.submit(req);
            assertAwait(started, "job to start");
            awaitStatus(job, JobStatus.RUNNING);

            assertTrue(q.cancel(job.getId()));
            assertEquals(JobStatus.CANCELLED, job.getStatus());
            assertTrue(job.awaitCompletion(AWAIT));
            assertAwait(interrupted, "renderer to be interrupted");
            awaitCondition(() -> q.runningCount() == 0, "running count to drop to 0");

            pause();
            assertEquals(JobStatus.CANCELLED, job.getStatus(), "status must not change after cancel");
            assertNull(job.getResult());
            verify(renderer, times(1)).render(req);
        }

        @Test
        @DisplayName("cancelling a RUNNING job lets the next PENDING job start")
        void cancelRunningJobFreesSlot() throws Exception {
            CountDownLatch slowStarted = new CountDownLatch(1);
            ExportRequest slow = request("slow");
            ExportRequest next = request("next");
            when(renderer.render(any())).thenAnswer(inv -> {
                if (slow.equals(inv.getArgument(0))) {
                    slowStarted.countDown();
                    Thread.sleep(HANG_MILLIS);
                }
                return PNG_BYTES;
            });
            ExportJobQueue q = newQueue(1, 0, LONG_TIMEOUT);

            ExportJob slowJob = q.submit(slow);
            assertAwait(slowStarted, "slow job to start");
            ExportJob nextJob = q.submit(next);
            assertEquals(JobStatus.PENDING, nextJob.getStatus());

            assertTrue(q.cancel(slowJob.getId()));

            assertCompletes(nextJob);
            assertEquals(JobStatus.SUCCEEDED, nextJob.getStatus());
            assertEquals(JobStatus.CANCELLED, slowJob.getStatus());
        }

        @Test
        @DisplayName("cancel returns false for a SUCCEEDED job")
        void cancelSucceededJob() throws Exception {
            when(renderer.render(any())).thenReturn(PNG_BYTES);
            ExportJobQueue q = newQueue(1, 0, LONG_TIMEOUT);
            ExportJob job = q.submit(request("file"));
            assertCompletes(job);

            assertFalse(q.cancel(job.getId()));
            assertEquals(JobStatus.SUCCEEDED, job.getStatus());
            assertArrayEquals(PNG_BYTES, job.getResult());
        }

        @Test
        @DisplayName("cancel returns false for a FAILED job")
        void cancelFailedJob() throws Exception {
            when(renderer.render(any())).thenThrow(new IOException("boom"));
            ExportJobQueue q = newQueue(1, 0, LONG_TIMEOUT);
            ExportJob job = q.submit(request("file"));
            assertCompletes(job);

            assertFalse(q.cancel(job.getId()));
            assertEquals(JobStatus.FAILED, job.getStatus());
        }

        @Test
        @DisplayName("cancel returns false for an already CANCELLED job")
        void cancelTwice() throws Exception {
            CountDownLatch started = new CountDownLatch(1);
            when(renderer.render(any())).thenAnswer(inv -> {
                started.countDown();
                Thread.sleep(HANG_MILLIS);
                return PNG_BYTES;
            });
            ExportJobQueue q = newQueue(1, 0, LONG_TIMEOUT);
            ExportJob job = q.submit(request("file"));
            assertAwait(started, "job to start");

            assertTrue(q.cancel(job.getId()));
            assertFalse(q.cancel(job.getId()));
            assertEquals(JobStatus.CANCELLED, job.getStatus());
        }

        @Test
        @DisplayName("cancel rejects a null ID")
        void cancelRejectsNull() {
            ExportJobQueue q = newQueue(1, 0, LONG_TIMEOUT);

            assertThrows(IllegalArgumentException.class, () -> q.cancel(null));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
        @DisplayName("cancel rejects a blank ID")
        void cancelRejectsBlank(String id) {
            ExportJobQueue q = newQueue(1, 0, LONG_TIMEOUT);

            assertThrows(IllegalArgumentException.class, () -> q.cancel(id));
        }

        @Test
        @DisplayName("cancel rejects an unknown ID")
        void cancelRejectsUnknown() throws Exception {
            when(renderer.render(any())).thenReturn(PNG_BYTES);
            ExportJobQueue q = newQueue(1, 0, LONG_TIMEOUT);
            ExportJob job = q.submit(request("file"));
            assertCompletes(job);

            assertThrows(IllegalArgumentException.class,
                    () -> q.cancel(UUID.randomUUID().toString()));
            assertThrows(IllegalArgumentException.class, () -> q.cancel("not-a-job"));
            assertEquals(JobStatus.SUCCEEDED, job.getStatus());
        }
    }

    // ---------------------------------------------------------------------
    // close()
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Close")
    class CloseTests {

        @Test
        @DisplayName("close cancels unfinished jobs and leaves finished ones alone")
        void closeCancelsUnfinishedJobs() throws Exception {
            CountDownLatch runningStarted = new CountDownLatch(1);
            ExportRequest done = request("done");
            ExportRequest running = request("running");
            ExportRequest waiting = request("waiting");
            when(renderer.render(any())).thenAnswer(inv -> {
                if (running.equals(inv.getArgument(0))) {
                    runningStarted.countDown();
                    Thread.sleep(HANG_MILLIS);
                }
                return PNG_BYTES;
            });
            ExportJobQueue q = newQueue(1, 3, LONG_TIMEOUT);

            ExportJob doneJob = q.submit(done);
            assertCompletes(doneJob);
            ExportJob runningJob = q.submit(running);
            assertAwait(runningStarted, "running job to start");
            ExportJob waitingJob = q.submit(waiting);

            q.close();

            assertEquals(JobStatus.SUCCEEDED, doneJob.getStatus());
            assertEquals(JobStatus.CANCELLED, runningJob.getStatus());
            assertEquals(JobStatus.CANCELLED, waitingJob.getStatus());
            assertTrue(runningJob.awaitCompletion(AWAIT));
            assertTrue(waitingJob.awaitCompletion(AWAIT));

            pause();
            verify(renderer, never()).render(waiting);
            verify(renderer, times(1)).render(running);
        }

        @Test
        @DisplayName("submit after close throws IllegalStateException")
        void submitAfterClose() throws Exception {
            ExportJobQueue q = newQueue(1, 0, LONG_TIMEOUT);
            q.close();

            assertThrows(IllegalStateException.class, () -> q.submit(request("file")));
            verify(renderer, never()).render(any());
        }

        @Test
        @DisplayName("submit(null) after close still throws")
        void submitNullAfterClose() {
            ExportJobQueue q = newQueue(1, 0, LONG_TIMEOUT);
            q.close();

            Exception e = assertThrows(RuntimeException.class, () -> q.submit(null));
            assertTrue(e instanceof IllegalArgumentException || e instanceof IllegalStateException);
        }

        @Test
        @DisplayName("calling close twice does nothing the second time")
        void closeIsIdempotent() throws Exception {
            CountDownLatch started = new CountDownLatch(1);
            when(renderer.render(any())).thenAnswer(inv -> {
                started.countDown();
                Thread.sleep(HANG_MILLIS);
                return PNG_BYTES;
            });
            ExportJobQueue q = newQueue(1, 0, LONG_TIMEOUT);
            ExportJob job = q.submit(request("file"));
            assertAwait(started, "job to start");

            q.close();
            assertDoesNotThrow(q::close);

            assertEquals(JobStatus.CANCELLED, job.getStatus());
            assertThrows(IllegalStateException.class, () -> q.submit(request("again")));
        }

        @Test
        @DisplayName("close on an empty queue is safe")
        void closeEmptyQueue() {
            ExportJobQueue q = newQueue(1, 0, LONG_TIMEOUT);

            assertDoesNotThrow(q::close);
            assertEquals(0, q.runningCount());
            assertEquals(0, q.pendingCount());
        }
    }
}
