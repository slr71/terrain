(ns terrain.clients.apps
  (:require [cheshire.core :as cheshire]
            [terrain.clients.apps.raw :as raw]
            [terrain.util.service :as service]))

(defn admin-list-tool-requests
  [params]
  (->> (raw/admin-list-tool-requests params)
       (:body)
       (service/decode-json)))

(defn admin-add-tools
  [body]
  (raw/admin-add-tools (cheshire/encode body)))

(defn get-authenticated-user
  []
  (-> (raw/get-authenticated-user)
      (:body)
      (service/decode-json)))
