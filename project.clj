(defproject biiwide/sandboxica "0.3.1-SNAPSHOT"

  :description "Avoid the Jungle"

  :url "https://github.com/biiwide/sandboxica"

  :license {:name "Eclipse Public License 2.0"
            :url "https://www.eclipse.org/legal/epl-2.0"}

  :scm {:name "git"
        :url  "https://github.com/biiwide/sandboxica"}

  :plugins [[lein-ancient "0.7.0"]
            [lein-cloverage "1.2.2"]
            [lein-eftest "0.5.9"]
            [lein-file-replace "0.1.0"]]

  :dependencies [[org.clojure/clojure "1.9.0" :scope "provided"]
                 [amazonica "0.3.156" :scope "provided"]
                 [org.javassist/javassist "3.28.0-GA"]]

  :lein-release {:deploy-via :clojars}

  :cloverage {:runner :eftest
              :runner-opts {:multithread? :vars}}

  :eftest {:multithread? :vars}

  :profiles {:dev {:dependencies [[com.amazonaws/aws-java-sdk-s3 "1.11.993"]
                                  [eftest "0.5.9"]
                                  [nubank/matcher-combinators "3.2.1"]]}}

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
