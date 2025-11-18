(ns scicloj.clay.v2.server-state-test
  "Unit tests for stateful parameterized notebooks feature.
   Tests state management, URL parsing, and configuration."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [scicloj.clay.v2.server :as server]))

;; Fixture to reset state between tests
(defn reset-state-fixture [f]
  (let [original-states @server/*states
        original-config @server/*state-config]
    (try
      (reset! server/*states {})
      (reset! server/*state-config server/default-state-config)
      (f)
      (finally
        (reset! server/*states original-states)
        (reset! server/*state-config original-config)))))

(use-fixtures :each reset-state-fixture)

;; =============================================================================
;; State Creation Tests
;; =============================================================================

(deftest test-create-state
  (testing "creates state with UUID"
    (let [id (server/create-state! {"wallet" "pb1test"})]
      (is (string? id))
      (is (= 36 (count id)))  ; UUID format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
      (is (some? (server/get-state id)))))

  (testing "state has correct structure"
    (let [id (server/create-state! {"wallet" "pb1abc" "minAUM" "1000"})]
      (is (= {"wallet" "pb1abc" "minAUM" "1000"}
             (:params (server/get-state id))))
      (is (number? (:expires (server/get-state id))))))

  (testing "multiple states are independent"
    (let [id1 (server/create-state! {"wallet" "pb1first"})
          id2 (server/create-state! {"wallet" "pb1second"})]
      (is (not= id1 id2))
      (is (= "pb1first" (get-in (server/get-state id1) [:params "wallet"])))
      (is (= "pb1second" (get-in (server/get-state id2) [:params "wallet"]))))))

;; =============================================================================
;; State Expiration Tests
;; =============================================================================

(deftest test-get-state-expiration
  (testing "returns nil for expired state"
    ;; Create state with 0 TTL (expires immediately)
    (let [id (server/create-state! {"test" "value"} 0)]
      (Thread/sleep 10)
      (is (nil? (server/get-state id)))))

  (testing "returns state before expiration"
    (let [id (server/create-state! {"test" "value"} 1)]  ; 1 hour TTL
      (is (some? (server/get-state id)))))

  (testing "returns nil for unknown state-id"
    (is (nil? (server/get-state "nonexistent-uuid-1234")))))

;; =============================================================================
;; Cleanup Tests
;; =============================================================================

(deftest test-cleanup-expired-states
  (testing "removes expired states"
    (reset! server/*states {})
    (let [expired-id (server/create-state! {"expired" "true"} 0)
          valid-id (server/create-state! {"valid" "true"} 24)]
      (Thread/sleep 10)
      (server/cleanup-expired-states!)
      (is (nil? (get @server/*states expired-id)))
      (is (some? (get @server/*states valid-id)))))

  (testing "keeps valid states after cleanup"
    (reset! server/*states {})
    (let [id1 (server/create-state! {"first" "1"} 24)
          id2 (server/create-state! {"second" "2"} 24)]
      (server/cleanup-expired-states!)
      (is (= 2 (count @server/*states)))
      (is (some? (get @server/*states id1)))
      (is (some? (get @server/*states id2))))))

;; =============================================================================
;; Max States Limit Tests
;; =============================================================================

(deftest test-max-states-limit
  (testing "evicts oldest when at limit"
    (reset! server/*states {})
    (server/configure-state-management! {:max-states 3})
    (let [id1 (server/create-state! {"first" "1"})
          _ (Thread/sleep 1)
          _id2 (server/create-state! {"second" "2"})
          _ (Thread/sleep 1)
          _id3 (server/create-state! {"third" "3"})
          _ (Thread/sleep 1)
          id4 (server/create-state! {"fourth" "4"})]
      ;; First state should be evicted
      (is (nil? (server/get-state id1)))
      (is (some? (server/get-state id4)))
      (is (<= (count @server/*states) 3)))
    ;; Reset to default
    (server/configure-state-management! {:max-states 10000}))

  (testing "respects limit after multiple additions"
    (reset! server/*states {})
    (server/configure-state-management! {:max-states 2})
    (dotimes [i 5]
      (server/create-state! {"index" (str i)})
      (Thread/sleep 1))
    (is (<= (count @server/*states) 2))
    ;; Reset
    (server/configure-state-management! {:max-states 10000})))

;; =============================================================================
;; URL Parsing Tests
;; =============================================================================

(deftest test-parse-state-url
  (testing "parses valid state URLs"
    (is (= {:page "dashboard.html" :state-id "abc-123"}
           (server/parse-state-url "/app/dashboard.html/abc-123")))
    (is (= {:page "index.html" :state-id "uuid-here"}
           (server/parse-state-url "/app/index.html/uuid-here")))
    (is (= {:page "deep/path/page.html" :state-id "state-456"}
           (server/parse-state-url "/app/deep/path/page.html/state-456"))))

  (testing "returns nil for invalid URLs"
    (is (nil? (server/parse-state-url "/dashboard.html")))
    (is (nil? (server/parse-state-url "/app/dashboard/abc")))  ; No .html
    (is (nil? (server/parse-state-url "/other/page.html/id")))  ; Wrong prefix
    (is (nil? (server/parse-state-url "/app/page.html")))  ; Missing state-id
    (is (nil? (server/parse-state-url "")))))

;; =============================================================================
;; Configuration Tests
;; =============================================================================

(deftest test-configure-state-management
  (testing "updates config atom"
    (server/configure-state-management! {:state-ttl-hours 48})
    (is (= 48 (:state-ttl-hours @server/*state-config)))
    ;; Reset
    (server/configure-state-management! {:state-ttl-hours 24}))

  (testing "preserves unset values"
    (let [original-max (:max-states @server/*state-config)]
      (server/configure-state-management! {:state-ttl-hours 12})
      (is (= 12 (:state-ttl-hours @server/*state-config)))
      (is (= original-max (:max-states @server/*state-config)))
      ;; Reset
      (server/configure-state-management! {:state-ttl-hours 24})))

  (testing "can update multiple values"
    (server/configure-state-management! {:state-ttl-hours 36
                                         :max-states 5000
                                         :cleanup-interval-ms 1800000})
    (is (= 36 (:state-ttl-hours @server/*state-config)))
    (is (= 5000 (:max-states @server/*state-config)))
    (is (= 1800000 (:cleanup-interval-ms @server/*state-config)))
    ;; Reset
    (reset! server/*state-config server/default-state-config)))

;; =============================================================================
;; State ID Generation Tests
;; =============================================================================

(deftest test-state-id-uniqueness
  (testing "generates unique IDs"
    (let [ids (repeatedly 100 #(server/create-state! {"test" "value"}))]
      (is (= 100 (count (set ids)))))))

;; =============================================================================
;; Parameter Merging Tests (for handle-state-post)
;; =============================================================================

(deftest test-parameter-merging
  (testing "new params merge with existing"
    (let [id1 (server/create-state! {"wallet" "pb1abc" "minAUM" "1000"})
          state1 (server/get-state id1)
          merged (merge (:params state1) {"filter" "active"})]
      (is (= {"wallet" "pb1abc" "minAUM" "1000" "filter" "active"} merged))))

  (testing "same key overrides existing"
    (let [id1 (server/create-state! {"wallet" "pb1old"})
          state1 (server/get-state id1)
          merged (merge (:params state1) {"wallet" "pb1new"})]
      (is (= {"wallet" "pb1new"} merged)))))
