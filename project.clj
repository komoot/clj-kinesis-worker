(defproject de.komoot/clj-kinesis-worker "1.0.1"
  :author "Johannes Staffans"
  :description "Wrapper for the Amazon Kinesis Client library"
  :url "https://github.com/komoot/clj-kinesis-worker"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [com.amazonaws/amazon-kinesis-client "1.6.1"]
                 [com.taoensso/encore "2.79.1" :exclusions [org.clojure/clojure]]
                 [com.taoensso/timbre "4.7.4" :exclusions [org.clojure/clojure]]
                 [com.fzakaria/slf4j-timbre "0.3.2"]
                 [org.slf4j/jcl-over-slf4j "1.7.14"]
                 [org.slf4j/slf4j-api "1.7.14"]]
  :profiles {:dev {:dependencies [[org.clojure/core.async "0.2.374"]
                                  [byte-streams "0.2.0"]
                                  [base64-clj "0.1.1"]
                                  [juxt/iota "0.2.0"]
                                  [clj-kinesis-client "0.0.7"]]}})
