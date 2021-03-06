(ns fulcro.democards.manual-tests-of-dynamic-queries
  (:require [devcards.core :as dc]
            [fulcro.client.dom :as dom]
            [fulcro.client :as fc]
            [fulcro.server :as server :refer [defquery-root]]
            [goog.object]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client.primitives :as prim :refer [defsc InitialAppState initial-state]]
            [fulcro.client.mutations :as m]
            [fulcro.client.data-fetch :as df]))

(defquery-root :leaf
  (value [{:keys [query]} params]
    (js/console.log :q query)
    (select-keys {:x 99 :y 101} query)))

(declare ui-leaf)

(defsc Leaf [this {:keys [x y]}]
  {:initial-state (fn [params] {:x 1 :y 42})
   :query         (fn [] [:x])
   :ident         (fn [] [:LEAF :ID])}
  (dom/div
    (dom/button {:onClick (fn [] (df/load this :leaf ui-leaf {:refresh [:root/leaf]}))} "Load a leaf using current query")
    (dom/button {:onClick (fn [] (prim/set-query! this ui-leaf {:query [:x]}))} "Set query to :x")
    (dom/button {:onClick (fn [] (prim/set-query! this ui-leaf {:query [:y]}))} "Set query to :y")
    (dom/button {:onClick (fn [e] (if x
                                    (m/set-value! this :x (inc x))
                                    (m/set-value! this :y (inc y))))}
      (str "Count: " (or x y)))
    " Leaf"))

(def ui-leaf (prim/factory Leaf {:qualifier :x}))

(defsc Root [this {:keys [root/leaf] :as props}]
  {:initial-state (fn [p] {:root/leaf (prim/get-initial-state Leaf {})})
   :query         (fn [] [{:root/leaf (prim/get-query ui-leaf)}])}
  (dom/div
    (ui-leaf leaf)))

(defcard-fulcro union-initial-app-state
  Root
  {}
  {:inspect-data true
   :fulcro       {:networking (server/new-server-emulator)}})

(comment
  ; live manual test of query IDs and pulling query for live components
  (let [reconciler (-> union-initial-app-state-fulcro-app deref :reconciler)
        state      (prim/app-state reconciler)
        indexer    (prim/get-indexer reconciler)
        component  (first (prim/ref->components reconciler [:LEAF :ID]))]
    [(prim/get-query-id component)
     (prim/get-query component @state)                      ;using component instance
     (prim/get-query ui-leaf @state)])                      ; using factory

  (-> Leaf meta))

