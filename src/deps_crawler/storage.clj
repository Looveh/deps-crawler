(ns deps-crawler.storage
  (:require [monger.core :as mg]
            [monger.collection :as mc]))

(def ^{:private true}
  db-name "deps-crawler")

(def ^{:private true}
  coll "deps")

(defn- get-db []
  (mg/get-db (mg/connect) db-name))

(defn store-repo [repo]
  (mc/insert-and-return (get-db) coll repo))

(defn get-all-repos []
  (mc/find-maps (get-db) "deps"))
