(defproject biiwide/sandboxica "0.2.2-SNAPSHOT"

  :description "Avoid the Jungle"

  :url "https://github.com/biiwide/sandboxica"

  :license {:name "Eclipse Public License 2.0"
            :url "https://www.eclipse.org/legal/epl-2.0"}

  :scm {:name "git"
        :url  "https://github.com/biiwide/sandboxica"}

  :plugins [[lein-ancient "0.7.0"]]

  :dependencies [[org.clojure/clojure "1.8.0" :scope "provided"]
                 [amazonica "0.3.156" :scope "provided"]
                 [cglib "3.3.0"]]

  :lein-release {:deploy-via :clojars}

  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag" "--no-sign"]
                  ["deploy"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]]
  )
