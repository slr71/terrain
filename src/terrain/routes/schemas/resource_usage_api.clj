(ns terrain.routes.schemas.resource-usage-api
  (:require [common-swagger-api.schema :refer [describe]]
            [schema.core :refer [defschema optional-key maybe]])
  (:import [java.util UUID]))

(def CurrentTotalSummary "Get the current CPU hours total for the user")
(def CurrentTotalDescription "Uses the current date to select a CPU hours total record for the logged-in user")
(def AllTotalsSummary "Get all of the CPU hours totals for the user")
(def AllTotalsDescription "Returns all of the CPU hours total records from the database regardless of the date range")
(def AddHoursSummary "Asynchronously adds hours to the current CPU hours total")
(def AddHoursDescription "Asynchronously adds the hours passed to the current total CPU hours for the user")
(def SubtractHoursSummary "Asynchronously subtracts hours from the current CPU hours total")
(def SubtractHoursDescription "Asynchronously subtracts the hours passed in from the current total CPU hours for the user")
(def ResetHoursSummary "Asynchronously resets the current CPU hours total")
(def ResetHoursDescription "Asynchronously resets the logged-in user's current CPU hours total to the value passed in")
(def ListWorkersSummary "List registered workers")
(def ListWorkersDescription "Lists all registered workers, which are services in the DE backend")
(def GetWorkerSummary "Get worker info")
(def GetWorkerDescription "Gets information about a single worker by their UUID")
(def UpdateWorkerSummary "Update worker")
(def UpdateWorkerDescription "Updates a worker's state. Be very careful doing this. Only use this if things are broken")
(def DeleteWorkerSummary "Delete worker")
(def DeleteWorkerDescription "Deletes a worker from the system. Be very careful doing this. Only use this if things are broken")
(def AllUsersCurrentCPUTotalSummary "Lists the current CPU hours totals for all users")
(def AllUsersCurrentCPUTotalDescription "Lists the current CPU hours totals for all users in the system")
(def AllUsersAllCPUTotalsSummary "List all CPU hours totals for all users")
(def AllUsersAllCPUTotalsDescription "Lists all of the CPU hours totals for every single user in the system")
(def ListEventsSummary "List all events")
(def ListEventsDescription "List all events in the system for all users")
(def ListUserEventsSummary "List events for a user")
(def ListUserEventsDescription "List all fo the events in the system for a single user")
(def GetEventSummary "Get info about a single event")
(def GetEventDescription "Returns information about a single event in the system")
(def UpdateEventSummary "Update an event")
(def UpdateEventDescription "Updates a single event in the system")
(def DeleteEventSummary "Deletes an event")
(def DeleteEventDescription "Deletes a single event in the system")

(def WorkerID (describe UUID "The UUID assigned to a worker"))
(def EventID (describe UUID "The UUID assigned to an event"))
(def CPUHoursTotalID (describe UUID "The assigned to the record containing the CPU hours total"))
(def HoursNumber (describe Long "The number of CPU hours"))
(def Username (describe String "A username in the system"))

(defschema Worker
  {:id                      WorkerID
   :name                    (describe String "The name of the worker")
   :added_on                (describe String "When the worker was added to the system. Automatically set by the system")
   :active                  (describe Boolean "Whether the worker is available for processing items. Set by the worker")
   :activation_expires_on   (describe (maybe String) "When the worker's activation expires")
   :deactivated_on          (describe (maybe String) "When the worker was deactivated. Automatically set by the system")
   :activated_on            (describe (maybe String) "When the worker was activated. Automatically set by the system")
   :getting_work            (describe Boolean "Whether the worker is claiming an event. Set by the worker")
   :getting_work_expires_on (describe (maybe String) "When the worker must stop trying to claim an event. Automatically set by the system")
   :getting_work_on         (describe (maybe String) "When the worker started claiming an event. Automatically set by the system")
   :working                 (describe Boolean "Whether the worker is processing a event. Set by the worker")
   :working_on              (describe (maybe String) "When the worker began processing the event")
   :last_modified           (describe String "When the worker's record in the database was last modified")})

(defschema UpdateWorker
  (into {}
        (for [keyval (select-keys
                      Worker
                      [:name
                       :active
                       :activation_expires_on
                       :deactivated_on
                       :getting_work
                       :getting_work_on
                       :getting_work_expires_on
                       :working
                       :working_on])]
          [(optional-key (key keyval)) (val keyval)])))

(defschema CPUHoursTotal
  {:id              (describe UUID "The UUID assigned to the total")
   :user_id         (describe UUID "The UUID assigned to the user the total applies to")
   :username        (describe String "The username of the user the total applies to")
   :total           (describe Integer "The total number of CPU hours that the user has reserved")
   :effective_start (describe String "The start date for the time range during which the total is applicable")
   :effective_end   (describe String "The end date for the time range during which the total is applicable ")
   :last_modified   (describe String "The date the record in the database was last modified")})

(defschema Event
  {:id                      EventID
   :record_date             (describe String "The date the event was added to the system")
   :effective_date          (describe String "The date the event is effective")
   :event_type              (describe String "The type of event")
   :value                   (describe Integer "The value associated with the event. How it's used is determined by the event type")
   :created_by              (describe UUID "The UUID of the user that created the event")
   :claimed                 (describe Boolean "Whether the event has been claimed by a worker")
   :claimed_by              (describe (maybe UUID) "The UUID of the worker that claimed the event")
   :claim_expires_on        (describe (maybe String) "The date the worker's claim expires")
   :claimed_on              (describe (maybe String) "The date the worker claimed the event")
   :processed               (describe Boolean "Whether the event has been worked on by a worker")
   :processing              (describe Boolean "Whether a worker is currently processing the event")
   :processed_on            (describe (maybe String) "The date the worker finished processing the event")
   :max_processing_attempts (describe Integer "The number of times any worker can try to process the event")
   :attempts                (describe Integer "The number of times any worker has tried to process the event")
   :last_modified           (describe String "The last time the record was modified in the system")})

(defschema UpdateEvent
  (into {}
        (for [keyval (select-keys Event [:record_date
                                         :effective_date
                                         :event_type
                                         :value
                                         :created_by
                                         :claimed
                                         :claimed_by
                                         :claim_expires_on
                                         :claimed_on
                                         :processed
                                         :processing
                                         :max_processing_attempts
                                         :attempts])]
          [(optional-key (key keyval)) (val keyval)])))