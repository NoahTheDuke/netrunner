(ns web.ws
  (:require
   [cljc.java-time.instant :as inst]
   [clojure.core.async :refer [<! >! alt! chan close! go go-loop put! timeout]]
   [integrant.core :as ig]
   [jinteki.msgpack-ext]
   [taoensso.sente :as sente]
   [taoensso.sente.packers.msgpack :as msgpack]
   [taoensso.sente.server-adapters.http-kit :as sente.http-kit]
   [taoensso.timbre :as timbre]
   [web.app-state :refer [register-user!]]
   [web.user :refer [active-user?]]))

(set! *warn-on-reflection* true)

(defn handshake-handler
  "Ring handler fn so accepts a ring request"
  [{:system/keys [ws] :as request}]
  (when-let [handshake-fn (:handshake-fn ws)]
    (try (handshake-fn request)
         (catch Exception ex
           (timbre/error ex "Caught an error in the ws handshake handler")))))

(defn post-handler
  "Ring handler fn so accepts a ring request"
  [{:system/keys [ws] :as request}]
  (when-let [ajax-post-fn (:ajax-post-fn ws)]
    (try (ajax-post-fn request)
         (catch Exception ex
           (timbre/error ex "Caught an error in the ws post handler")))))

(defn chsk-send! [ws uid ev]
  (when-let [send-fn (:send-fn ws)]
    (send-fn uid ev)))

(defn connected-sockets [ws]
  (when-let [connected-uids (:connected-uids ws)]
    @connected-uids))

(defn connections
  "internal sente info, ideally don't use this outside of debugging"
  [ws]
  (when-let [connections (:conns_ (:private ws))]
    @connections))

(defn connected-uids [ws] (seq (:any (connected-sockets ws))))

(defn buffer-stats [ws]
  (when-let [{:keys [buffer buffer-size]} ws]
    {:pending (count (.-buf ^clojure.core.async.impl.channels.ManyToManyChannel buffer))
     :size buffer-size}))

(defn broadcast-to!
  "Sends the given event and msg to all clients in the given uids sequence."
  [ws uids event msg]
  ;; TODO in high stress situations, multiple go blocks could be competing.
  ;; This could result in out of order messages and thus a stale client.
  ;; To fix, we would want to keep the order of loading correct perhaps by blocking
  ;; successive go blocks until the previous ones have completed
  (let [buffer (:buffer ws)]
    (go
      (doseq [client uids
              :when (some? client)]
        ;; Block if we have recently sent a lot of messages. The data supplied is arbitrary
        (when buffer (>! buffer true))
        (chsk-send! ws client [event msg])))))

(defmulti -msg-handler
  "Multimethod to handle Sente `event-msg`s"
  :id)

(defmethod -msg-handler :default
  msg-handler--default
  ;; Handles any hecked messages from the client
  [{:keys [id ?data uid ?reply-fn]}]
  (timbre/error "Unhandled WS msg" id uid (pr-str ?data))
  (when ?reply-fn
    (?reply-fn {:msg "Unhandled event"})))

(defmethod -msg-handler :chsk/ws-ping chsk--ws-ping [_])
(defmethod -msg-handler :chsk/ws-pong chsk--ws-pong [_])
;; NOTE - :chsk/uidport-close is handled in game.clj
(defmethod -msg-handler :chsk/uidport-open
  chsk--uidport-open
  [{uid :uid
    {user :user} :ring-req}]
  (when (active-user? user)
    (register-user! uid user)))

(defn event-msg-handler
  "Wraps `-msg-handler` with logging, error catching, etc."
  [event]
  (try
    (-msg-handler (assoc event :timestamp (inst/now)))
    (catch Exception e
      (timbre/error e "Caught an error in the message handler"))))

;; buffer-clear-timer-ms:
;; Maximum throughput is 25,000 client updates a second
;; or 1024 pending broadcast-to!'s (asyncs limit for pending takes).
;; At a duration of 40ms, a maximum of 2 buffer sizes can be processed
;; in one sente tick (sentes buffer window is 30ms)
;;
;; buffer-size:
;; If two buffers can be exhausted in one sente tick, we should use a max
;; buffer size of roughly half the 1024 core.async limit
(defmethod ig/init-key :web/ws
  [_ {:keys [packer buffer-size buffer-clear-timer-ms] :as opts
      :or {buffer-size 500
           buffer-clear-timer-ms 40}}]
  (let [{:keys [ch-recv ajax-post-fn ajax-get-or-ws-handshake-fn send-fn connected-uids]}
        (sente/make-channel-socket-server!
          (sente.http-kit/get-sch-adapter)
          {:ws-kalive-ms 2500
           :packer (if (= packer :edn)
                     :edn
                     (msgpack/get-packer))
           :user-id-fn (fn [ring-req]
                         (or (-> ring-req :session :uid)
                           (:client-id ring-req)))})
        buffer (chan buffer-size)
        rate-limiter (let [exit-ch (chan)]
                       (go-loop []
                         (let [timeout-ch (timeout (int buffer-clear-timer-ms))]
                           (alt!
                             exit-ch nil
                             timeout-ch (when (loop [n buffer-size]
                                                (or (zero? n)
                                                  (when (<! buffer)
                                                    (recur (dec n)))))
                                          (recur)))))
                       exit-ch)]
    (-> opts
      (assoc :buffer buffer)
      (assoc :buffer-size buffer-size)
      (assoc :buffer-clear-timer-ms buffer-clear-timer-ms)
      (assoc :rate-limiter rate-limiter)
      (assoc :ch-chsk ch-recv)
      (assoc :ajax-post-fn ajax-post-fn)
      (assoc :handshake-fn ajax-get-or-ws-handshake-fn)
      (assoc :send-fn send-fn)
      (assoc :connected-uids connected-uids)
      (assoc :stop-fn (sente/start-server-chsk-router! ch-recv #'event-msg-handler)))))

(defmethod ig/halt-key! :web/ws [a {:keys [buffer rate-limiter stop-fn] :as opts}]
  (when rate-limiter (put! rate-limiter true))
  (when buffer (close! buffer))
  (when (fn? stop-fn) (stop-fn))
  nil)
