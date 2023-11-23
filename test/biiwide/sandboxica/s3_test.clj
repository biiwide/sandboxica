(ns biiwide.sandboxica.s3-test
  (:require [amazonica.aws.s3 :as s3]
            [biiwide.sandboxica.alpha :as sandbox]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop])
  (:import (com.amazonaws.services.s3.model AmazonS3Exception)
           (java.io ByteArrayOutputStream)
           (java.net URLDecoder URLEncoder)))

(defn copy-to-buffer
  [readable]
  (with-open [out (ByteArrayOutputStream.)]
    (io/copy readable out)
    (.toByteArray out)))

(defn encode-bucket-key
  ([bucket-name object-key]
   (format "%s:%s"
           (URLEncoder/encode (str bucket-name))
           object-key))
  ([s3-obj]
   (encode-bucket-key (:bucket-name s3-obj)
                      (:key s3-obj))))

(defn decode-bucket-key
  [s]
  (let [[b k] (string/split s #":" 2)]
    {:buckeet-name (URLDecoder/decode b)
     :key          k}))

(defn just-s3-objects
  [{:keys [page-size state]
    :or   {page-size 3
           state     (atom (sorted-map))}}]
  (sandbox/just*
   {`s3/get-object
    (fn s3-get-object [req]
      (if-some [{:keys [buffer data]} (get @state (encode-bucket-key req))]
        (assoc data :object-content buffer)
        (throw (AmazonS3Exception.
                (str "The specified key does not exist. (Service: Fake S3; "
                     "Status Code: 404; Error Code: NoSuchKey)")))))

    `s3/get-object-metadata
    (fn s3-get-object-metadata [req]
      (if-some [{:keys [data]} (get @state (encode-bucket-key req))]
        data
        (throw (AmazonS3Exception.
                (str "Not Found (Service: Fake S3; "
                     "Status Code: 404; Error Code: 404 Not Found)")))))

    `s3/list-objects-v2
    (fn s3-list-objects-v2 [req]
      (let [max-keys (or (:max-keys req)
                         page-size)
            qual-pfx (encode-bucket-key (:bucket-name req)
                                        (:prefix req ""))
            start-at (or (:continuation-token req)
                         qual-pfx)
            [entries more]
            (->> (subseq @state >= start-at)
                 (take-while #(string/starts-with? (key %) qual-pfx))
                 (split-at max-keys))]
        (conj
         {:max-keys max-keys
          :key-count (count entries)
          :object-summaries
          (mapv (comp :data val) entries)}
         (when-some [[next-qk _] (first more)]
           {:truncated               true
            :next-continuation-token next-qk}))))

    `s3/put-object
    (fn s3-put-object [req]
      (let [now    (java.util.Date.)
            buffer (copy-to-buffer (or (:input-stream req)
                                       (:file req)))
            md     {:content-length (count buffer)
                    :content-md5    (str (hash buffer))
                    :etag           (str (hash buffer))
                    :last-modified  now}
            data   {:bucket-name     (:bucket-name req)
                    :key             (:key req)
                    :etag            (str (hash buffer))
                    :last-modified   now
                    :metadata        md
                    :object-metadata md
                    :size            (count buffer)}]
        (swap! state assoc (encode-bucket-key req)
               {:buffer buffer
                :data   data})
        data))}))

(def FAKE_CREDS
  {:access-key "AK00000"
   :secret-key "boo!"
   :endpoint   "https://localhost:9999/1"})

(defspec stress-fake-s3
  (prop/for-all [bucket  gen/string
                 objects (gen/map gen/string-alphanumeric gen/string)]
    (sandbox/with
      (comp (just-s3-objects {})
            sandbox/always-fail)
      (doseq [[k v] objects]
        (s3/put-object FAKE_CREDS {:bucket-name bucket
                                   :key         k
                                   :input-stream (io/input-stream (.getBytes v))}))
      (and (is (= (sort (keys objects))
                  (map :key (:object-summaries
                              (s3/list-objects-v2 FAKE_CREDS {:bucket-name bucket
                                                              :max-keys    (count objects)})))))
           (is (every? (fn [[k v]]
                         (is (= v
                                (-> (s3/get-object FAKE_CREDS
                                                   {:bucket-name bucket
                                                    :key         k})
                                    (:input-stream)
                                    (slurp)))
                             (format "Contents of key '%s' must match original value." k)))
                       objects))))))
