(ns fraud-detection.core
  (:require [clojure.java.io :as io]
           [clojure.string :as string]
           [clojure.data.csv :as csv]
           [clojure.core.matrix :as mat]
           [clojure.core.matrix.stats :as matstats]
           [cortex.nn.layers :as layers]
           [cortex.nn.network :as network]
           [cortex.nn.execute :as execute]
           [cortex.optimize.adadelta :as adadelta]
           [cortex.optimize.adam :as adam]
           [cortex.metrics :as metrics]
           [cortex.util :as util]
           [cortex.experiment.util :as experiment-util]
           [cortex.experiment.train :as experiment-train]))


(def orig-data-file "resources/creditcard.csv")
(def log-file "training.log")
(def network-file "trained-network.nippy")

(def params
  {:test-ds-size      50000 ;; total = 284807, test-ds ~= 17.5%
   :optimizer         (adam/adam)   ;; alternately, (adadelta/adadelta)
   :batch-size        100
   :epoch-count       30
   :epoch-size        200000})


(defn vec->label
  "Map vector of probabilities to original class (vec->label [:a :b :c :d] [0.1 0.2 0.6 0.1] => :c)

   If class-threshold (vector of [class-name, threshold] provided, if the label-vec's class is the same as the provided class-name,
      the label-vec's max-value has to be greater than the threshold. Else, returns next largest class."
  [class-names label-vec & [class-threshold]]
  (let [max-idx (util/max-index label-vec)
        max-class (get class-names max-idx)]
    (if class-threshold
      (let [[class threshold] class-threshold
            max-val (apply max label-vec)]
        (if (and (= class max-class) (< max-val threshold))
          (let [next-max (util/max-index (assoc label-vec max-idx 0))]
            (nth class-names next-max))
          max-class))
      max-class)))


;; "Read input csv and create a vector of maps {:data [...] :label [..]}, where each map represents one instance"
(defonce create-dataset
  (memoize
    (fn []
      (let [credit-data (with-open [infile (io/reader orig-data-file)]
                          (rest (doall (csv/read-csv infile))))
            data (mapv #(mapv read-string %) (map #(drop 1 %) (map drop-last credit-data))) ; drop label and time
            labels (mapv #(util/idx->one-hot (read-string %) 2) (map last credit-data))
            dataset (mapv (fn [d l] {:data d :label l}) data labels)]
        dataset))))


;; "Split dataset into train/test sets with an equal proportion of positive examples in each
;; (as a way of reducing testing variation in a highly imbalanced dataset)"
(defonce get-train-test-dataset
  (memoize
    (fn []
      (let [dataset (shuffle (create-dataset))
            {positives true negatives false} (group-by #(= (:label %) [0.0 1.0]) dataset)
            test-pos-amount (int (* (count positives) (/ (:test-ds-size params) (count dataset)))) ;86
            test-neg-amount (- (:test-ds-size params) test-pos-amount)
            test-set (into [] (concat (take test-pos-amount positives) (take test-neg-amount negatives)))
            train-set (into [] (concat (drop test-pos-amount positives) (drop test-neg-amount negatives)))]
        [train-set test-set]))))


(defn calc-min-dist
  "Calculate min distance between feature vectors for positive and negative samples

  Result: Elapsed time: 1696027.647549 msecs  │  3.598395571684021"
  []
  (let [dataset (shuffle (create-dataset))
        {positives true negatives false} (group-by #(= (:label %) [0.0 1.0]) dataset)
        pos-data (mat/matrix (map #(:data %) positives))
        neg-data (mat/matrix (map #(:data %) negatives))
        dists (for [p (mat/rows pos-data) n (mat/rows neg-data)] (mat/distance p n))]
    (time (apply min dists))))

;; "Get variance for each feature in positive dataset and scale it by the given factor"
(defonce get-scaled-variances
  (memoize
    (fn []
      (let [{positives true negatives false} (group-by #(= (:label %) [0.0 1.0]) (create-dataset))
            pos-data (mat/matrix (map #(:data %) positives))
            variances (mat/matrix (map #(matstats/variance %) (mat/columns pos-data)))
            scaled-vars (mat/mul (/ 10000 (mat/length variances)) variances)]
        scaled-vars))))


(defn add-rand-variance
  "Take vector v and add random vector based on the variance of each feature in the positive dataset"
  [v scaled-vars]
  (let [randv (map #(- (* 2 (rand %)) %) scaled-vars)]
    (mapv + v randv)))


(defn augment-train-ds
  "Takes train dataset and augments positive examples to reach 50/50 balance"
  [orig-train]
  (let [{train-pos true train-neg false} (group-by #(= (:label %) [0.0 1.0]) orig-train)
        pos-data (map #(:data %) train-pos)
        num-augments (- (count train-neg) (count train-pos))
        augments-per-sample (int (/ num-augments (count train-pos)))

        augmented-data (apply concat (repeatedly augments-per-sample
                                                #(mapv (fn [p] (add-rand-variance p (get-scaled-variances))) pos-data)))
        augmented-ds (mapv (fn [d] {:data d :label [0.0 1.0]}) augmented-data)]
    (shuffle (concat orig-train augmented-ds))))


(def network-description
  [(layers/input (count (:data (first (create-dataset)))) 1 1 :id :data) ;width, height, channels, args
  (layers/linear->relu 20) ; num-output & args
  (layers/dropout 0.85)
  (layers/linear->relu 10)
  (layers/linear 2)
  (layers/softmax :id :label)])


(defn log
     "Print data and save to log file"
     [data]
     (spit log-file (str data "\n") :append true)
     (println data))


;; false positive ok
;; false negative bad (fraud happens)
;; => emphasize recall
(defn f-beta
  "F-beta score, default uses F1"
  ([precision recall] (f-beta precision recall 1))
  ([precision recall beta]
    (let [beta-squared (* beta beta)]
      (* (+ 1 beta-squared)
         (try                         ;; catch divide by 0 errors
           (/ (* precision recall)
              (+ (* beta-squared precision) recall))
         (catch ArithmeticException e
           0))))))



(defn f1-test-fn
  "Test function that takes in two map arguments, global info and local epoch info.
   Compares F1 score of current network to that of the previous network, and returns map:
    {:best-network? boolean
     :network (assoc new-network :evaluation-score-to-compare)}"
  [;; global arguments
   {:keys [batch-size context]}
   ;per-epoch arguments
   {:keys [new-network old-network test-ds]} ]
  (let [batch-size (long batch-size)
        test-results (execute/run new-network test-ds
                                 :batch-size batch-size
                                 :loss-outputs? true
                                 :context context)
         ;;; test metrics
         test-actual (mapv #(vec->label [0.0 1.0] %) (map :label test-ds))
         test-pred (mapv #(vec->label [0.0 1.0] % [1 0.9]) (map :label test-results))

         precision (metrics/precision test-actual test-pred)
         recall (metrics/recall test-actual test-pred)
         f-beta (f-beta precision recall)

         ;; if current f-beta higher than the old network's, current is best network
         best-network? (or (nil? (get old-network :cv-score))
                          (> f-beta (get old-network :cv-score)))
         updated-network (assoc new-network :cv-score f-beta)
         epoch (get new-network :epoch-count)]

    (experiment-train/save-network updated-network network-file)
    (log (str "Epoch: " epoch "\n"
              "Precision: " precision  "\n"
              "Recall: " recall "\n"
              "F1: " f-beta "\n\n"))
    {:best-network? best-network?
     :network updated-network}))


(defn train
  "Trains network for :epoch-count number of epochs"
  []
  (let [network (network/linear-network network-description)
        [train-orig test-ds] (get-train-test-dataset)
        augmented-train (shuffle (augment-train-ds train-orig))
        train-ds (experiment-util/infinite-class-balanced-dataset augmented-train
                                            :class-key :label
                                            :epoch-size (:epoch-size params))]
    (experiment-train/train-n network train-ds test-ds
                                      :batch-size (:batch-size params)
                                      :epoch-count (:epoch-count params)
                                      :optimizer (:optimizer params)
                                      :test-fn f1-test-fn)))



(def high-score* (atom {:score 0}))

(defn train-low-level
  "Example low-level training function. Here, the compute context (CPU/GPU) is established, and
   a reduce function continuously trains and updates the network for the specified number of epochs"
  []
  (log "________________________________________________________")
  (log params)
  (log (str network-description))
  (let [context (execute/compute-context)] ; determines context for gpu/cpu training
    (execute/with-compute-context
      context
      (let [[train-orig test-ds] (get-train-test-dataset)
            train-ds (take (:epoch-size params) (shuffle (augment-train-ds train-orig)))
            network (network/linear-network network-description)]
            (reduce (fn [[network optimizer] epoch]
                        (let [{:keys [network optimizer]} (execute/train network train-ds
                                                                        :context context
                                                                        :batch-size (:batch-size params)
                                                                        :optimizer optimizer)
                              test-results  (execute/run network test-ds :context context
                                                                         :batch-size (:batch-size params))
                              ;;; test metrics
                              test-actual (mapv #(vec->label [0.0 1.0] %) (map :label test-ds))
                              test-pred (mapv #(vec->label [0.0 1.0] % [1 0.95]) (map :label test-results))

                              test-precision (metrics/precision test-actual test-pred)
                              test-recall (metrics/recall test-actual test-pred)
                              test-f-beta (f-beta test-precision test-recall)

                              ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

                              train-results (execute/run network (take (:test-ds-size params) train-ds) :context context
                                                                                                 :batch-size (:batch-size params))
                              ;;; train metrics
                              train-actual (mapv #(vec->label [0.0 1.0] %) (map :label (take (count test-ds) train-ds)))
                              train-pred (mapv #(vec->label [0.0 1.0] %) (map :label (take (count test-ds) train-results)))

                              train-precision (metrics/precision train-actual train-pred)
                              train-recall (metrics/recall train-actual train-pred)
                              train-f-beta (f-beta train-precision train-recall)

                              ]

                            (log (str "Epoch: " (inc epoch) "\n"
                                      "Test precision: " test-precision  "      | Train precision: " train-precision"\n"
                                      "Test recall: " test-recall "             | Train recall: " train-recall "\n"
                                      "Test F1: " test-f-beta "                 | Train F1: " train-f-beta "\n\n"))

                            (when (> test-f-beta (:score @high-score*))
                                  (reset! high-score* {:score test-f-beta})
                                  (experiment-train/save-network network network-file))
                            [network optimizer]))
                [network (:optimizer params)]
                (range (:epoch-count params)))
            (println "Done.")
            (log (str "Best score: " (:score @high-score*)))))))
