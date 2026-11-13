# Standardized prompt (same text for every run, fresh chat each time)

```
Role: You are an experienced Java developer who writes thorough JUnit 5 unit tests.

Task: Write unit tests for the class ExportJobDispatcher from the Penpot scalable exporter. Use JUnit 5 (Java 21), package app.penpot.exporter.dispatcher, class name ExportJobDispatcherTest. Mockito is available but only use it if needed.

Code context:
Package: app.penpot.exporter.dispatcher
public class ExportJobDispatcher {
    private final int maxJobsPerWorker;
    private final LinkedHashMap<String, Integer> activeJobsByWorker;
    private final Map<String, String> workerByJob;
    public ExportJobDispatcher(int maxJobsPerWorker);
    public void registerWorker(String workerId);
    public Optional<String> dispatch(String jobId);
    public void markJobFinished(String jobId);
    public int getActiveJobCount(String workerId);
    public Optional<String> getAssignedWorker(String jobId);
}
Invalid arguments throw IllegalArgumentException.

Intention: Verify that (1) workers can be registered and null, blank or duplicate worker IDs are rejected; (2) dispatch assigns a job to exactly one worker and returns that worker's ID; (3) the worker with the fewest active jobs is chosen, ties going to the first registered worker; (4) no worker ever exceeds maxJobsPerWorker, and dispatch returns an empty Optional when no workers exist or all are full; (5) markJobFinished frees a slot so a later dispatch can use it, and rejects null, blank or unknown job IDs. Cover normal cases and edge cases.
```
