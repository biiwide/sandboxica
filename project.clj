(defproject biiwide/sandboxica "0.1.0-SNAPSHOT"

  :description "Avoid the Jungle"

  :url "https://github.com/biiwide/sandboxica"

  :license {:name "Eclipse Public License 2.0"
            :url "https://www.eclipse.org/legal/epl-2.0"}

  :dependencies [[org.clojure/clojure "1.8.0" :scope "provided"]
                 [amazonica "0.3.142" :scope "provided"]
                 [cglib "3.2.12"]]

  :profiles {:dev {:dependencies [[amazonica "0.3.142"]]}}

  )
