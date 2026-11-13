;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.lease-test
  "Unit tests for worker execution leases, heartbeat renewals, expiration
  reclamation, zombie-worker rejection, and ID validation."
  (:require
   [app.jobs.lease :as lease]
   [cljs.test :as t :include-macros true]))

(def ^:private base-time-ms 1762873200000)
(def ^:private default-ttl-ms 30000)
(def ^:private job-1 "job-export-101")
(def ^:private job-2 "job-export-202")
(def ^:private worker-a "exporter-worker-a")
(def ^:private worker-b "exporter-worker-b")

(defn- setup
  []
  (let [now-ms  (atom base-time-ms)
        events  (atom {:orphaned  []
                       :requeued  []
                       :statuses  []
                       :completed []})
        monitor (lease/create-monitor
                 {:clock            #(deref now-ms)
                  :ttl-ms           default-ttl-ms
                  :on-orphan        (fn [job-id] (swap! events update :orphaned conj job-id))
                  :on-requeue       (fn [job-id] (swap! events update :requeued conj job-id))
                  :on-status-update (fn [job-id status] (swap! events update :statuses conj [job-id status]))
                  :on-complete      (fn [job-id] (swap! events update :completed conj job-id))})]
    {:monitor  monitor
     :events   events
     :advance! (fn [ms] (swap! now-ms + ms))}))

(defn- capture-error-data
  [f]
  (try
    (f)
    nil
    (catch :default cause
      (ex-data cause))))

;; Requirement 1: A worker claiming an export job receives a time-limited execution lease.

(t/deftest acquire-sets-active-lease-with-ttl-expiration
  (let [{:keys [monitor events]} (setup)
        acquired                 (lease/acquire! monitor job-1 worker-a)]
    (t/is (= job-1 (:job-id acquired)))
    (t/is (= worker-a (:worker-id acquired)))
    (t/is (= :active (:status acquired)))
    (t/is (= base-time-ms (:acquired-at acquired)))
    (t/is (= (+ base-time-ms default-ttl-ms) (:expires-at acquired)))
    (t/is (= {:orphaned [] :requeued [] :statuses [] :completed []} @events))))

(t/deftest acquire-tracks-independent-leases-per-job
  (let [{:keys [monitor advance!]} (setup)
        first-lease                (lease/acquire! monitor job-1 worker-a)]
    (advance! 5000)
    (let [second-lease (lease/acquire! monitor job-2 worker-b)]
      (t/is (= (+ base-time-ms default-ttl-ms) (:expires-at first-lease)))
      (t/is (= (+ base-time-ms 5000 default-ttl-ms) (:expires-at second-lease)))
      (t/is (= worker-a (:worker-id (lease/get-lease monitor job-1))))
      (t/is (= worker-b (:worker-id (lease/get-lease monitor job-2)))))))

;; Requirement 2: Valid heartbeats from the assigned worker extend the lease expiration.

(t/deftest heartbeat-extends-lease-expiration-when-active
  (let [{:keys [monitor advance! events]} (setup)]
    (lease/acquire! monitor job-1 worker-a)
    (advance! 20000)
    (let [renewed (lease/heartbeat! monitor job-1 worker-a)]
      (t/is (= :active (:status renewed)))
      (t/is (= (+ base-time-ms 20000 default-ttl-ms) (:expires-at renewed))))
    ;; Advance past the original T+30s deadline to T+40s; renewed deadline is T+50s.
    (advance! 20000)
    (t/is (= [] (lease/reclaim-expired! monitor)))
    (t/is (= [] (:requeued @events)))))

(t/deftest heartbeat-renews-lease-one-ms-before-expiration
  (let [{:keys [monitor advance!]} (setup)]
    (lease/acquire! monitor job-1 worker-a)
    (advance! (dec default-ttl-ms))
    (let [renewed (lease/heartbeat! monitor job-1 worker-a)]
      (t/is (= :active (:status renewed)))
      (t/is (= (+ base-time-ms (dec default-ttl-ms) default-ttl-ms)
               (:expires-at renewed))))))

(t/deftest lease-remains-valid-at-exact-expiration-boundary
  (let [{:keys [monitor advance! events]} (setup)]
    (lease/acquire! monitor job-1 worker-a)
    (advance! default-ttl-ms)
    (t/is (= [] (lease/reclaim-expired! monitor)))
    (let [renewed (lease/heartbeat! monitor job-1 worker-a)]
      (t/is (= :active (:status renewed)))
      (t/is (= (+ base-time-ms default-ttl-ms default-ttl-ms)
               (:expires-at renewed))))
    (t/is (= [] (:orphaned @events)))
    (t/is (= [] (:requeued @events)))))

(t/deftest update-status-forwards-update-when-lease-is-active
  (let [{:keys [monitor advance! events]} (setup)]
    (lease/acquire! monitor job-1 worker-a)
    (advance! 10000)
    (lease/update-status! monitor job-1 worker-a "processing")
    (t/is (= [[job-1 "processing"]] (:statuses @events)))))

(t/deftest complete-closes-lease-when-active-and-worker-matches
  (let [{:keys [monitor advance! events]} (setup)]
    (lease/acquire! monitor job-1 worker-a)
    (advance! 12000)
    (let [completed (lease/complete! monitor job-1 worker-a)]
      (t/is (= :completed (:status completed)))
      (t/is (= [job-1] (:completed @events)))
      (t/is (= :completed (:status (lease/get-lease monitor job-1)))))))

;; Requirement 3: If a lease expires without a heartbeat, the job is flagged ORPHANED and reclaimed.

(t/deftest reclaim-revokes-flags-orphaned-and-requeues-one-ms-past-ttl
  (let [{:keys [monitor advance! events]} (setup)]
    (lease/acquire! monitor job-1 worker-a)
    (advance! (inc default-ttl-ms))
    (let [reclaimed (lease/reclaim-expired! monitor)]
      (t/is (= [job-1] reclaimed))
      (t/is (= :revoked (:status (lease/get-lease monitor job-1))))
      (t/is (= [job-1] (:orphaned @events)))
      (t/is (= [job-1] (:requeued @events))))))

(t/deftest reclaim-sweeps-only-expired-leases-leaving-active-intact
  (let [{:keys [monitor advance! events]} (setup)]
    (lease/acquire! monitor job-1 worker-a)
    (advance! 15000)
    (lease/acquire! monitor job-2 worker-b)
    (advance! 20000)
    (let [reclaimed (lease/reclaim-expired! monitor)]
      (t/is (= [job-1] reclaimed))
      (t/is (= :revoked (:status (lease/get-lease monitor job-1))))
      (t/is (= :active (:status (lease/get-lease monitor job-2))))
      (t/is (= [job-1] (:orphaned @events)))
      (t/is (= [job-1] (:requeued @events))))))

(t/deftest reclaim-is-idempotent-on-repeated-sweeps
  (let [{:keys [monitor advance! events]} (setup)]
    (lease/acquire! monitor job-1 worker-a)
    (advance! (+ default-ttl-ms 5000))
    (t/is (= [job-1] (lease/reclaim-expired! monitor)))
    (advance! 5000)
    (t/is (= [] (lease/reclaim-expired! monitor)))
    (t/is (= [job-1] (:orphaned @events)))
    (t/is (= [job-1] (:requeued @events)))))

(t/deftest new-worker-can-acquire-job-after-reclamation
  (let [{:keys [monitor advance!]} (setup)]
    (lease/acquire! monitor job-1 worker-a)
    (advance! (+ default-ttl-ms 5000))
    (lease/reclaim-expired! monitor)
    (let [reassigned-at (+ base-time-ms default-ttl-ms 5000)
          reassigned    (lease/acquire! monitor job-1 worker-b)]
      (t/is (= worker-b (:worker-id reassigned)))
      (t/is (= :active (:status reassigned)))
      (t/is (= reassigned-at (:acquired-at reassigned)))
      (t/is (= (+ reassigned-at default-ttl-ms) (:expires-at reassigned))))))

;; Requirement 4: Status updates or heartbeats from an expired or revoked lease are rejected.

(t/deftest heartbeat-rejected-when-expired-before-sweep
  (let [{:keys [monitor advance!]} (setup)]
    (lease/acquire! monitor job-1 worker-a)
    (advance! (inc default-ttl-ms))
    (let [err (capture-error-data #(lease/heartbeat! monitor job-1 worker-a))]
      (t/is (= :conflict (:type err)))
      (t/is (= :lease-expired (:code err))))))

(t/deftest heartbeat-rejected-when-lease-was-revoked
  (let [{:keys [monitor advance!]} (setup)]
    (lease/acquire! monitor job-1 worker-a)
    (advance! (+ default-ttl-ms 5000))
    (lease/reclaim-expired! monitor)
    (let [err (capture-error-data #(lease/heartbeat! monitor job-1 worker-a))]
      (t/is (= :conflict (:type err)))
      (t/is (= :lease-revoked (:code err))))))

(t/deftest status-update-rejected-when-lease-expired-or-revoked
  (let [{:keys [monitor advance! events]} (setup)]
    (lease/acquire! monitor job-1 worker-a)
    (advance! (+ default-ttl-ms 1000))
    (let [expired-err (capture-error-data #(lease/update-status! monitor job-1 worker-a "processing"))]
      (t/is (= :conflict (:type expired-err)))
      (t/is (= :lease-expired (:code expired-err))))
    (lease/reclaim-expired! monitor)
    (let [revoked-err (capture-error-data #(lease/update-status! monitor job-1 worker-a "processing"))]
      (t/is (= :conflict (:type revoked-err)))
      (t/is (= :lease-revoked (:code revoked-err))))
    (t/is (= [] (:statuses @events)))))

(t/deftest complete-rejected-when-lease-is-expired
  (let [{:keys [monitor advance! events]} (setup)]
    (lease/acquire! monitor job-1 worker-a)
    (advance! (+ default-ttl-ms 1000))
    (let [err (capture-error-data #(lease/complete! monitor job-1 worker-a))]
      (t/is (= :conflict (:type err)))
      (t/is (= :lease-expired (:code err)))
      (t/is (= [] (:completed @events))))))

(t/deftest zombie-worker-completion-rejected-after-reassignment
  (let [{:keys [monitor advance! events]} (setup)]
    (lease/acquire! monitor job-1 worker-a)
    (advance! (+ default-ttl-ms 5000))
    (lease/reclaim-expired! monitor)
    (lease/acquire! monitor job-1 worker-b)
    (advance! 5000)
    (let [err (capture-error-data #(lease/complete! monitor job-1 worker-a))]
      (t/is (= :validation (:type err)))
      (t/is (= :worker-mismatch (:code err)))
      (t/is (= [] (:completed @events)))
      (t/is (= :active (:status (lease/get-lease monitor job-1)))))
    (lease/complete! monitor job-1 worker-b)
    (t/is (= [job-1] (:completed @events)))))

(t/deftest heartbeat-and-duplicate-completion-rejected-after-completion
  (let [{:keys [monitor advance! events]} (setup)]
    (lease/acquire! monitor job-1 worker-a)
    (advance! 10000)
    (lease/complete! monitor job-1 worker-a)
    (let [hb-err   (capture-error-data #(lease/heartbeat! monitor job-1 worker-a))
          comp-err (capture-error-data #(lease/complete! monitor job-1 worker-a))]
      (t/is (= :lease-completed (:code hb-err)))
      (t/is (= :lease-completed (:code comp-err))))
    (advance! (+ default-ttl-ms 5000))
    (t/is (= [] (lease/reclaim-expired! monitor)))
    (t/is (= [job-1] (:completed @events)))
    (t/is (= [] (:orphaned @events)))
    (t/is (= [] (:requeued @events)))))

;; Requirement 5: Null, blank, or mismatched worker and job IDs throw validation exceptions.

(t/deftest acquire-rejects-nil-or-blank-job-id
  (let [{:keys [monitor events]} (setup)]
    (doseq [bad-id [nil "" "   " "\t\n" 123]]
      (let [err (capture-error-data #(lease/acquire! monitor bad-id worker-a))]
        (t/is (= :validation (:type err)))
        (t/is (= :invalid-id (:code err)))))
    (t/is (= [] (:requeued @events)))))

(t/deftest acquire-rejects-nil-or-blank-worker-id
  (let [{:keys [monitor events]} (setup)]
    (doseq [bad-id [nil "" "   " "\t\n" 123]]
      (let [err (capture-error-data #(lease/acquire! monitor job-1 bad-id))]
        (t/is (= :validation (:type err)))
        (t/is (= :invalid-id (:code err)))))
    (t/is (= [] (:requeued @events)))))

(t/deftest acquire-rejects-duplicate-active-lease
  (let [{:keys [monitor advance!]} (setup)]
    (lease/acquire! monitor job-1 worker-a)
    (advance! 10000)
    (let [same-err  (capture-error-data #(lease/acquire! monitor job-1 worker-a))
          other-err (capture-error-data #(lease/acquire! monitor job-1 worker-b))
          current   (lease/get-lease monitor job-1)]
      (t/is (= :duplicate-lease (:code same-err)))
      (t/is (= :duplicate-lease (:code other-err)))
      (t/is (= worker-a (:worker-id current)))
      (t/is (= (+ base-time-ms default-ttl-ms) (:expires-at current))))))

(t/deftest heartbeat-rejects-nil-blank-or-unknown-ids
  (let [{:keys [monitor]} (setup)]
    (lease/acquire! monitor job-1 worker-a)
    (doseq [bad-id [nil "" "   "]]
      (t/is (= :invalid-id (:code (capture-error-data #(lease/heartbeat! monitor bad-id worker-a)))))
      (t/is (= :invalid-id (:code (capture-error-data #(lease/heartbeat! monitor job-1 bad-id))))))
    (t/is (= :lease-not-found (:code (capture-error-data #(lease/heartbeat! monitor "unknown-job" worker-a)))))))

(t/deftest mismatched-worker-id-rejected-without-mutating-lease
  (let [{:keys [monitor advance! events]} (setup)]
    (lease/acquire! monitor job-1 worker-a)
    (advance! 10000)
    (let [hb-err     (capture-error-data #(lease/heartbeat! monitor job-1 worker-b))
          status-err (capture-error-data #(lease/update-status! monitor job-1 worker-b "processing"))
          comp-err   (capture-error-data #(lease/complete! monitor job-1 worker-b))
          current    (lease/get-lease monitor job-1)]
      (t/is (= :worker-mismatch (:code hb-err)))
      (t/is (= :worker-mismatch (:code status-err)))
      (t/is (= :worker-mismatch (:code comp-err)))
      (t/is (= worker-a (:worker-id current)))
      (t/is (= :active (:status current)))
      (t/is (= (+ base-time-ms default-ttl-ms) (:expires-at current)))
      (t/is (= [] (:statuses @events)))
      (t/is (= [] (:completed @events))))))

(t/deftest status-update-and-completion-reject-invalid-inputs
  (let [{:keys [monitor events]} (setup)]
    (lease/acquire! monitor job-1 worker-a)
    (doseq [bad-id [nil "" " "]]
      (t/is (= :invalid-id (:code (capture-error-data #(lease/update-status! monitor bad-id worker-a "processing")))))
      (t/is (= :invalid-id (:code (capture-error-data #(lease/update-status! monitor job-1 bad-id "processing")))))
      (t/is (= :invalid-id (:code (capture-error-data #(lease/complete! monitor bad-id worker-a)))))
      (t/is (= :invalid-id (:code (capture-error-data #(lease/complete! monitor job-1 bad-id))))))
    (t/is (= :invalid-status (:code (capture-error-data #(lease/update-status! monitor job-1 worker-a nil)))))
    (t/is (= :invalid-status (:code (capture-error-data #(lease/update-status! monitor job-1 worker-a "   ")))))
    (t/is (= :lease-not-found (:code (capture-error-data #(lease/update-status! monitor "unknown-job" worker-a "processing")))))
    (t/is (= :lease-not-found (:code (capture-error-data #(lease/complete! monitor "unknown-job" worker-a)))))
    (t/is (= [] (:statuses @events)))
    (t/is (= [] (:completed @events)))))

(t/deftest create-monitor-rejects-invalid-clock-or-ttl
  (t/is (= :invalid-clock (:code (capture-error-data #(lease/create-monitor {:clock "not-a-fn"})))))
  (t/is (= :invalid-ttl (:code (capture-error-data #(lease/create-monitor {:ttl-ms 0})))))
  (t/is (= :invalid-ttl (:code (capture-error-data #(lease/create-monitor {:ttl-ms -100})))))
  (t/is (= :invalid-ttl (:code (capture-error-data #(lease/create-monitor {:ttl-ms nil}))))))
