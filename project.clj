(defproject biiwide/sandboxica "0.4.1"

  :description "Avoid the Jungle"

  :url "https://github.com/biiwide/sandboxica"

  :license {:name "Eclipse Public License 2.0"
            :url "https://www.eclipse.org/legal/epl-2.0"}

  :scm {:name "git"
        :url  "https://github.com/biiwide/sandboxica"}

  :plugins [[lein-ancient "0.7.0"]
            [lein-cloverage "1.2.2"]
            [lein-eftest "0.6.0"]
            [lein-file-replace "0.1.0"]]

  :dependencies [[org.clojure/clojure "1.9.0" :scope "provided"]
                 [amazonica "0.3.165" :scope "provided"]
                 [org.javassist/javassist "3.28.0-GA"]]

  :lein-release {:deploy-via :clojars}

  :cloverage {:runner :eftest
              :runner-opts {:multithread? :vars}}

  :eftest {:multithread? :vars}

  :profiles {:dev {:dependencies [[org.clojure/test.check "1.1.1"]
                                  [com.amazonaws/aws-java-sdk-api-gateway "1.12.595"]
                                  [com.amazonaws/aws-java-sdk-athena "1.12.595"]
                                  [com.amazonaws/aws-java-sdk-config "1.12.595"]
                                  [com.amazonaws/aws-java-sdk-ec2 "1.12.595"]
                                  [com.amazonaws/aws-java-sdk-ecs "1.12.595"]
                                  [com.amazonaws/aws-java-sdk-glacier "1.12.595"]
                                  [com.amazonaws/aws-java-sdk-kafka "1.12.595"]
                                  [com.amazonaws/aws-java-sdk-kinesis "1.12.595"]
                                  [com.amazonaws/aws-java-sdk-transfer "1.12.595"]
                                  [com.amazonaws/aws-java-sdk-s3 "1.12.595"]
                                  [com.amazonaws/aws-java-sdk-sqs "1.12.595"]
                                  [eftest "0.6.0"]
                                  [nubank/matcher-combinators "3.8.8"]]}}

  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["file-replace" "README.md" "\\[biiwide/sandboxica \"" "\"]" "version"]
                  ["vcs" "commit"]
                  ["vcs" "tag" "--no-sign"]
                  ["deploy" "clojars"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]]
  )
