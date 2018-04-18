(ns web.game
  (:require [web.ws :as ws]
            [web.lobby :refer [all-games old-states] :as lobby]
            [web.utils :refer [response]]
            [web.stats :as stats]
            [game.main :as main]
            [game.core :as core]
            [jinteki.utils :refer [side-from-str]]
            [cheshire.core :as json]
            [crypto.password.bcrypt :as bcrypt]
            [clj-time.core :as t]))


(defn send-state-diffs!
  "Sends diffs generated by game.main/public-diffs to all connected clients."
  [{:keys [gameid players spectators] :as game}
   {:keys [type runner-diff corp-diff spect-diff] :as diffs}]
  (doseq [{:keys [ws-id side] :as pl} players]
    (ws/send! ws-id [:netrunner/diff
                     (json/generate-string (if (= side "Corp")
                                             corp-diff
                                             runner-diff))]))
  (doseq [{:keys [ws-id] :as pl} spectators]
    (ws/send! ws-id [:netrunner/diff
                     (json/generate-string  spect-diff)])))

(defn send-state!
  "Sends full states generated by game.main/public-states to all connected clients."
  ([game states]
   (send-state! :netrunner/state game states))

  ([event
    {:keys [gameid players spectators] :as game}
    {:keys [type runner-state corp-state spect-state] :as states}]
   (doseq [{:keys [ws-id side] :as pl} players]
     (ws/send! ws-id [event (json/generate-string (if (= side "Corp")
                                                    corp-state
                                                    runner-state))]))
   (doseq [{:keys [ws-id] :as pl} spectators]
     (ws/send! ws-id [event (json/generate-string spect-state)]))))

(defn swap-and-send-state!
  "Updates the old-states atom with the new game state, then sends a :netrunner/state
  message to game clients."
  [{:keys [gameid state] :as game}]
  (swap! old-states assoc gameid @state)
  (send-state! game (main/public-states state)))

(defn swap-and-send-diffs!
  "Updates the old-states atom with the new game state, then sends a :netrunner/diff
  message to game clients."
  [{:keys [gameid state] :as game}]
  (let [old-state (get @old-states gameid)]
    (when (and state @state)
      (swap! old-states assoc gameid @state)
      (send-state-diffs! game (main/public-diffs old-state state)))))

(defn handle-game-start
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id}]
  (when-let [{:keys [players gameid started] :as game} (lobby/game-for-client client-id)]
    (when (and (lobby/first-player? client-id gameid)
               (not started))
      (let [strip-deck (fn [player] (-> player
                                        (update-in [:deck] #(select-keys % [:_id :identity]))
                                        (update-in [:deck :identity] #(select-keys % [:title :faction]))))
            stripped-players (mapv strip-deck players)
            game (as-> game g
                       (assoc g :started true
                                :original-players stripped-players
                                :ending-players stripped-players
                                :last-update (t/now))
                       (assoc g :state (core/init-game g))
                       (update-in g [:players] #(mapv strip-deck %)))]
        (swap! all-games assoc gameid game)
        (swap! old-states assoc gameid @(:state game))
        (stats/game-started game)
        (lobby/refresh-lobby :update gameid)
        (send-state! :netrunner/start game (main/public-states (:state game)))))))

(defn handle-game-leave
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id}]
  (let [{:keys [started players gameid state] :as game} (lobby/game-for-client client-id)]
    (when (and started state)
      (lobby/remove-user client-id gameid)
      (when-let [game (lobby/game-for-id gameid)]
        ; The game will not exist if this is the last player to leave.
        (main/handle-notification state (str username " has left the game."))
        (swap-and-send-diffs! (lobby/game-for-id gameid))))))

(defn handle-game-rejoin
  [{{{:keys [username _id] :as user} :user} :ring-req
    client-id                           :client-id
    {:keys [gameid]}   :?data
    reply-fn                            :?reply-fn}]
  (let [{:keys [original-players started players state] :as game} (lobby/game-for-id gameid)]
    (if (and started
             (< (count players) 2)
             (some #(= _id (:_id %)) (map :user original-players)))
      (let [player (lobby/join-game user client-id gameid)
            side (keyword (str (.toLowerCase (:side player)) "-state"))]
        (main/handle-rejoin state (:user player))
        (lobby/refresh-lobby :update gameid)
        (ws/send! client-id [:lobby/select {:gameid gameid
                                            :started started
                                            :state (json/generate-string
                                                     (side (main/public-states (:state game))))}])
        (swap-and-send-state! (lobby/game-for-id gameid))))))

(defn handle-game-concede
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id}]
  (let [{:keys [started players gameid state] :as game} (lobby/game-for-client client-id)
        side (some #(when (= client-id (:ws-id %)) (:side %)) players)]
    (when (lobby/player? client-id gameid)
      (main/handle-concede state (side-from-str side))
      (swap-and-send-diffs! game))))

(defn handle-mute-spectators
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id
    mute-state                          :?data}]
  (let [{:keys [gameid started state] :as game} (lobby/game-for-client client-id)
        message (if mute-state "muted" "unmuted")]
    (when (lobby/player? client-id gameid)
      (swap! all-games assoc-in [gameid :mute-spectators] mute-state)
      (main/handle-notification state (str username " " message " specatators."))
      (lobby/refresh-lobby :update gameid)
      (swap-and-send-diffs! game)
      (ws/broadcast-to! (lobby/lobby-clients gameid)
                        :games/diff
                        {:diff {:update {gameid (lobby/game-public-view (lobby/game-for-id gameid))}}}))))

(defn handle-game-action
  [{{{:keys [username] :as user} :user}        :ring-req
    client-id                                  :client-id
    {:keys [gameid-str command args] :as msg}      :?data}]
  (let [gameid (java.util.UUID/fromString gameid-str)
        {:keys [players state] :as game} (lobby/game-for-id gameid)
        old-state (get @old-states gameid)
        side (some #(when (= client-id (:ws-id %)) (:side %)) players)]
    (if (and state side)
      (do
        (main/handle-action user command state (side-from-str side) args)
        (swap! all-games assoc-in [gameid :last-update] (t/now))
        (swap-and-send-diffs! game))
      (do
        (println "HandleGameAction: unknown state or side")
        (println "\tGameID:" gameid)
        (println "\tGameID by ClientID:" (:gameid (lobby/game-for-client client-id)))
        (println "\tCommand:" command)
        (println "\tArgs:" args)
        (println "\tGame:" game)))))

(defn handle-game-watch
  "Handles a watch command when a game has started."
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id
    {:keys [gameid password options]}   :?data
    reply-fn                            :?reply-fn}]
  (if-let [{game-password :password state :state started :started :as game}
           (lobby/game-for-id gameid)]
    (when (and user game (lobby/allowed-in-game game user) state @state)
      (if-not started
        false ; don't handle this message, let lobby/handle-game-watch.
        (if (or (empty? game-password)
                (bcrypt/check password game-password))
          (let [{:keys [spect-state]} (main/public-states state)]
            ;; Add as a spectator, inform the client that this is the active game,
            ;; add a chat message, then send full states to all players.
            ; TODO: this would be better if a full state was only sent to the new spectator, and diffs sent to the existing players.
            (lobby/spectate-game user client-id gameid)
            (main/handle-notification state (str username " joined the game as a spectator."))
            (swap-and-send-state! (lobby/game-for-id gameid))
            (ws/send! client-id [:lobby/select {:gameid gameid
                                                :started started}])
            (when reply-fn (reply-fn 200))
            true)
          (when reply-fn
            (reply-fn 403)
            false))))
    (when reply-fn
      (reply-fn 404)
      false)))

(defn handle-game-say
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id
    {:keys [gameid-str msg]}                :?data}]
  (let [gameid (java.util.UUID/fromString gameid-str)
        {:keys [state mute-spectators] :as game} (lobby/game-for-id gameid)
        {:keys [side user]} (lobby/player? client-id gameid)]
    (if (and state side user)
      (do (main/handle-say state (jinteki.utils/side-from-str side) user msg)
        (swap-and-send-diffs! game))
      (let [{:keys [user]} (lobby/spectator? client-id gameid)]
        (when (and user (not mute-spectators))
          (main/handle-say state :spectator user msg)
          (swap! all-games assoc-in [gameid :last-update] (t/now))
          (swap-and-send-diffs! game))))))

(defn handle-game-typing
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id
    {:keys [gameid-str typing]}             :?data}]
  (let [gameid (java.util.UUID/fromString gameid-str)
        {:keys [state] :as game} (lobby/game-for-id gameid)
        {:keys [side user]} (lobby/player? client-id gameid)]
    (when (and state side user)
      (main/handle-typing state (jinteki.utils/side-from-str side) user typing)
      (swap-and-send-diffs! game))))

(defn handle-ws-close [{{{:keys [username] :as user} :user} :ring-req
                        client-id                           :client-id}]
  (when-let [{:keys [gameid state] :as game} (lobby/game-for-client client-id)]
    (lobby/remove-user client-id (:gameid game))
    (when-let [game (lobby/game-for-id gameid)]
      ; The game will not exist if this is the last player to leave.
      (main/handle-notification state (str username " has disconnected."))
      (swap-and-send-diffs! game))))

(ws/register-ws-handlers!
  :netrunner/start handle-game-start
  :netrunner/action handle-game-action
  :netrunner/leave handle-game-leave
  :netrunner/rejoin handle-game-rejoin
  :netrunner/concede handle-game-concede
  :netrunner/mute-spectators handle-mute-spectators
  :netrunner/say handle-game-say
  :netrunner/typing handle-game-typing
  :lobby/watch handle-game-watch
  :chsk/uidport-close handle-ws-close)
