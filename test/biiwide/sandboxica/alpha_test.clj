(ns biiwide.sandboxica.alpha-test
  (:require [amazonica.aws.ec2 :as ec2]
            [amazonica.aws.s3 :as s3]
            [amazonica.aws.s3transfer :as s3transfer]
            [amazonica.aws.sqs :as sqs]
            [biiwide.sandboxica.alpha :as sandbox]
            [clojure.java.io :as io]
            [clojure.test :refer [are deftest is]]
            matcher-combinators.test)
  (:import  (com.amazonaws.client AwsSyncClientParams)
            (com.amazonaws.services.ec2 AmazonEC2Client)
            (com.amazonaws.services.ec2.model
             DescribeInstancesRequest DescribeInstancesResult)
            (com.amazonaws.services.s3.model
             ListObjectsV2Result S3ObjectSummary)
            (com.amazonaws.services.s3.transfer Upload)
            (com.amazonaws.services.sqs AmazonSQSClient)
            (com.amazonaws.services.sqs.model
             DeleteMessageResult SendMessageRequest SendMessageResult)
            (java.lang.reflect Method Modifier)
            (javassist.util.proxy MethodHandler)))


(deftest test-invocation-handler
  (is (instance?
        MethodHandler
        (sandbox/invocation-handler [_ _] nil)))
  (let [o (Object.)]
    (is (identical? o (.invoke (sandbox/invocation-handler [_ _] o)
                               nil nil nil nil)))))


(deftest test-sync-client-params
  (let [empty-params (#'sandbox/aws-sync-client-params {} {})]
    (is (instance? AwsSyncClientParams empty-params))
    (is (some? (.getCredentialsProvider empty-params)))
    (is (some? (.getClientConfiguration empty-params)))
    (is (some? (.getRequestHandlers empty-params)))
    (is (empty? (.getRequestHandlers empty-params)))
    (is (some? (.getRequestMetricCollector empty-params)))
    (is (some? (.getClientSideMonitoringConfigurationProvider
                 empty-params)))
    (is (nil? (.getMonitoringListener empty-params))))

  (let [creds (.getCredentials
                (.getCredentialsProvider
                  (#'sandbox/aws-sync-client-params {:access-key "abc"
                                                :secret-key "def"}
                                               {})))]
    (and (is (= "abc" (.getAWSAccessKeyId creds)))
         (is (= "def" (.getAWSSecretKey creds))))))


(deftest test-client-proxy
  (are [client-class endpoint-prefix service-name]
       (let [proxy (#'sandbox/client-proxy client-class
                     (sandbox/invocation-handler [_ _]
                       (throw (RuntimeException. "BOOM!"))))]
         (and (is (instance? client-class proxy))
              (is (= endpoint-prefix (.getEndpointPrefix proxy)))
              (is (= service-name (.getServiceName proxy)))
              ))

       com.amazonaws.services.apigateway.AmazonApiGatewayClient "apigateway" "apigateway"
       com.amazonaws.services.athena.AmazonAthenaClient         "athena"     "athena"
       com.amazonaws.services.config.AmazonConfigClient         "config"     "config"
       com.amazonaws.services.ec2.AmazonEC2Client               "ec2"        "ec2"
       com.amazonaws.services.ecs.AmazonECSClient               "ecs"        "ecs"
       com.amazonaws.services.glacier.AmazonGlacierClient       "glacier"    "glacier"
       com.amazonaws.services.kafka.AWSKafkaClient              "kafka"      "kafka"
       com.amazonaws.services.kinesis.AmazonKinesisClient       "kinesis"    "kinesis"
       com.amazonaws.services.s3.AmazonS3Client                 "s3"         "s3"
       com.amazonaws.services.sns.AmazonSNSClient               "sns"        "sns"
       com.amazonaws.services.sqs.AmazonSQSClient               "sqs"        "sqs"
       com.amazonaws.services.transfer.AWSTransferClient        "transfer"   "transfer"
       ))


(defn method
  [class method-name & arg-types]
  (or (try (.getMethod ^Class class method-name
             (into-array Class arg-types))
        (catch NoSuchMethodException _ nil))
      (try (.getDeclaredMethod ^Class class method-name
             (into-array Class arg-types))
        (catch NoSuchMethodException _ nil))))


(deftest test-public?
  (are [p? method]
       (p? (#'sandbox/public? method))

       true?  (method Object "toString")
       false? (method Object "finalize")
       false? (method BigDecimal "inflated")
       ))


(deftest test-unmarshall
  (are [clazz val]
       (instance? clazz (#'sandbox/unmarshall clazz val))

       DescribeInstancesResult {}
       SendMessageResult       {}
       Upload                  (reify Upload)
       ))


(deftest test-coerce-method-implementation
  (are [expectation method f args]
       (let [result (apply (#'sandbox/coerce-method-implementation f method) args)]
         (is expectation))

       (instance? DescribeInstancesResult result)
       (method AmazonEC2Client "describeInstances" DescribeInstancesRequest)
       (constantly {:abc "def"})
       [{}]

       (= "abcdefg" (.getMessageId result))
       (method AmazonSQSClient "sendMessage" SendMessageRequest)
       (fn [req]
         (is (= "foobar" (:message-body req)))
         (is (= "000"    (:message-deduplication-id req)))
         {:message-id "abcdefg"})
       [{:message-body "foobar"
         :message-deduplication-id "000"}]

       (= "xyz" (.getMessageId result))
       (method AmazonSQSClient "sendMessage" SendMessageRequest)
       (fn [req]
         (is (= "foobar" (:message-body req)))
         (is (= "000"    (:message-deduplication-id req)))
         {:message-id "xyz"})
       [{:message-body "foobar"
         :message-deduplication-id "000"}]

       (instance? DeleteMessageResult result)
       (method AmazonSQSClient "deleteMessage" String String)
       (fn [url message-id]
         (is (= "queue-url" url))
         (is (= "54321" message-id))
         {:a "b"})
       ["queue-url" "54321"]
       ))


(deftest test-with

  (sandbox/with sandbox/always-nothing
    (is (nil? (ec2/describe-instances {:filters []}))))


  (sandbox/with sandbox/always-fail
    (is (thrown? UnsupportedOperationException
          (ec2/describe-instances {:filters []}))))


  (sandbox/with (comp (sandbox/just
                        (ec2/describe-instances [req]
                          {:reservations [{:reservation-id "1"}
                                          {:reservation-id "2"}]}))
                      sandbox/always-nothing)
    (is (= ["1" "2"]
           (->> (ec2/describe-instances {:filters []})
                (:reservations)
                (map :reservation-id))))
    (is (nil? (sqs/delete-message "a" "b"))))
  )


(deftest test-with-transfer-manager
  (sandbox/with
    sandbox/always-nothing
    (is (nil? (s3transfer/upload "" "" (java.io.File. "abc")))))

  (sandbox/with
    (sandbox/just
      (s3transfer/upload [& args] (reify Upload)))
    (is (s3transfer/upload "" "" (java.io.File. "abc"))))
  )


(deftest test-aws-class-lineage
  (are [clazz expected]
    (= expected (#'sandbox/aws-class-lineage clazz))

    com.amazonaws.services.s3.model.GetObjectAclRequest
    [com.amazonaws.services.s3.model.GetObjectAclRequest]

    com.amazonaws.services.s3.model.PutObjectRequest
    [com.amazonaws.services.s3.model.PutObjectRequest
     com.amazonaws.services.s3.model.AbstractPutObjectRequest]
    ))


(deftest test-needs-better-marshalling?
  (are [p? clazz]
    (p? (#'sandbox/needs-better-marshalling? clazz))

    false? nil
    false? String
    false? Long
    false? com.amazonaws.services.s3.model.ObjectMetadata
    false? com.amazonaws.services.s3.model.GetObjectAclRequest
    true?  com.amazonaws.services.s3.model.PutObjectRequest
    ))


(deftest test-get-deep-fields
  (are [clazz value]
    (match? value (sandbox/get-deep-fields
                    (#'sandbox/unmarshall clazz value)))

    com.amazonaws.services.s3.model.PutObjectRequest
    {:bucket-name "foo.bar"
     :key "abcdefg"
     :storage-class "REDUCED_REDUNDANCY"
     :metadata {:content-type "text/random"}}
    ))

(deftest test-s3-put-object
  ;; s3/put-object has unique behavior as described in this issue report:
  ;; https://github.com/biiwide/sandboxica/issues/1
  (let [content "Hello"
        put-req {:bucket-name  "mybucket"
                 :key          "something.txt"
                 :input-stream (io/input-stream (.getBytes content))
                 :metadata     {:content-type   "text/plain"
                                :content-length (count content)
                                :user-metadata  {:abc "def"}}}]
  (sandbox/with (sandbox/just
                 (s3/put-object [req]
                   (is (match? (assoc put-req :input-stream content)
                               (update req :input-stream slurp)))
                   {}))
    (s3/put-object {:profile "none"} put-req))))

(deftest test-unmarshal-s3-list-objects-v2-result
  (is (match? {:object-summaries [{:key "foo"}]}
              (sandbox/marshall++
                (#'sandbox/unmarshall ListObjectsV2Result
                                      {:object-summaries [{:key "foo"}]})))))
