(ns carbon.rx
  (:require-macros [carbon.rx :as rx]))

(defprotocol IReactiveSource
  (get-rank [_])
  (add-sink [_ sink])
  (remove-sink [_ sink])
  (get-sinks [_]))

(defprotocol IReactiveExpression
  (compute [_])
  (computed? [_])
  (gc [_])
  (add-source [_ source])
  (remove-source [_ source]))

(defprotocol IDrop
  (add-drop [_ key f])
  (remove-drop [_ key])
  (notify-drops [_]))

(def ^:dynamic *rx* nil)                                    ; current parent expression
(def ^:dynamic *rank* nil)                                  ; highest rank met during expression compute
(def ^:dynamic *dirty-sinks* nil)                           ; subject to `compute`
(def ^:dynamic *dirty-sources* nil)                         ; subject to `gc`
(def ^:dynamic *provenance* [])

(defn compare-by [keyfn]
  (fn [x y]
    (compare (keyfn x) (keyfn y))))

(defn rank-hash [x]
  [(get-rank x) (hash x)])

(def empty-queue (sorted-set-by (compare-by rank-hash)))

(defn propagate
  "Recursively compute all dirty sinks in the `queue` and return all visited sources to clean."
  [queue]
  (binding [*rx* nil *rank* nil]                            ; try to be foolproof
    (loop [queue queue dirty '()]
      (if-let [x (first queue)]
        (let [queue (disj queue x)]
          (recur (if (= @x (compute x)) queue (->> x get-sinks (into queue)))
                 (conj dirty x)))
        dirty))))

(defn clean
  "Recursively garbage collect all disconnected sources in the `queue`"
  [queue]
  (doseq [source queue]
    (gc source)))

(defn register [source]
  (when *rx*                                                ; *rank* too
    (add-sink source *rx*)
    (add-source *rx* source)
    (vswap! *rank* max (get-rank source))))

(defn dosync* [f]
  (let [sinks (or *dirty-sinks* (volatile! empty-queue))
        sources (or *dirty-sources* (volatile! empty-queue))
        result (binding [*dirty-sinks* sinks
                         *dirty-sources* sources]
                 (f))]
    ;; top-level dosync*
    (when-not *dirty-sinks*
      (binding [*dirty-sources* sources]
        (vswap! *dirty-sources* into (propagate @sinks))))
    ;; top-level dosync*
    (when-not *dirty-sources*
      (clean (reverse @sources)))
    result))

(defn safe-realized? [x]
  (if (implements? IPending x)
    (realized? x)
    true))

(defn fully-realized?
  [form]
  (if (seqable? form)
    (and (safe-realized? form) (every? fully-realized? form))
    (safe-realized? form)))

(deftype ReactiveExpression [getter setter metadata validator ^:mutable drop
                             ^:mutable state ^:mutable watches
                             ^:mutable rank ^:mutable sources ^:mutable sinks]

  Object
  (equiv [this other] (-equiv this other))

  IEquiv
  (-equiv [o other] (identical? o other))

  IDeref
  (-deref [this]
    (when-not (computed? this) (compute this))
    (register this)
    state)

  IReactiveSource
  (get-rank [_] rank)
  (add-sink [_ sink] (set! sinks (conj sinks sink)))
  (remove-sink [_ sink] (set! sinks (disj sinks sink)))
  (get-sinks [_] sinks)

  IReactiveExpression
  (computed? [this]
    (not= state ::thunk))
  (compute [this]
    (doseq [source sources]
      (remove-sink source this))
    (set! sources #{})
    (when ^boolean js/goog.DEBUG
      (when (some #(identical? this %) *provenance*)
        (throw (js/Error. (str "carbon.rx: detected a cycle in computation graph!\n"
                               (pr-str (map meta *provenance*)))))))
    (let [old-value state
          r (volatile! 0)
          new-value (binding [*rx* this
                              *rank* r
                              *provenance* (conj *provenance* this)]
                      (let [x (getter)]
                        (when ^boolean js/goog.DEBUG
                          (when-not (fully-realized? x)
                            (js/console.warn
                              "carbon.rx: this branch returns not fully realized value, make sure that no dependencies are derefed inside lazy part:\n"
                              (map meta *provenance*)
                              "\n" x)))
                        x))]
      (set! rank (inc @r))
      (when (not= old-value new-value)
        (set! state new-value)
        (-notify-watches this old-value new-value))
      new-value))
  (gc [this]
    (if *dirty-sources*
      (vswap! *dirty-sources* conj this)
      (when (and (empty? sinks) (empty? watches))
        (doseq [source sources]
          (remove-sink source this)
          (when (satisfies? IReactiveExpression source)
            (gc source)))
        (set! sources #{})
        (set! state ::thunk)
        (notify-drops this))))
  (add-source [_ source]
    (set! sources (conj sources source)))
  (remove-source [_ source]
    (set! sources (disj sources source)))

  IDrop
  (add-drop [this key f]
    (set! drop (assoc drop key f))
    this)
  (remove-drop [this key]
    (set! drop (dissoc drop key))
    this)
  (notify-drops [this]
    (doseq [[key f] drop]
      (f key this)))

  IMeta
  (-meta [_] metadata)

  IWatchable
  (-notify-watches [this oldval newval]
    (doseq [[key f] watches]
      (f key this oldval newval)))
  (-add-watch [this key f]
    (when-not (computed? this) (compute this))
    (set! watches (assoc watches key f))
    this)
  (-remove-watch [this key]
    (set! watches (dissoc watches key))
    (gc this)
    this)

  IHash
  (-hash [this] (goog/getUid this))

  IPrintWithWriter
  (-pr-writer [_ writer opts]
    (-write writer "#<RLens: ")
    (pr-writer state writer opts)
    (-write writer ">"))

  IReset
  (-reset! [_ new-value]
    (assert setter "Can't reset lens w/o setter")
    (when-not (nil? validator)
      (assert (validator new-value) "Validator rejected reference state"))
    (dosync* #(setter new-value))
    new-value)

  ISwap
  (-swap! [this f]
    (when-not (computed? this) (compute this))
    (reset! this (f state)))
  (-swap! [this f x]
    (when-not (computed? this) (compute this))
    (reset! this (f state x)))
  (-swap! [this f x y]
    (when-not (computed? this) (compute this))
    (reset! this (f state x y)))
  (-swap! [this f x y xs]
    (when-not (computed? this) (compute this))
    (reset! this (apply f state x y xs))))

(defn watch [_ source o n]
  (when (not= o n)
    (if *dirty-sinks*
      (vswap! *dirty-sinks* into (get-sinks source))
      (->> source get-sinks (into empty-queue) propagate clean))))

(defn cell* [x & m]
  (let [sinks (volatile! #{})]
    (specify! (apply atom x m)

      IReactiveSource
      (get-rank [_] 0)
      (add-sink [_ sink] (vswap! sinks conj sink))
      (remove-sink [_ sink] (vswap! sinks disj sink))
      (get-sinks [_] @sinks)

      IDeref
      (-deref [this]
        (register this)
        (add-watch this ::rx watch)
        (.-state this)))))

(defn rx*
  ([getter] (rx* getter nil nil nil nil))
  ([getter setter] (rx* getter setter nil nil nil))
  ([getter setter meta] (rx* getter setter meta nil nil))
  ([getter setter meta validator] (rx* getter setter meta validator nil))
  ([getter setter meta validator drop]
   (ReactiveExpression. getter setter meta validator drop ::thunk {} 0 #{} #{})))

(def cursor-cache (volatile! {}))

(defn cache-dissoc [cache parent path]
  (let [cache (update cache parent dissoc path)]
    (if (empty? (get cache parent))
      (dissoc cache parent)
      cache)))

(def normalize-cursor-path vec)

(defn cursor [parent path]
  (rx/no-rx
    (let [path (normalize-cursor-path path)]
      (or (get-in @cursor-cache [parent path])
          (let [x (rx/lens (get-in @parent path) (partial swap! parent assoc-in path))]
            (add-drop x ::cursor #(vswap! cursor-cache cache-dissoc parent path))
            (vswap! cursor-cache assoc-in [parent path] x)
            x)))))