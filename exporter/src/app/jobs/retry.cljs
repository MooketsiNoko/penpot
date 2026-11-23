;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.jobs.retry)

(def ^:private retryable-error-codes
  #{:timeout :render-timeout :worker-not-ready :worker-exited})

(defn retryable-error?
  "Returns true for known temporary render failures. Accepts structured
  exception data as a map or an exception carrying that data."
  [error]
  (let [data (if (map? error) error (ex-data error))]
    (contains? retryable-error-codes (:code data))))

(defn should-retry-export?
  "Returns true when the error is retryable and another attempt is allowed.
  `attempts` counts attempts already made. `max-attempts` includes the first
  attempt and defaults to 3. Invalid counts or limits return false."
  ([attempts error]
   (should-retry-export? attempts 3 error))
  ([attempts max-attempts error]
   (and (js/Number.isSafeInteger attempts)
        (js/Number.isSafeInteger max-attempts)
        (<= 0 attempts)
        (pos? max-attempts)
        (< attempts max-attempts)
        (retryable-error? error))))
