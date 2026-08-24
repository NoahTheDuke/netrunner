(ns web.system
  (:require
   [aero.core :as aero]
   [cljc.java-time.local-date :as ld]
   [clojure.java.io :as io]
   [game.cards.agendas]
   [game.cards.assets]
   [game.cards.basic]
   [game.cards.events]
   [game.cards.hardware]
   [game.cards.ice]
   [game.cards.identities]
   [game.cards.operations]
   [game.cards.programs]
   [game.cards.resources]
   [game.cards.upgrades]
   [game.quotes :refer [load-quotes!]]
   [integrant.core :as ig]
   [jinteki.cards :as cards]
   [taoensso.carmine :as car :refer [wcar]]
   [jinteki.i18n :as i18n]
   [medley.core :refer [deep-merge]]
   [monger.collection :as mc]
   [monger.core :as mg]
   [org.httpkit.server :refer [run-server server-stop!]]
   [taoensso.sente :as sente]
   [time-literals.data-readers]
   [time-literals.read-write :as read-write]
   [web.api :refer [make-app make-dev-app]]
   [web.app-state :as app-state]
   [web.game]
   [web.lobby :as lobby]
   [web.logs :refer [timbre-init!]]
   [web.telemetry]
   [web.utils :refer [tick]]
   [web.ws :as ws]) 
  (:import
   [clojure.lang ExceptionInfo]
   [org.httpkit.server HttpServer]))

(read-write/print-time-literals-clj!)

(defmethod aero/reader 'ig/ref
  [_ _ value]
  (ig/ref value))

(defn server-config []
  (let [dev-file (io/resource "dev.edn")
        dev-config (when dev-file (aero/read-config dev-file))
        prod-file (io/resource "prod.edn")
        master-config (when prod-file (aero/read-config prod-file))]
    (deep-merge dev-config master-config)))

(defmethod ig/init-key :server/mode [_ mode]
  mode)

(defmethod ig/init-key :mongodb/connection [_ opts]
  (let [{:keys [address port name connection-string]} opts
        connection (or connection-string (str "mongodb://" address ":" port "/" name))]
    (mg/connect-via-uri connection)))

(defmethod ig/halt-key! :mongodb/connection [_ {:keys [conn]}]
  (mg/disconnect conn))

(defmethod ig/init-key :redis/connection [_ {:keys [pool spec]}]
  {:pool (car/connection-pool pool)
   :spec spec})

(defmethod ig/halt-key! :redis/connection [_ {:keys [pool]}]
  (taoensso.carmine.connections.ConnectionPool/.close pool))

(defmethod ig/init-key :logging/timbre [_ config]
  (timbre-init! config))

(defmethod ig/init-key :web/app [_ opts]
  (if (:server-mode opts)
    (make-app opts)
    (make-dev-app opts)))

(defmethod ig/init-key :web/app-state [_ _]
  (reset! app-state/app-state app-state/base-app-state))

(defmethod ig/halt-key! :web/app-state [_ _]
  (reset! app-state/app-state app-state/base-app-state))

(defmethod ig/init-key :web/server [_ {:keys [app port]}]
  (let [^HttpServer s (run-server app {:port port
                                       :legacy-return-value? false})]
    {:server s
     :port (.getPort s)}))

(defmethod ig/halt-key! :web/server [_ {server :server}]
  (when server
    (server-stop! server nil)))

(defmethod ig/init-key :web/auth [_ settings]
  settings)

(defmethod ig/init-key :web/lobby [_ {:keys [interval mongo time-inactive]}]
  (let [db (:db mongo)]
    [(tick #(lobby/clear-inactive-lobbies db time-inactive) interval)]))

(defmethod ig/halt-key! :web/lobby [_ futures]
  (run! future-cancel futures))

(defmethod ig/init-key :web/chat [_ settings]
  settings)

(defmethod ig/init-key :web/email [_ settings]
  settings)

(defmethod ig/init-key :web/banned-msg [_ {:keys [initial redis]
                                           {:keys [db]} :mongo}]
  (if-let [msg (:banned-msg (mc/find-one-as-map db "config" nil))]
    (do (wcar redis (car/set :config/banned-msg msg))
      msg)
    (do (mc/insert-and-return db "config" {:banned-msg initial})
      (wcar redis (car/set :config/banned-msg initial))
      initial)))

(defmethod ig/init-key :frontend/version [_ {:keys [initial redis]
                                             {:keys [db]} :mongo}]
  (if-let [version (:version (mc/find-one-as-map db "config" nil))]
    (do (wcar redis (car/set :config/version version))
      version)
    (do (mc/insert-and-return db "config" {:version initial})
      (wcar redis (car/set :config/version initial))
      initial)))

(defmethod ig/init-key :web/ws [_ opts]
  (ws/start-server! opts)
  opts)

(defmethod ig/halt-key! :web/ws [_ _]
  (ws/stop-server!))

(defmethod ig/init-key :sente/router [_ _opts]
  (sente/start-server-chsk-router!
    (ws/ch-chsk)
    ws/event-msg-handler))

(defmethod ig/halt-key! :sente/router [_ stop-fn]
  (when (fn? stop-fn)
    (stop-fn)))

(defmethod ig/init-key :game/quotes [_ _opts]
  (load-quotes!))

(defmethod ig/init-key :web/i18n [_ _opts]
  (i18n/load-dictionary! "public/i18n"))

(defmethod ig/halt-key! :web/i18n [_ _opts]
  (reset! i18n/fluent-dictionary nil))

(defn- format-card-key->string
  [fmt]
  (assoc fmt :cards
         (->> (:cards fmt)
              (reduce-kv
               (fn [m k v]
                 (assoc! m (name k) v))
               (transient {}))
              (persistent!))))

(defmethod ig/init-key :jinteki/cards-version [_ {:keys [initial redis]
                                                  {:keys [db]} :mongo}]
  (if-let [version (:version (mc/find-one-as-map db "config" nil))]
    (do (wcar redis (car/set :config/cards-version version))
      version)
    (do (mc/insert-and-return db "config" {:version initial})
      (wcar redis (car/set :config/cards-version initial))
      initial)))

(defmethod ig/init-key :jinteki/cards [_ {{:keys [db]} :mongo}]
  (let [cards (mc/find-maps db "cards" nil)
        stripped-cards (mapv #(update % :_id str) cards)
        all-cards (into {} (map (juxt :title identity)) stripped-cards)
        sets (mc/find-maps db "sets" nil)
        cycles (mc/find-maps db "cycles" nil)
        mwl (mc/find-maps db "mwls" nil)
        latest-mwl (->> mwl
                        (map (fn [e] (update e :date-start ld/parse)))
                        (group-by #(keyword (:format %)))
                        (mapv (fn [[k, v]] [k (->> v
                                                  (sort-by :date-start)
                                                  (last)
                                                  (format-card-key->string))]))
                        (into {}))]
    (reset! cards/all-cards all-cards)
    (reset! cards/sets sets)
    (reset! cards/cycles cycles)
    (reset! cards/mwl latest-mwl)
    {:all-cards all-cards
     :sets sets
     :cycles cycles
     :mwl latest-mwl}))

(defmethod ig/halt-key! :jinteki/cards [_ _opts]
  (reset! cards/all-cards nil)
  (reset! cards/sets nil)
  (reset! cards/cycles nil)
  (reset! cards/mwl nil))

(defn stop [system & {:keys [only]}]
  (when system
    (if only
      (ig/halt! system only)
      (ig/halt! system)))
  nil)

(defn start
  [& {:keys [only]}]
  (let [config (server-config)]
    (try (if only
           (ig/init config only)
           (ig/init config))
         (catch ExceptionInfo ex
           (stop (:system (ex-data ex)))))))

(comment
  (def system (start {:only [:redis/connection]}))
  (wcar system
    (car/set :hello/world 1)
    (car/get :hello/world)
    )
  (stop system)
  )
