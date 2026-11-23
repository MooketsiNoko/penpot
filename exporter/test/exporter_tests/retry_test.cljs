;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.retry-test
  (:require
   [app.jobs.retry :as retry]
   [cljs.test :as t :include-macros true]))

(t/deftest recognizes-temporary-export-errors
  (doseq [code [:timeout :render-timeout :worker-not-ready :worker-exited]]
    (t/testing (str code)
      (t/is (true? (retry/retryable-error? (ex-info "export failed" {:type :internal :code code}))))))
  (t/testing "normalized error data"
    (t/is (true? (retry/retryable-error? {:code :render-timeout})))))

(t/deftest rejects-other-export-errors
  (doseq [[description error]
          [["permanent failure" {:code :unable-to-upload-resource :response-status 403}]
           ["upload service unavailable" {:code :unable-to-upload-resource :response-status 503}]
           ["invalid input" {:type :validation :code :invalid-color}]
           ["cancellation" (ex-info "cancelled" {:code :job-cancelled})]
           ["unknown code" {:code :unknown}]
           ["caller wait timeout" {:code :export-timeout}]
           ["unstructured error" (js/Error. "render timeout")]
           ["missing error" nil]]]
    (t/testing description
      (t/is (false? (retry/retryable-error? error)))
      (t/is (false? (retry/should-retry-export? 1 3 error))))))

(t/deftest honors-the-total-attempt-limit
  (let [error {:code :render-timeout}]
    (doseq [[description attempts limit expected]
            [["zero attempts" 0 3 true]
             ["after the first attempt" 1 3 true]
             ["after the second attempt" 2 3 true]
             ["at the limit" 3 3 false]
             ["past the limit" 4 3 false]
             ["before a single allowed attempt" 0 1 true]
             ["after a single allowed attempt" 1 1 false]]]
      (t/testing description
        (t/is (= expected (retry/should-retry-export? attempts limit error)))))
    (t/testing "the default is three total attempts"
      (t/is (true? (retry/should-retry-export? 2 error)))
      (t/is (false? (retry/should-retry-export? 3 error))))))

(t/deftest rejects-invalid-attempt-counts-and-limits
  (let [error {:code :render-timeout}]
    (doseq [value [-1 0.5 "1" false nil js/NaN js/Infinity 9007199254740992]]
      (t/testing (str "invalid count or limit " (pr-str value))
        (t/is (false? (retry/should-retry-export? value 3 error)))
        (t/is (false? (retry/should-retry-export? 0 value error)))))
    (t/testing "the limit must be positive"
      (t/is (false? (retry/should-retry-export? 0 0 error))))))
