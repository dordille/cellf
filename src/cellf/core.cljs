(ns ^:figwheel-always cellf.core
    (:require [om.core :as om :include-macros true]
              [om.dom :as dom :include-macros true]
              [cljs.core.async :refer [<! >! take! put! chan timeout]]
              [cellf.media :as media])
    (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(enable-console-print!)

(def default-size 3)
(def capture-size 250)
(def tick-ms      150)
(def resize-ms    200)

(defonce img-cache (atom []))

(defn sq [n]
  (* n n))

(defn t3d [x y]
  (str "translate3d(" x "%," y "%,0)"))

(defn promise-chan []
  (let [c (chan)]
    (take! c #(go-loop []
      (>! c %)
      (recur)))
    c))

(defn capture-move [{:keys [ctx vid-node canvas-node vid-w vid-h]} tiles]
  (.drawImage ctx vid-node (/ (- vid-w vid-h) 2) 0 vid-h vid-h 0 0 capture-size capture-size)
  (let [img-data (.toDataURL canvas-node "image/jpeg" 1)
        img-el   (js/Image.)
        pc       (promise-chan)]
    (swap! img-cache conj pc)
    (aset img-el "src" img-data)
    (aset img-el "onload" (fn []
      (js-delete img-el "onload")
      (put! pc img-el)))
    {:tiles tiles :image img-data}))

(defn make-tile-list [size]
  (conj (vec (range (dec (sq size)))) :empty))

(defn make-tiles [size win-state]
  (let [shuffled (zipmap (make-tile-list size) (shuffle (range (sq size))))]
    (if (= shuffled win-state) (recur size win-state) shuffled)))

(defn make-win-state [size]
  (zipmap (make-tile-list size) (range (sq size))))

(defn make-game
  ([app size]
    (make-game app size tick-ms))
  ([app size speed]
    (let [win-state (make-win-state size)
          tiles     (make-tiles size win-state)]
      {
        :moves     [(capture-move app tiles)]
        :tick      0
        :tick-ms   tick-ms
        :grid-size size
        :tiles     tiles
        :win-state win-state})))

(def get-tile-xy (juxt mod quot))

(defn get-tile-transform [app i]
  (let [size  (:grid-size app)
        pct   (str (/ 100 size) \%)
        [x y] (get-tile-xy i size)]
    #js {
      :transform (t3d (* 100 x) (* 100 y))
      :width     pct
      :height    pct}))


(defn get-bg-transform [app i]
  (if (= i :empty)
    nil
    (let [size  (:grid-size app)
          pct   (/ 100 size)
          [x y] (get-tile-xy i size)]
      #js {
        :height    (str (* size 100) "%")
        :transform (t3d
          (- (+ (/ (* x pct) (:vid-ratio app)) (:vid-offset app)))
          (- (* pct y)))})))


(defn adj? [app i]
  (let [{size :grid-size {emp :empty} :tiles} app]
    (or
      (= i (- emp size))
      (and (= i (dec emp)) (pos? (mod emp size)))
      (and (= i (inc emp)) (pos? (mod (inc emp) size)))
      (= i (+ emp size)))))


(defn swap-tile [tiles n]
  (let [current-idx (tiles n)
        empty-idx   (:empty tiles)]
    (into {} (map (fn [[k idx]]
      (if (= k :empty)
        [:empty current-idx]
        (if (= k n) [k empty-idx] [k idx]))) tiles))))


(defn move! [{:keys [moves win-state] :as app} n]
  (let [new-layout (swap-tile (:tiles app) n)]
    (om/update! app :tiles new-layout)
    (om/update! app :moves (conj moves (capture-move app new-layout)))))


(defn new-game! [app size speed]
  (reset! img-cache [])
  (om/update! app (merge app (make-game app size speed))))


(defn tile [app [n idx]]
  (let [is-adj   (adj? app idx)
        is-empty (= n :empty)]
    (dom/div #js {
      :react-key n
      :className (str
        "tile"
        (if is-empty " empty" (when is-adj " adjacent")))
      :style   (get-tile-transform app idx)
      :onClick #(when is-adj (move! app n))}
        (when-not is-empty
          (dom/video #js {
            :src      (:stream app)
            :autoPlay "autoplay"
            :style    (get-bg-transform app n)})))))

(defn grid [{:keys [grid-px] :as app}]
  (om/component
    (apply dom/div #js {:className "grid" :style #js {:width grid-px :height grid-px}}
      (map (partial tile app) (:tiles app)))))

(defn set-grid-size! [app size]
  (when (and (integer? size) (> size 1) (< size 10))
    (new-game! app size (:tick-ms @app))))

(defn set-tick-ms! [app ms]
  (om/update! app [:tick-ms] ms))

(defn get-max-grid-px []
  (min (- js/innerWidth (* capture-size 1.2)) js/innerHeight))

(declare playback-ctx)

(defn paint-canvas! [{:keys [moves grid-size]} tick]
  (take! (@img-cache tick) (fn [img]
    (let [ts (/ capture-size grid-size)]
      (.clearRect playback-ctx 0 0 capture-size capture-size)
      (doseq [[idx pos] (:tiles (moves tick))]
        (if-not (= idx :empty)
          (let [[x1 y1] (get-tile-xy idx grid-size)
                [x2 y2] (get-tile-xy pos grid-size)]
            (.drawImage playback-ctx img (* x1 ts) (* y1 ts) ts ts (* x2 ts) (* y2 ts) ts ts))))
      (.drawImage playback-ctx img 0 capture-size)))))

(defonce app-state (atom {}))

(defn raf-step! [c]
  (js/requestAnimationFrame #(put! c true (partial raf-step! c))))

(defonce raf-chan
  (let [c (chan)]
    (raf-step! c)
    c))

(defn start! []
  (swap! app-state merge (make-game @app-state default-size))
  (defonce tick-loop
    (go-loop [tick 0]
      (let [app        @app-state
            moves      (:moves app)
            move-count (count moves)]
        (swap! app-state assoc :tick tick)
        (paint-canvas! app tick)
        (<! raf-chan)
        (<! (timeout (:tick-ms app)))
        (if (or (zero? move-count) (= tick (dec move-count)))
          (recur 0)
          (recur (inc tick)))))))

(defn on-js-reload [])

(defonce gum (take! (media/get-media) (fn [{:keys [status data]}]
    (if (= status :success)
      (let [video  (.createElement js/document "video")
            canvas (.createElement js/document "canvas")]
        (aset canvas "width" capture-size)
        (aset canvas "height" capture-size)
        (aset video "autoplay" "autoplay")
        (aset video "onplaying" (fn []
          (let [vw (.-videoWidth video)
                vh (.-videoHeight video)]
            (js-delete video "onplaying")
            (swap! app-state merge {
              :stream      data
              :grid-px     (get-max-grid-px)
              :vid-node    video
              :vid-w       vw
              :vid-h       vh
              :vid-ratio   (/ vw vh)
              :vid-offset  (* 100 (/ (max 1 (.abs js/Math (- vw vh))) (max 1 (* vw 2))))
              :canvas-node canvas
              :ctx         (.getContext canvas "2d")})
            (js/setTimeout start! 500))))
        (aset video "src" data))
      (swap! app-state assoc :media-error? true)))))


(om/root
    (fn [{:keys [stream tiles win-state moves tick tick-ms grid-size media-error?] :as app} _]
      (reify
        om/IDidMount
        (did-mount [_]
          (def playback-ctx
            (->
              (.getElementById js/document "playback-canvas")
              (.getContext "2d")))

          (defonce resize-loop
            (let [resize-chan (chan)]
              (.addEventListener js/window "resize" #(put! resize-chan 0))
              (go-loop [open true]
                (when open (<! resize-chan))
                (let [throttle (timeout resize-ms)]
                  (if (= throttle (last (alts! [throttle resize-chan])))
                    (do
                      (swap! app-state assoc :grid-px (get-max-grid-px))
                      (recur true))
                    (recur false)))))))

        om/IRender
        (render [_]
          (let [move-count (count moves)]
            (dom/div nil
              (dom/div #js {:id "sidebar" :style #js {:width capture-size}}
                (dom/h1 nil "cellf")
                (dom/p nil "find yourself.")
                (dom/canvas #js {
                  :id     "playback-canvas"
                  :width  capture-size
                  :height (* capture-size 2)})

                (when stream
                  (dom/div nil
                    (when (= tiles win-state)
                      (dom/h1 nil "You win!"))

                    (dom/p
                      #js {:className "move-count"}
                      (str move-count " move" (when (> move-count 1) "s")))

                    (dom/label nil "grid-size:")
                    (dom/label nil (str grid-size \× grid-size))
                    (dom/input #js {
                      :type     "range"
                      :value    grid-size
                      :min      "2"
                      :max      "9"
                      :step     "1"
                      :onChange #(set-grid-size! app (js/parseInt (.. % -target -value)))})

                    (dom/label nil "playback speed:")
                    (dom/input #js {
                      :type     "range"
                      :value    (- tick-ms)
                      :min      "-1000"
                      :max      "-30"
                      :step     "10"
                      :onChange #(set-tick-ms! app (- (js/parseInt (.. % -target -value))))}))))

              (if stream
                (om/build grid app)
                (dom/div #js {:className "heads-up"} "To play Cellf, allow your camera."))

              (when media-error?
                (dom/div #js {:className "warning"} "Error accessing camera.")))))))
    app-state
    {:target (.getElementById js/document "app")})


