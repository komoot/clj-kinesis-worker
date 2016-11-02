(ns clj-kinesis-worker.core
  (:require [taoensso.encore :refer [doto-cond]]
            [taoensso.timbre :as log])
  (:import [java.net InetAddress]
           [java.util UUID]
           [com.amazonaws.auth
            DefaultAWSCredentialsProviderChain
            AWSCredentialsProvider]
           [com.amazonaws.regions Regions]
           [com.amazonaws ClientConfiguration]
           [com.amazonaws.services.cloudwatch AmazonCloudWatchClient]
           [com.amazonaws.services.kinesis AmazonKinesisClient]
           [com.amazonaws.services.kinesis.clientlibrary.interfaces
            IRecordProcessorFactory
            IRecordProcessor]
           [com.amazonaws.services.kinesis.clientlibrary.lib.worker
            KinesisClientLibConfiguration
            Worker InitialPositionInStream]
           [com.amazonaws.services.dynamodbv2 AmazonDynamoDBClient]))

#_(log/merge-config!
  {:level      :info
   ;; reduce logging from the slf4j adapter to WARN
   :middleware [(fn min-level-for-ns [msg]
                  (when
                    (or (not= "slf4j-timbre.adapter" (:?ns-str msg))
                      (log/level>= (:level msg) :warn))
                    msg))]})

(defn- enum-name
  [key]
  (-> key name clojure.string/upper-case (clojure.string/replace #"-" "_")))

(defn- aws-region-enum
  "Converts a region key like :eu-west-1 to an enum from com.amazon.regions.Regions"
  [key]
  (when-let [region-name (enum-name key)]
    (. Regions valueOf region-name)))

(defn- initial-position-enum
  [key]
  (. InitialPositionInStream valueOf (enum-name key)))

(defn dynamodb-client
  [{:keys [provider region endpoint] :as client-opts}]
  (if (empty? client-opts)
    (AmazonDynamoDBClient.)
    (let [^AWSCredentialsProvider provider
          (or provider (DefaultAWSCredentialsProviderChain.))
          ;; TODO: client configuration such as timeout, max retries
          ^ClientConfiguration config
          (ClientConfiguration.)]
      (doto-cond [c (AmazonDynamoDBClient. provider config)]
        region (.withRegion (aws-region-enum region))
        endpoint (.setEndpoint endpoint)))))

(defn kinesis-client
  [{:keys [provider region endpoint] :as client-opts}]
  (if (empty? client-opts)
    (AmazonKinesisClient.)
    (let [^AWSCredentialsProvider provider
          (or provider (DefaultAWSCredentialsProviderChain.))
          ;; TODO: client configuration such as timeout, max retries
          ^ClientConfiguration config
          (ClientConfiguration.)]
      (doto-cond [c (AmazonKinesisClient. provider config)]
        region (.withRegion (aws-region-enum region))
        endpoint (.setEndpoint endpoint)))))

(defn cloudwatch-client
  [config {:keys [region]}]
  (doto-cond [c (AmazonCloudWatchClient. (.getCloudWatchCredentialsProvider config) (.getCloudWatchClientConfiguration config))]
    region (.withRegion (aws-region-enum region))))

(defprotocol RecordProcessor
  (initialize [this shard-id])
  (process-records [this shard-id records checkpointer])
  (shutdown [this shard-id checkpointer reason]))

(defn- default-worker-id
  []
  (str (.getCanonicalHostName (InetAddress/getLocalHost)) ":" (UUID/randomUUID)))

(def ^:private worker*
  (memoize
    (fn
      [{:keys [provider region kinesis dynamodb worker-id app-name stream-name initial-position failover-time processor-factory-fn]
        :or   {worker-id (default-worker-id) initial-position :latest}
        :as   worker-opts}]
      ;; TODO: client configuration such as timeout, max retries
      (let [_ (assert (not (nil? app-name)) ":app-name missing")
            _ (assert (not (nil? stream-name)) ":stream-name missing")
            _ (assert (fn? processor-factory-fn) "value of :processor-factory-fn is not a function")
            _ (assert (contains? #{:trim-horizon :latest} initial-position) "value of :initial-position is invalid")
            _ (when failover-time (assert (and (integer? failover-time) (pos? failover-time)) "value of :failover-time must be a positive integer"))

            ^AWSCredentialsProvider provider
            (or provider (DefaultAWSCredentialsProviderChain.))
            ^AmazonDynamoDBClient dynamodb-client
            (dynamodb-client (merge worker-opts dynamodb))
            ^AmazonKinesisClient kinesis-client
            (kinesis-client (merge worker-opts kinesis))
            ^KinesisClientLibConfiguration config
            (doto-cond [c (KinesisClientLibConfiguration. app-name stream-name provider worker-id)]
              :always       (.withInitialPositionInStream (initial-position-enum initial-position))
              region        (.withRegionName (name region))
              failover-time (.withFailoverTimeMillis failover-time))
            ^AmazonCloudWatchClient cloudwatch-client
            (cloudwatch-client config worker-opts)
            processor-factory
            (reify IRecordProcessorFactory
              (createProcessor [_]
                (let [^RecordProcessor processor (processor-factory-fn)
                      shard-id (atom nil)]
                  (reify IRecordProcessor
                    (initialize [_ sid]
                      (reset! shard-id sid)
                      (initialize processor @shard-id))
                    (processRecords [_ records checkpointer]
                      (process-records processor @shard-id records checkpointer))
                    (shutdown [_ checkpointer reason]
                      (shutdown processor @shard-id checkpointer reason)
                      (reset! shard-id nil))))))]
        (Worker.
          processor-factory
          config
          kinesis-client
          dynamodb-client
          cloudwatch-client)))))

(defn create-worker ^Worker [worker-opts] (worker* worker-opts))

(comment

  (defrecord TestProcessor []
    RecordProcessor
    (initialize [this shard-id]
      (log/info "Initializing, shard id:" shard-id))

    (process-records [this shard-id records checkpointer]
      (log/info "Processing" (count records) "from" shard-id)
      (doseq [r records]
        (log/info r)))

    (shutdown [_ shard-id checkpointer reason]
      (log/info "Shutting down")))

  (defn new-processor
    []
    (->TestProcessor))

  (def worker
    (clj-kinesis-worker.core/create-worker
      {:kinesis              {:endpoint "http://localhost:4567"}
       :dynamodb             {:endpoint "http://localhost:4568"}
       :region               "eu-west-1"
       :stream-name          "some-stream"
       :app-name             "some-app"
       :failover-time        60000
       :processor-factory-fn new-processor}))

  (.run worker)

)
