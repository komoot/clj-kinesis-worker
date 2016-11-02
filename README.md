# clj-kinesis-worker

Thin wrapper around the Amazon Kinesis Client Library, more specifically the Worker class.

Developing workers using the KCL is preferrable to using the API directly, because
the library takes care of things like distributing shards among workers and providing
a distributed failover mechanism. See the [docs] [1] for more information.
[This talk] [2] is also pretty informative!

## Usage

You will need to implement a protocol:

```clojure
;; (defprotocol RecordProcessor
;;   (initialize [this shard-id])
;;   (process-records [this shard-id records checkpointer])
;;;  (shutdown [this shard-id checkpointer reason]))


(defrecord TestProcessor []
  RecordProcessor
  (initialize [_ shard-id] ...)
  (process-records [_ shard-id records checkpointer] ...)
  (shutdown [_ shard-id checkpointer reason] ...)

(defn new-processor [] (->TestProcessor))
```

A worker can then be created:

```clojure
(def worker
  (clj-kinesis-worker.core/create-worker
    {:region               "eu-west-1"
     :stream-name          "some-stream"
     :app-name             "some-app"
     :processor-factory-fn new-processor}))
```

There is also an `:initial-position` option that can take two values: `:trim-horizon` and `:latest`. This refers to the point in the
Kinesis log stream that the worker starts reading events from **the first time the application is launched**. If the application is
already known to the KCL, the KCL has created a DynamoDB table with information on what the last Sequence ID was that the application
processed. This information takes precedence over the `:initial-position` configuration. You can of course delete the DynamoDB table
to force the application to take the `:initial-position` option into account.

Further options:

* `:failover-time` - Set this to a value higher than the maximum processing time, in order to prevent the KCL from giving a batch
  to a new processor instance before it has been completely processed.

## Development

KCL uses both DynamoDB and Kinesis to keep track of and process events. Both DynamoDB and Kinesis can be mocked locally:

```
$> npm install -g dynalite
$> npm install -g kinesalite
$> dynalite --port 4567 &
$> kinesalite --port 4568 &
```

The corresponding worker configuration:

```clojure
(clj-kinesis-worker.core/create-worker
  {:kinesis              {:endpoint "http://localhost:4568"}
   :dynamodb             {:endpoint "http://localhost:4567"}
   :region               "eu-west-1"
   :stream-name          "some-stream"
   :app-name             "some-app"
   :processor-factory-fn new-processor}))

```

You can now use another library like [clj-kinesis-client] [3] to feed the pipeline from one end.

Note that KCL still has a CloudWatch dependency. So access to AWS needs to be secured,
for example using environment variables like `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`.
The `create-worker` function will use the default AWS credentials provider chain for
authentication.

## TODO

* Client configuration like max retries, timeouts
* Wrap checkpointer, provide default retry logic

## License

Copyright © 2016 komoot GmbH

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.

[1]: http://docs.aws.amazon.com/kinesis/latest/dev/developing-consumers-with-kcl.html
[2]: https://www.youtube.com/watch?v=AXAaCG2QUkE
[3]: https://github.com/adtile/clj-kinesis-client
