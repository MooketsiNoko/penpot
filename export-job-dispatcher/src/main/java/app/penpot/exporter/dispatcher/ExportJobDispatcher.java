package app.penpot.exporter.dispatcher;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Assigns export jobs to workers: least active jobs first, never above the
 * per-worker limit, ties go to the first registered worker.
 */
public class ExportJobDispatcher {
  private final int maxJobsPerWorker;
  private final LinkedHashMap<String, Integer> activeJobsByWorker = new LinkedHashMap<>();
  private final Map<String, String> workerByJob = new HashMap<>();

  public ExportJobDispatcher(int maxJobsPerWorker) {
    if (maxJobsPerWorker < 1) {
      throw new IllegalArgumentException("maxJobsPerWorker must be at least 1");
    }
    this.maxJobsPerWorker = maxJobsPerWorker;
  }

  public void registerWorker(String workerId) {
    requireId(workerId, "workerId");
    if (activeJobsByWorker.containsKey(workerId)) {
      throw new IllegalArgumentException("worker already registered: " + workerId);
    }
    activeJobsByWorker.put(workerId, 0);
  }

  public Optional<String> dispatch(String jobId) {
    requireId(jobId, "jobId");
    String existing = workerByJob.get(jobId);
    if (existing != null) {
      return Optional.of(existing);
    }
    String best = null;
    int bestLoad = Integer.MAX_VALUE;
    for (Map.Entry<String, Integer> e : activeJobsByWorker.entrySet()) {
      int load = e.getValue();
      // strict < keeps the first registered worker on ties
      if (load < maxJobsPerWorker && load < bestLoad) {
        best = e.getKey();
        bestLoad = load;
      }
    }
    if (best == null) {
      return Optional.empty();
    }
    activeJobsByWorker.merge(best, 1, Integer::sum);
    workerByJob.put(jobId, best);
    return Optional.of(best);
  }

  public void markJobFinished(String jobId) {
    requireId(jobId, "jobId");
    String worker = workerByJob.remove(jobId);
    if (worker == null) {
      throw new IllegalArgumentException("unknown job: " + jobId);
    }
    activeJobsByWorker.merge(worker, -1, Integer::sum);
  }

  public int getActiveJobCount(String workerId) {
    return activeJobsByWorker.getOrDefault(workerId, 0);
  }

  public Optional<String> getAssignedWorker(String jobId) {
    return Optional.ofNullable(workerByJob.get(jobId));
  }

  private static void requireId(String id, String name) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException(name + " must not be null or blank");
    }
  }
}
