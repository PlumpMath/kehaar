(ns kehaar.core
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [langohr.basic :as lb]
            [langohr.consumers :as lc]
            [langohr.queue :as lq]
            [clojure.tools.logging :as log]
            [kehaar.async :refer [bounded>!!]]))

(defn read-payload
  "Unsafely read a byte array as edn."
  [^bytes payload]
  (-> payload
      (String. "UTF-8")
      edn/read-string))

(defn channel-handler
  "Returns a RabbitMQ message handler function which puts each
  incoming message on `channel`."
  ([channel exchange timeout]
   (channel-handler channel exchange timeout false))
  ([channel exchange timeout close-channel?]
   (fn [ch {:keys [delivery-tag] :as metadata} ^bytes payload]
     (try
       (let [message (read-payload payload)]
         (cond
           (nil? message)
           ;; don't requeue nil, it's invalid
           [:nack delivery-tag false]

           (= ::stop message)
           ;; ack it and close the channel if close-channel?
           (do
             (if close-channel?
               (async/close! channel)
               (bounded>!! channel {:message message
                                    :metadata metadata} timeout))
             [:ack delivery-tag])

           :else
           (if (bounded>!! channel {:message  message
                                    :metadata metadata} timeout)
             ;; successfully put, ack now
             [:ack delivery-tag]
             ;; assume we're busy, requeue timed out
             [:nack delivery-tag true])))
       (catch Throwable t
         (log/error t "Kehaar: fn->handler-fn: payload did not parse"
                    (String. payload "UTF-8"))
         ;; don't requeue parse errors
         [:nack delivery-tag false])))))

(defn- ack-or-nack [channel [op delivery-tag requeue :as ret]]
  (case op
    :ack  (lb/ack  channel delivery-tag)
    :nack (lb/nack channel delivery-tag false requeue)
    ;; otherwise, let's log that
    (log/warn "Kehaar: I don't know how to process this:" ret
              "I'm designed to process messages containing :ack or :nack.")))

(defn rabbit=>async
  "Subscribes to the RabbitMQ queue, taking each payload, decoding as
  edn, and putting the result onto the async channel. This propagates
  backpressure back to rabbit by using nacks."
  ([rabbit-channel queue channel]
   (rabbit=>async rabbit-channel queue channel {} 100))
  ([rabbit-channel queue channel options timeout]
   (rabbit=>async rabbit-channel queue channel options timeout false))
  ([rabbit-channel queue channel options timeout close-channel?]
   (lc/subscribe rabbit-channel
                 queue
                 (comp (partial ack-or-nack rabbit-channel)
                       (channel-handler channel "" timeout close-channel?))
                 (merge options {:auto-ack false}))))

(defmacro go-handler
  "A macro that runs code in `body`, with `binding` bound to each
  message coming in on `channel`."
  [[binding channel] & body]
  `(let [channel# ~channel]
     (async/go-loop []
       (let [ch-message# (async/<! channel#)]
         (if (nil? ch-message#)
           (log/warn "Kehaar: go handler" '~channel "is closed.")
           (do
             (try
               (let [~binding ch-message#]
                 ~@body)
               (catch Throwable t#
                 (log/error t# "Kehaar: caught an exception in go-handler")))
             (recur)))))))

(defn async=>rabbit
  "Forward all messages on channel to the RabbitMQ queue. Messages
  should look like:

  ```
  {:message  {...}  ;; message payload
  :metadata {...}} ;; rabbit metadata (optional)
  ```"
  ([channel rabbit-channel queue]
   (async=>rabbit channel rabbit-channel "" queue))
  ([channel rabbit-channel exchange queue]
   (go-handler [{:keys [message metadata]} channel]
               (lb/publish rabbit-channel exchange queue (pr-str message) metadata))))

(defn async=>rabbit-with-reply-to
  "Forward all messages on channel to the RabbitMQ queue specified in
  `:reply-to` metadata. Messages should look like:

  ```
  {:message  {...}  ;; message payload
  :metadata {:repy-to \"queue\"
  ...}} ;; rabbit metadata
  ```"
  ([channel rabbit-channel]
   (async=>rabbit-with-reply-to channel rabbit-channel ""))
  ([channel rabbit-channel exchange]
   (go-handler [{:keys [message metadata]} channel]
    (if-let [reply-to (:reply-to metadata)]
      (lb/publish rabbit-channel exchange (:reply-to metadata) (pr-str message) metadata)
      (log/warn "Kehaar: No reply-to in metadata."
                (pr-str message)
                (pr-str metadata))))))

(defn thread-handler
  [channel f]
  (async/thread
    (loop []
      (let [ch-message (async/<!! channel)]
        (if (nil? ch-message)
          (log/warn "Kehaar: thread handler is closed.")
          (do
            (try
              (async/thread
                (f ch-message))
              (catch Throwable t
                (log/error t "Kehaar: caught exception in thread-handler")))
            (recur)))))))

(defn responder-fn
  "Create a function that takes map of message and metadata, calls `f`
  on message, and redirects the return to `out-channel`. This is used
  by `responder`. Handles two cases: async channel return and regular
  value return."
  [out-channel f]
  (fn [{:keys [message metadata]}]
    (let [return (f message)]
      (if (satisfies? clojure.core.async.impl.protocols/ReadPort return)
        (let [metadata-channel (async/chan 1000 (map (fn [message]
                                                       {:message message
                                                        :metadata metadata})))]
          (async/pipe return metadata-channel true)
          (async/pipe metadata-channel out-channel false))

        (async/>!! out-channel {:message return
                                :metadata metadata})))))

(defn streaming-responder-fn
  [connection out-channel f threshold]
  (fn [{:keys [message metadata]}]
    (let [return (f message)
          size-or-threshold (count (take threshold return))]
      (if (= threshold size-or-threshold)
        ;; big
        ;; create new queue
        (let [ch (langohr.channel/open connection)
              response-queue (langohr.queue/declare-server-named
                              ch
                              {:exclusive false
                               :auto-delete true
                               :durable true})]
          ;; put queue name on out-channel
          (async/>!! out-channel {:message {::response-queue response-queue}
                                  :metadata metadata})

          ;; publish everything from return onto it
          ;; maybe on a new thread?
          (doseq [v return]
            (lb/publish ch "" response-queue (pr-str v) metadata))
          (lb/publish ch "" response-queue (pr-str ::stop) metadata))

        ;; small
        (do
          ;; put "the results are going to come on this channel message"
          (async/>!! out-channel {:message {::inline size-or-threshold}
                                  :metadata metadata})
          ;; put each return value
          (doseq [v return]
            (async/>!! out-channel {:message v
                                    :metadata metadata}))

          (async/>!! out-channel {:message ::stop
                                  :metadata metadata}))))))
