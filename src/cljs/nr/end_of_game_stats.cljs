(ns nr.end-of-game-stats
  (:require [nr.translations :refer [tr tr-side]]
            [nr.utils :refer [map-longest]]))

(defn computed-stat
  [s stat-tr key transform]
  (let [val (get-in s key)
        val (when val (transform val))]
    (when (and val (pos? val))
      [stat-tr val])))

(defn optional-stat
  [s stat-tr key]
  (let [val (get-in s key)]
    (when (and val (pos? val))
      [stat-tr (get-in s key)])))

(defn corp-stats [s]
  (vec (remove nil?
               [[(tr [:stats_clicks-gained "Clicks Gained"]) (get-in s [:gain :click])]
                [(tr [:stats_credits-gained "Credits Gained"]) (get-in s [:gain :credit])]
                [(tr [:stats_credits-spent "Credits Spent"]) (get-in s [:spent :credit])]
                [(tr [:stats_credits-click "Credits by the Basic Action"]) (get-in s [:click :credit])]
                [(tr [:stats_cards-drawn "Cards Drawn"]) (get-in s [:gain :card])]
                [(tr [:stats_cards-click "Cards Drawn by the Basic Action"]) (get-in s [:click :draw])]
                [(tr [:stats_damage-done "Damage Done"]) (get-in s [:damage :all])]
                [(tr [:stats_cards-rezzed "Cards Rezzed"]) (get-in s [:cards :rezzed])]
                (optional-stat s (tr [:stats_shuffle-count "Shuffle Count"]) [:shuffle-count])
                (optional-stat s (tr [:stats_operations-played "Operations Played"]) [:cards-played :play-instant])
                (optional-stat s (tr [:stats_rashida-count "Rashida Count"]) [:rashida-count])
                ;; psi games
                (optional-stat s (tr [:stats_psi-game-total "Psi Game: Games Played"]) [:psi-game :games-played])
                (optional-stat s (tr [:stats_psi-game-total-wins "Psi Game: Wins"]) [:psi-game :wins])
                (optional-stat s (tr [:stats_psi-game-total-bid-0 "Psi Game: Bid 0"]) [:psi-game :bet-0])
                (optional-stat s (tr [:stats_psi-game-total-bid-1 "Psi Game: Bid 1"]) [:psi-game :bet-1])
                (optional-stat s (tr [:stats_psi-game-total-bid-2 "Psi Game: Bid 2"]) [:psi-game :bet-2])
                ])))

(defn runner-stats [s]
  (vec (remove nil?
               [[(tr [:stats_clicks-gained "Clicks Gained"]) (get-in s [:gain :click])]
                [(tr [:stats_credits-gained "Credits Gained"]) (get-in s [:gain :credit])]
                [(tr [:stats_credits-spent "Credits Spent"]) (get-in s [:spent :credit])]
                [(tr [:stats_credits-click "Credits by the Basic Action"]) (get-in s [:click :credit])]
                [(tr [:stats_cards-drawn "Cards Drawn"]) (get-in s [:gain :card])]
                [(tr [:stats_cards-click "Cards Drawn by the Basic Action"]) (get-in s [:click :draw])]
                [(tr [:stats_tags-gained "Tags Gained"]) (get-in s [:gain :tag :base])]
                [(tr [:stats_runs-made "Runs Made"]) (get-in s [:runs :started])]
                [(tr [:stats_cards-accessed "Cards Accessed"]) (get-in s [:access :cards])]
                (optional-stat s (tr [:stats_shuffle-count "Shuffle Count"]) [:shuffle-count])
                (optional-stat s (tr [:stats_cards-sabotaged "Sabotage Count"]) [:cards-sabotaged])
                (optional-stat s (tr [:stats_events-played "Events Played"]) [:cards-played :play-instant])
                (computed-stat s (tr [:stats_unique-accesses "Unique Cards Accessed"]) [:access :unique-cards] count)
                ;; psi games
                (optional-stat s (tr [:stats_psi-game-total "Psi Game: Games Played"]) [:psi-game :games-played])
                (optional-stat s (tr [:stats_psi-game-total-wins "Psi Game: Wins"]) [:psi-game :wins])
                (optional-stat s (tr [:stats_psi-game-total-bid-0 "Psi Game: Bid 0"]) [:psi-game :bet-0])
                (optional-stat s (tr [:stats_psi-game-total-bid-1 "Psi Game: Bid 1"]) [:psi-game :bet-1])
                (optional-stat s (tr [:stats_psi-game-total-bid-2 "Psi Game: Bid 2"]) [:psi-game :bet-2])
                ])))

(defn show-stat
  "Determines statistic counter and if it should be shown"
  [side]
  (when-let [stat (-> side second)]
    (if (pos? stat) stat "-")))

(defn build-game-stats
  "Builds the end of game statistics div & table"
  [corp runner]
  (let [stats (map-longest list nil (corp-stats corp) (runner-stats runner))]
    [:div
     [:table.win.table
      [:tbody
       [:tr.win.th
        [:td.win.th [tr-side "Corp"]] [:td.win.th]
        [:td.win.th [tr-side "Runner"]] [:td.win.th]]
       (doall (map-indexed
                (fn [i [corp runner]]
                  [:tr {:key i}
                   [:td (first corp)] [:td (show-stat corp)]
                   [:td (first runner)] [:td (show-stat runner)]])
                stats))]]]))
