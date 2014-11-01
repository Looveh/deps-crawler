(ns deps-crawler.data
  (:require [deps-crawler.storage :refer :all]))


(defn count-all-versions [lib]
  (reduce + (vals (val lib))))

(defn add-lib-count [lib-count repo]
  (reduce #(update-in %1
                      [(:name %2) (:version %2)]
                      (fnil inc 0))
          lib-count
          (:dependencies repo)))

(defn deps-count []
  (reduce add-lib-count {} (get-all-repos)))

(defn deps-count-all-versions []
  (map #(hash-map (key %)
                  (count-all-versions %))
       (deps-count)))
