(ns kehaar-example.streaming.consumer
  (:require [kehaar.rabbitmq :as kehaar-rabbit]
            [kehaar.configure :as configure]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]))

(declare get-countdown)

(defn -main [& args]
  (log/info "Consumer starting up...")
  (let [connection (kehaar-rabbit/connect-with-retries)]

    (configure/configure!
     connection
     {:external-services
      [{:streaming? true
        :exchange ""
        :queue "countdown"
        :queue-options {}
        :timeout 5000
        :f 'kehaar-example.streaming.consumer/get-countdown}]})

    (log/info "Consumer making a request!")
    (doseq [n [10 10 3 3 10]]
      (let [return-ch (get-countdown {:num n})]
        (loop []
          (when-let [v (async/<!! return-ch)]
            (log/info "Got" v)
            (recur)))))
    (log/info "Consumer making a request!")
    (let [return-ch (get-countdown {:num 4 :delay 3000})
          v (async/<!! return-ch)]
      (log/info "Got" v)
      (async/close! return-ch)
      (log/info "Closed return channel"))
    (log/info "Consumer making a request!")
    (let [return-ch (get-countdown {:num 100 :delay 200})]
      (dotimes [n 10]
        (when-let [v (async/<!! return-ch)]
          (log/info "Got" v)))
      (async/close! return-ch)
      (log/info "Closed return channel"))
    (doseq [n [2 2 2 2 2]]
      (let [return-ch (get-countdown {:num n})]
        (loop []
          (when-let [v (async/<!! return-ch)]
            (log/info "Got" v)
            (recur)))))
    (Thread/sleep 10000)
    (System/exit 0)))
