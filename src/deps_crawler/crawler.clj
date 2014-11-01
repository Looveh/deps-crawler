(ns deps-crawler.crawler
  (:require [clojure.data.json :as json]
            [clojure.java.io :refer [writer]]
            [clojure.core.async :refer [<! >! go go-loop chan onto-chan close!]]
            [clj-http.client]
            [deps-crawler.storage :refer :all]))

(defn github-api-query
  ([] (github-api-query 0 0 95))
  ([page min-size max-size] (str "https://api.github.com/search/repositories?q=size:" min-size ".." max-size "+language:clojure&page=" page "&per_page=100")))

(defn last-page [min-size max-size]
  (try
    (let [headers (:headers (clj-http.client/get (github-api-query 1 min-size max-size)))]
      (Integer. (re-find #"\d+" (re-find #"page=\d+" (second (clojure.string/split (get headers "Link") #", "))))))
    (catch Exception e nil)))

(defn create-query-creator []
  github-api-query)

(defn next-max-size [max-size]
  (loop [min-size max-size max-size (inc max-size)]
    (if (not (nil? (last-page min-size max-size)))
      max-size
      (recur min-size (inc max-size)))))

(defn generate-queries [out]
  (go-loop [page 1 min-size 0 max-size 95 max-page (last-page 0 95)]
           (let [page (atom page)
                 min-size (atom min-size)
                 max-size (atom max-size)
                 max-page (atom max-page)]
             (if (<= @page @max-page)
               (do
                 (>! out (github-api-query @page @min-size @max-size))
                 (swap! page inc))
               (do
                 (reset! page 1)
                 (reset! min-size @max-size)
                 (reset! max-size (next-max-size @max-size))
                 (reset! max-page (last-page @min-size @max-size))))
             (Thread/sleep 20000)
             (recur @page @min-size @max-size @max-page)))
  out)


(defn _create-query-creator []
  (let [page (atom 1)
        min-size (atom 0)
        max-size (atom 95)
        max-page (atom (last-page @page @min-size @max-size))]
    (fn []
      (let [query (github-api-query @page @min-size @max-size)]
        (println @page @min-size @max-size @max-page)
        (swap! page inc)
        (when (< @max-page @page)
          (reset! max-page nil)
          (while (nil? @max-page)
            (reset! page 1)
            (reset! min-size @max-size)
            (swap! max-size inc)
            (reset! max-page (last-page @page @min-size @max-size))))
        query))))

(defn repo-name->project-file-url [repo-name]
  (str "https://raw.githubusercontent.com/" repo-name "/master/project.clj"))

(defn fetch-git-metadata [query]
  (json/read-str (slurp query) :key-fn keyword))

(defn parse-git-metadata [raw-metadata]
  (map #(hash-map :name (:full_name %)
                  :last-updated (:updated_at %)
                  :size (:size %))
       (:items raw-metadata)))

(defn fetch-project-def [repo-name]
  (slurp (repo-name->project-file-url repo-name)))

(defn filter-deps-from-raw-project-def [raw-project-def]
  (try
    (let [project-params (apply hash-map (drop 3 (read-string raw-project-def)))]
      (map #(hash-map :name (str (first %))
                      :version (second %))
           (:dependencies project-params)))
    (catch Exception e '())))

(defn collect-repo-metadata [in out]
  (go-loop [query (<! in)]
           (when-not (nil? query)
             (try
               (onto-chan out
                          (->> query
                               fetch-git-metadata
                               parse-git-metadata)
                          false)
               (catch Exception e))
             (recur (<! in))))
  out)

(defn collect-repo-deps [in out]
  (go-loop [repo (<! in)]
           (when-not (nil? repo)
             (try
               (->> (:name repo)
                    fetch-project-def
                    filter-deps-from-raw-project-def
                    (assoc repo :dependencies)
                    (>! out))
               (catch Exception e))
             (recur (<! in))))
  out)

(defn store-repo-deps [in]
    (go-loop [repo (<! in)]
             (when-not (nil? repo)
               (store-repo repo)
               (recur (<! in)))))

(defn start []
  (-> (generate-queries (chan 200))
      (collect-repo-metadata (chan 200))
      (collect-repo-deps (chan 200))
      (store-repo-deps)))
