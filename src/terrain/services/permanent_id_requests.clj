(ns terrain.services.permanent-id-requests
  (:use [kameleon.uuids :only [uuidify]]
        [slingshot.slingshot :only [try+ throw+]]
        [terrain.auth.user-attributes :only [current-user]]
        [terrain.services.filesystem.metadata :only [metadata-get]])
  (:require [cheshire.core :as json]
            [clj-time.core :as time]
            [clojure.tools.logging :as log]
            [clojure-commons.file-utils :as ft]
            [terrain.clients.data-info :as data-info]
            [terrain.clients.data-info.raw :as data-info-client]
            [terrain.clients.ezid :as ezid]
            [terrain.clients.metadata.raw :as metadata]
            [terrain.clients.notifications :as notifications]
            [terrain.util.config :as config]
            [terrain.util.email :as email]
            [terrain.util.service :as service]))

;; Status Codes.
(def ^:private status-code-completion "Completion")
(def ^:private status-code-failed "Failed")

(defn- parse-service-json
  [response]
  (-> response :body service/decode-json))

(defn- validate-ezid-metadata
  [ezid-metadata]
  (when (empty? ezid-metadata)
    (throw+ {:type :clojure-commons.exception/bad-request
             :error "No metadata found for Permanent ID Request."}))
  (let [identifier (get ezid-metadata (config/permanent-id-identifier-attr))]
    (when-not (empty? identifier)
    (throw+ {:type :clojure-commons.exception/bad-request
             :error (str "The " (config/permanent-id-identifier-attr)
                         " metadata attribue already contains a value: "
                         identifier)})))
  ezid-metadata)

(defn- validate-request-target-type
  [{target-type :type :as folder}]
  (let [target-type (metadata/resolve-data-type target-type)]
    (when-not (= target-type "folder")
      (throw+ {:type :clojure-commons.exception/bad-request-field
               :error "The given data ID does not belong to a folder."
               :file folder}))
    target-type))

(defn- validate-owner
  [user {:keys [id path] :as data-item}]
  (when-not (data-info/owns? user path)
    (throw+ {:type :clojure-commons.exception/not-owner
             :error "User does not own given folder."
             :user user
             :folder-id id}))
  data-item)

(defn- validate-staging-dest-exists
  [{:keys [paths]} staging-dest]
  (let [path-exists? (get paths (keyword staging-dest))]
    (when path-exists?
    (throw+ {:type :clojure-commons.exception/exists
             :error "A folder with this name has already been submitted for a Permanent ID request."
             :path staging-dest}))))

(defn- validate-publish-dest-exists
  [{:keys [paths]} publish-dest]
  (let [path-exists? (get paths (keyword publish-dest))]
    (when path-exists?
      (throw+ {:type :clojure-commons.exception/exists
               :error "A folder with this name has already been published."
               :path publish-dest}))))

(defn- validate-publish-dest
  [{:keys [path] :as data-item}]
  (let [publish-dest (ft/path-join (config/permanent-id-publish-dir) (ft/basename path))
        paths-exist (parse-service-json (data-info/check-existence {:user (config/permanent-id-curators-group)}
                                                                   {:paths [publish-dest]}))]
    (validate-publish-dest-exists paths-exist publish-dest))
  data-item)

(defn- validate-data-item
  [user {:keys [path] :as data-item}]
  (validate-owner user data-item)
  (let [staging-dest (ft/path-join (config/permanent-id-staging-dir) (ft/basename path))
        publish-dest (ft/path-join (config/permanent-id-publish-dir) (ft/basename path))
        paths-exist  (parse-service-json (data-info/check-existence {:user (config/permanent-id-curators-group)}
                                                                    {:paths [staging-dest
                                                                             publish-dest]}))]
    (validate-staging-dest-exists paths-exist staging-dest)
    (validate-publish-dest-exists paths-exist publish-dest))
  data-item)

(defn- submit-permanent-id-request
  "Submits the request to the metadata create-permanent-id-request endpoint."
  [type folder-id target-type path]
  (-> {:type type
       :target_id folder-id
       :target_type target-type
       :original_path path}
      json/encode
      metadata/create-permanent-id-request
      parse-service-json))

(defn- create-publish-dir
  "Creates the Permanent ID Requests publish directory, if it doesn't already exist."
  []
  (let [publish-path (config/permanent-id-publish-dir)
        curators     (config/permanent-id-curators-group)]
    (when-not (data-info/path-exists? (config/irods-user) publish-path)
      (log/warn "creating" publish-path "for:" curators)
      (data-info-client/create-dirs (config/irods-user) [publish-path])
      (data-info/share (config/irods-user) [curators] [publish-path] "own"))))

(defn- create-staging-dir
  "Creates the Permanent ID Requests staging directory, if it doesn't already exist."
  []
  (let [staging-path (config/permanent-id-staging-dir)
        curators     (config/permanent-id-curators-group)]
    (when-not (data-info/path-exists? (config/irods-user) staging-path)
      (log/warn "creating" staging-path "for:" curators)
      (data-info-client/create-dirs (config/irods-user) [staging-path])
      (data-info/share (config/irods-user) [curators] [staging-path] "own"))))

(defn- stage-data-item
  [user {:keys [id path] :as data-item}]
  (let [staged-path (ft/path-join (config/permanent-id-staging-dir) (ft/basename path))
        curators    (config/permanent-id-curators-group)]
    (data-info-client/move-single (config/irods-user) id (config/permanent-id-staging-dir))
    (data-info/share (config/irods-user) [user] [staged-path] "write")
    (data-info/share (config/irods-user) [curators] [staged-path] "own")
    staged-path))

(defn- publish-data-item
  [user {:keys [id path] :as data-item}]
  (let [publish-path (ft/path-join (config/permanent-id-publish-dir) (ft/basename path))
        curators     (config/permanent-id-curators-group)]
    (data-info-client/move-single curators id (config/permanent-id-publish-dir))
    (data-info/share (config/irods-user) [curators] [publish-path] "own")
    publish-path))

(defn- publish-metadata
  [{:keys [id type]} template-id publish-avus]
  (let [data-type (metadata/resolve-data-type type)]
    (metadata/add-metadata-template-avus id data-type template-id publish-avus)))

(defn- send-notification
  [user subject request-id]
  (log/debug "sending permanent_id_request notification to" user ":" subject)
  (try
    (notifications/send-notification
      {:type "permanent_id_request"
       :user user
       :subject subject
       :payload {:uuid request-id}})
    (catch Exception e
      (log/error e
        "Could not send permanent_id_request (" request-id ") notification to" user ":" subject))))

(defn- send-update-notification
  [{:keys [id type folder history requested_by] :or {folder {:path "unknown"}}}]
  (send-notification
    requested_by
    (str type " Request for " (ft/basename (:path folder)) " Status Changed to " (:status (last history)))
    id))

(defn- request-type->shoulder
  [type]
  (case type
    "ARK" (config/ezid-shoulders-ark)
    "DOI" (config/ezid-shoulders-doi)
    (throw+ {:type :clojure-commons.exception/bad-request-field
             :error "No EZID shoulder defined for this Permanent ID Request type."
             :request-type type})))

(defn- parse-valid-ezid-metadata
  [irods-avus metadata]
  (let [metadata (mapcat :avus (:templates metadata))
        format-avus #(vector (:attr %) (:value %))
        ezid-metadata (into {} (concat (map format-avus irods-avus) (map format-avus metadata)))
        ezid-metadata (assoc ezid-metadata (config/permanent-id-date-attr) (str (time/year (time/now))))]
    (validate-ezid-metadata ezid-metadata)
    ezid-metadata))

(defn- get-validated-data-item
  "Gets data-info stat for the given ID and checks if the data item is valid for a Permanent ID request."
  [user data-id]
  (let [{:keys [irods-avus metadata]} (metadata-get user data-id)]
    (parse-valid-ezid-metadata irods-avus metadata))
  (->> data-id
       (data-info/stat-by-uuid user)
       (validate-data-item user)))

(defn- format-alt-id-avus
  "Formats metadata service AVUs from the alt-identifiers-map, only if existing-alt-id is empty."
  [existing-alt-id alt-identifiers-map]
  (when (empty? existing-alt-id)
    (mapcat (fn [[k v]] [{:attr (config/permanent-id-alt-identifier-attr)
                          :value v
                          :unit ""}
                         {:attr (config/permanent-id-alt-identifier-type-attr)
                          :value (name k)
                          :unit ""}])
            alt-identifiers-map)))

(defn- format-publish-avus
  "Formats AVUs containing completed request information for saving with the metadata service."
  [metadata ezid-metadata identifier alt-identifiers]
  (let [publish-date    (get ezid-metadata (config/permanent-id-date-attr))
        existing-alt-id (get ezid-metadata (config/permanent-id-alt-identifier-attr))
        alt-id-avus     (format-alt-id-avus existing-alt-id alt-identifiers)
        remove-attrs    (set (concat [(config/permanent-id-identifier-attr)
                                      (config/permanent-id-date-attr)]
                                     (when-not (empty? alt-id-avus)
                                       [(config/permanent-id-alt-identifier-attr)
                                        (config/permanent-id-alt-identifier-type-attr)])))
        template        (-> metadata :templates first)]
    (update template :avus
      (comp (partial concat
              alt-id-avus
              [{:attr (config/permanent-id-identifier-attr)
                :value identifier
                :unit ""}
               {:attr (config/permanent-id-date-attr)
                :value publish-date
                :unit ""}])
            (partial remove #(contains? remove-attrs (:attr %)))))))

(defn- format-perm-id-req-response
  [user {:keys [target_id] :as response}]
  (-> response
      (dissoc :target_id :target_type :original_path)
      (assoc :folder (data-info/stat-by-uuid user (uuidify target_id)))))

(defn- format-perm-id-req-list
  [requests]
  (map
    (partial format-perm-id-req-response (:shortUsername current-user))
    requests))

(defn list-permanent-id-requests
  [params]
  (-> (metadata/list-permanent-id-requests params)
      parse-service-json
      (update-in [:requests] format-perm-id-req-list)))

(defn create-permanent-id-request
  [params body]
  (create-staging-dir)
  (let [{type :type folder-id :folder} (service/decode-json body)
        folder-id                      (uuidify folder-id)
        user                           (:shortUsername current-user)
        {:keys [path] :as folder}      (get-validated-data-item user folder-id)
        target-type                    (validate-request-target-type folder)
        {request-id :id :as response}  (submit-permanent-id-request type folder-id target-type path)
        staged-path                    (stage-data-item user folder)]
    (send-notification user (str type " Request Submitted for " (ft/basename path)) request-id)
    (email/send-permanent-id-request-new type staged-path current-user)
    (email/send-permanent-id-request-submitted type staged-path current-user)
    (format-perm-id-req-response user response)))

(defn list-permanent-id-request-status-codes
  [params]
  (metadata/list-permanent-id-request-status-codes))

(defn list-permanent-id-request-types
  [params]
  (metadata/list-permanent-id-request-types))

(defn get-permanent-id-request
  [request-id params]
  (->> (metadata/get-permanent-id-request request-id)
       parse-service-json
       (format-perm-id-req-response (:shortUsername current-user))))

(defn admin-list-permanent-id-requests
  [params]
  (-> (metadata/admin-list-permanent-id-requests params)
      parse-service-json
      (update-in [:requests] format-perm-id-req-list)))

(defn admin-get-permanent-id-request
  [request-id params]
  (->> (metadata/admin-get-permanent-id-request request-id)
       parse-service-json
       (format-perm-id-req-response (:shortUsername current-user))))

(defn update-permanent-id-request
  [request-id params body]
  (let [response (->> (metadata/update-permanent-id-request request-id body)
                      parse-service-json
                      (format-perm-id-req-response (:shortUsername current-user)))]
    (send-update-notification response)
    response))

(defn- complete-permanent-id-request
  [user {request-id :id :keys [folder type] :as request}]
  (try+
    (let [shoulder                      (request-type->shoulder type)
          folder                        (validate-publish-dest folder)
          folder-id                     (uuidify (:id folder))
          {:keys [irods-avus metadata]} (metadata-get user folder-id)
          template-id                   (-> metadata :templates first :template_id)
          ezid-metadata                 (parse-valid-ezid-metadata irods-avus metadata)
          ezid-response                 (ezid/mint-id shoulder ezid-metadata)
          identifier                    (get ezid-response (keyword type))
          alt-identifiers               (dissoc ezid-response (keyword type))
          publish-avus                  (format-publish-avus metadata ezid-metadata identifier alt-identifiers)
          publish-path                  (publish-data-item user folder)]
      (email/send-permanent-id-request-complete type
                                                publish-path
                                                (json/encode ezid-response {:pretty true}))
      (publish-metadata folder template-id publish-avus)
      identifier)
    (catch Object e
      (log/error e)
      (update-permanent-id-request request-id nil (json/encode {:status status-code-failed}))
      (throw+ e))))

(defn create-permanent-id
  [request-id params body]
  (create-publish-dir)
  (let [identifier (complete-permanent-id-request (:shortUsername current-user)
                                                  (admin-get-permanent-id-request request-id nil))]
    (update-permanent-id-request request-id nil (json/encode {:status       status-code-completion
                                                              :permanent_id identifier}))))
