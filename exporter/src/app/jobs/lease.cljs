;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.jobs.lease
  "Per-job execution leases with heartbeat renewal and orphan reclamation.

  Tracks time-limited worker leases against an injectable clock so expiration,
  reclamation, and zombie-worker rejection are deterministic and testable
  without wall-clock sleeps."
  (:require
   [app.common.exceptions :as ex]
   [app.config :as cf]
   [clojure.string :as str]))

(defn- validate-id!
  [value field]
  (when-not (and (string? value) (not (str/blank? value)))
    (ex/raise :type :validation
              :code :invalid-id
              :field field
              :hint (str (name field) " must be a non-blank string")))
  value)

(defn- validate-status!
  [status]
  (when-not (or (keyword? status)
                (and (string? status) (not (str/blank? status))))
    (ex/raise :type :validation
              :code :invalid-status
              :hint "status must be a keyword or non-blank string"))
  status)

(defn- expired?
  [lease now-ms]
  (> now-ms (:expires-at lease)))

(defn- assert-active-owner!
  [lease job-id worker-id now-ms]
  (cond
    (nil? lease)
    (ex/raise :type :validation
              :code :lease-not-found
              :job-id job-id
              :hint "no lease exists for job")

    (not= (:worker-id lease) worker-id)
    (ex/raise :type :validation
              :code :worker-mismatch
              :job-id job-id
              :worker-id worker-id
              :owner (:worker-id lease)
              :hint "worker does not hold the lease for this job")

    (= :revoked (:status lease))
    (ex/raise :type :conflict
              :code :lease-revoked
              :job-id job-id
              :worker-id worker-id
              :hint "lease has been revoked")

    (= :completed (:status lease))
    (ex/raise :type :conflict
              :code :lease-completed
              :job-id job-id
              :worker-id worker-id
              :hint "lease is already completed")

    (expired? lease now-ms)
    (ex/raise :type :conflict
              :code :lease-expired
              :job-id job-id
              :worker-id worker-id
              :hint "lease has expired")

    :else
    lease))

(defn create-monitor
  "Creates a lease monitor configured with an optional `:clock` (0-arg fn
  returning epoch ms), `:ttl-ms`, and lifecycle callbacks (`:on-orphan`,
  `:on-requeue`, `:on-status-update`, `:on-complete`)."
  ([]
   (create-monitor {}))
  ([{:keys [clock ttl-ms on-orphan on-requeue on-status-update on-complete]
     :or   {clock            #(js/Date.now)
            ttl-ms           (* 1000 (cf/get :exporter-heartbeat-ttl 15))
            on-orphan        (constantly nil)
            on-requeue       (constantly nil)
            on-status-update (constantly nil)
            on-complete      (constantly nil)}}]
   (when-not (fn? clock)
     (ex/raise :type :validation
               :code :invalid-clock
               :hint "clock must be a 0-arg function"))
   (when-not (and (int? ttl-ms) (pos? ttl-ms))
     (ex/raise :type :validation
               :code :invalid-ttl
               :hint "ttl-ms must be a positive integer"))
   {:clock            clock
    :ttl-ms           ttl-ms
    :on-orphan        on-orphan
    :on-requeue       on-requeue
    :on-status-update on-status-update
    :on-complete      on-complete
    :leases           (atom {})}))

(defn get-lease
  "Returns the current lease record for `job-id`, or nil."
  [{:keys [leases]} job-id]
  (get @leases job-id))

(defn acquire!
  "Grants a time-limited `:active` lease for `job-id` to `worker-id`."
  [{:keys [clock ttl-ms leases]} job-id worker-id]
  (validate-id! job-id :job-id)
  (validate-id! worker-id :worker-id)
  (let [now-ms (clock)
        result (volatile! nil)]
    (swap! leases
           (fn [state]
             (let [existing (get state job-id)]
               (cond
                 (= :active (:status existing))
                 (ex/raise :type :validation
                           :code :duplicate-lease
                           :job-id job-id
                           :worker-id (:worker-id existing)
                           :hint "job already has an active lease")

                 (= :completed (:status existing))
                 (ex/raise :type :conflict
                           :code :lease-completed
                           :job-id job-id
                           :hint "job lease is already completed")

                 :else
                 (let [lease {:job-id      job-id
                              :worker-id   worker-id
                              :status      :active
                              :acquired-at now-ms
                              :expires-at  (+ now-ms ttl-ms)}]
                   (vreset! result lease)
                   (assoc state job-id lease))))))
    @result))

(defn heartbeat!
  "Extends the expiration deadline of an active lease held by `worker-id`."
  [{:keys [clock ttl-ms leases]} job-id worker-id]
  (validate-id! job-id :job-id)
  (validate-id! worker-id :worker-id)
  (let [now-ms (clock)
        result (volatile! nil)]
    (swap! leases
           (fn [state]
             (let [lease   (assert-active-owner! (get state job-id) job-id worker-id now-ms)
                   updated (assoc lease
                                  :expires-at (+ now-ms ttl-ms)
                                  :last-heartbeat-at now-ms)]
               (vreset! result updated)
               (assoc state job-id updated))))
    @result))

(defn update-status!
  "Forwards a job status update if `worker-id` holds a valid, unexpired lease."
  [{:keys [clock leases on-status-update]} job-id worker-id status]
  (validate-id! job-id :job-id)
  (validate-id! worker-id :worker-id)
  (validate-status! status)
  (let [now-ms (clock)
        lease  (assert-active-owner! (get @leases job-id) job-id worker-id now-ms)]
    (on-status-update job-id status)
    lease))

(defn complete!
  "Marks the lease `:completed` and records completion if `worker-id` holds a valid lease."
  [{:keys [clock leases on-complete]} job-id worker-id]
  (validate-id! job-id :job-id)
  (validate-id! worker-id :worker-id)
  (let [now-ms (clock)
        result (volatile! nil)]
    (swap! leases
           (fn [state]
             (let [lease   (assert-active-owner! (get state job-id) job-id worker-id now-ms)
                   updated (assoc lease
                                  :status :completed
                                  :completed-at now-ms)]
               (vreset! result updated)
               (assoc state job-id updated))))
    (on-complete job-id)
    @result))

(defn reclaim-expired!
  "Revokes expired `:active` leases, flags jobs orphaned, and returns them for
  reassignment. Returns the vector of reclaimed job IDs."
  [{:keys [clock leases on-orphan on-requeue]}]
  (let [now-ms    (clock)
        reclaimed (volatile! [])]
    (swap! leases
           (fn [state]
             (vreset! reclaimed [])
             (reduce-kv (fn [acc job-id lease]
                          (if (and (= :active (:status lease))
                                   (expired? lease now-ms))
                            (do
                              (vswap! reclaimed conj job-id)
                              (assoc acc job-id (assoc lease
                                                       :status :revoked
                                                       :revoked-at now-ms)))
                            acc))
                        state
                        state)))
    (let [job-ids @reclaimed]
      (doseq [job-id job-ids]
        (on-orphan job-id)
        (on-requeue job-id))
      job-ids)))
