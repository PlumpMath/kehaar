(ns kehaar.wire-up-test
  (:require [kehaar.wire-up :refer :all]
            [clojure.test :refer :all]
            [clojure.core.async :as async]
            [langohr.core :as rmq]
            [langohr.channel :as lch]
            [langohr.queue :as lq]
            [langohr.basic :as lb]
            [langohr.consumers :as lc]
            [clojure.tools.logging :as log]
            [kehaar.async :refer [bounded<!! bounded>!!]]))

(deftest ^:rabbit-mq events-test
  (testing "we can publish events and receive them, going through rabbit"
    (let [conn   (rmq/connect)
          ch-out (async/chan 1000)
          ch-in  (async/chan 1000)
          chs [(declare-events-exchange conn "events" "topic" {})
               (incoming-events-channel conn "test-in" {} "events" "test-event" ch-in 1000)
               (outgoing-events-channel conn "events" "test-event" ch-out)]]
      (try
        (dotimes [x 1000]
          (let [x (java.util.UUID/randomUUID)]
            (bounded>!! ch-out {:hello! :there! :uuid x} 100)
            (is (= {:hello! :there! :uuid x} (bounded<!! ch-in 100)))))
        (finally
          (async/close! ch-in)
          (async/close! ch-out)
          (doseq [ch chs]
            (rmq/close ch))
          (rmq/close conn)))))

  (testing "we can publish a bunch of events and then receive them
  later, going through rabbit"
    (let [conn   (rmq/connect)
          ch-out (async/chan 1000)
          ch-in  (async/chan 1000)
          chs [(declare-events-exchange conn "events" "topic" {})
               (incoming-events-channel conn "test-in" {} "events" "test-event" ch-in 1000)
               (outgoing-events-channel conn "events" "test-event" ch-out)]]
      (try
        (let [messages (for [x (range 1000)
                             :let [x (java.util.UUID/randomUUID)]]
                         {:hello! x})]
          (doseq [message messages]
            (bounded>!! ch-out message 100))
          (doseq [message messages]
            (is (= message (bounded<!! ch-in 100)))))
        (finally
          (async/close! ch-in)
          (async/close! ch-out)
          (doseq [ch chs]
            (rmq/close ch))
          (rmq/close conn)))))

  (testing "we can set up a handler function on incoming events"
    (let [conn   (rmq/connect)
          ch-out (async/chan 1000)
          ch-in  (async/chan 1000)
          test-chan (async/chan 1)
          chs [(declare-events-exchange conn "events" "topic" {})
               (incoming-events-channel conn "test-in" {} "events" "test-event" ch-in 1000)
               (outgoing-events-channel conn "events" "test-event" ch-out)]]
      (try
        (start-event-handler! ch-in (fn [message]
                                      (bounded>!! test-chan :hello 100)))
        (bounded>!! ch-out :hello 100)
        (is (= :hello (bounded<!! test-chan 100)))
        (finally
          (async/close! ch-in)
          (async/close! ch-out)
          (doseq [ch chs]
            (rmq/close ch))
          (rmq/close conn))))))

(deftest event-handler-test
  (testing "our event handler fires"
    (let [in (async/chan)
          out (async/chan)]
      (try
        (start-event-handler! in (fn [_] (async/put! out 1)))
        (async/put! in 100)
        (is (= 1 (bounded<!! out 100)))
        (finally
          (async/close! in)
          (async/close! out))))))

(deftest simple-service-test
  (testing "we can return a value"
    (let [in (async/chan 1)
          out (async/chan 1)
          service-fn (fn [{:keys [n]}]
                       (* 10 n))
          metadata {:dude 100}]
      (try
        (start-responder! in out service-fn)
        (bounded>!! in {:message {:n 20}
                        :metadata metadata} 100)
        (is (= {:message 200
                :metadata metadata} (bounded<!! out 100)))
        (finally (async/close! out)
                 (async/close! in)))))

  (testing "we can return nil"
    (let [in (async/chan 1)
          out (async/chan 1)
          service-fn (fn [_]
                       nil)
          metadata {:dude 100}]
      (try
        (start-responder! in out service-fn)
        (bounded>!! in {:message {:n 20}
                        :metadata metadata} 100)
        (is (nil? (:message (bounded<!! out 100))))
        (finally (async/close! out)
                 (async/close! in)))))

  (testing "we can return a channel"
    (let [in (async/chan 1)
          out (async/chan 1)
          service-fn (fn [{:keys [n]}]
                       (async/go (* 10 n)))
          metadata {:dude 100}]
      (try
        (start-responder! in out service-fn)
        (bounded>!! in {:message {:n 20}
                        :metadata metadata} 100)
        (is (= {:message 200
                :metadata metadata} (bounded<!! out 100)))
        (finally (async/close! out)
                 (async/close! in))))))

(deftest ^:rabbit-mq service-test
  (testing "create an incoming service and an outgoing services that
  calls it, roundtripping through rabbit."
    (let [conn   (rmq/connect)
          ch-ext (async/chan 1000)
          ch-in  (async/chan 1000)
          ch-out  (async/chan 1000)
          test-chan (async/chan 1)
          chs [(incoming-service conn "this.is.my.service"
                                 {:exclusive false :durable false :auto-delete true}
                                 ch-in ch-out)
               (external-service conn "this.is.my.service" ch-ext)]
          f (async->fn ch-ext)]
      (try
        (start-responder! ch-in ch-out
                          (fn [message]
                            (log/debug "I am here!")
                            {:answer (* 100 (:n message))}))
        (is (= {:answer 3400} (bounded<!! (f {:n 34}) 1000)))
        (finally
          (async/close! ch-in)
          (async/close! ch-ext)
          (async/close! ch-out)
          (doseq [ch chs]
            (rmq/close ch))
          (rmq/close conn))))))

(deftest async->fn-test
  (let [c (async/chan 1) ;; we need buffered channels for external services
        response-fn (async->fn c)
        message {:test true}
        response-channel (response-fn message)]
    (is (= [response-channel message] (async/<!! c)))))
