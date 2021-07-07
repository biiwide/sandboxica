(ns biiwide.sandboxica.alpha
  (:refer-clojure :exclude [methods])
  (:require [amazonica.core :as aws]
            [clojure.string :as string])
  (:import  [com.amazonaws ClientConfiguration]
            [com.amazonaws.auth AWSCredentialsProvider]
            [com.amazonaws.client AwsSyncClientParams]
            [java.lang.reflect Method Modifier]
            [java.util.concurrent CopyOnWriteArrayList]
            [javassist.util.proxy MethodFilter MethodHandler
             ProxyObject ProxyFactory]))


(defmacro invocation-handler
  "A macro that constructs an invocation handler for use by client proxies.
Invocations handlers are functions of 2 arguments: a method, and an arguments array.

Example:
(let [client (create-client class)]
  (invocation-handler [^Method method args]
    (println (.getName method) \\: (seq args))
    (.invoke method client args)))
"
  [[method-sym args-sym] & body]
  (letfn [(tag [sym type]
            (vary-meta sym assoc :tag type))
          (array-hint [clazz]
            (symbol (.getName (class (into-array clazz [])))))]
    `(reify javassist.util.proxy.MethodHandler
       (invoke [~'_ ~'_
                ~(tag method-sym 'java.lang.reflect.Method)
                ~(tag '_ 'java.lang.reflect.Method)
                ~(tag args-sym (array-hint Object))]
         ~@body))))


(defn- client-configuration
  [client-config]
  (if (instance? ClientConfiguration client-config)
    client-config
    (#'aws/get-client-configuration
      (or client-config {}))))


(defn- ^AwsSyncClientParams aws-sync-client-params
  [credentials configuration]
  (let [aws-creds  (aws/get-credentials credentials)
        aws-config (client-configuration configuration)
        metrics    (com.amazonaws.metrics.RequestMetricCollector/NONE)]
    (proxy [AwsSyncClientParams] []
      (getCredentialsProvider [] aws-creds)
      (getClientConfiguration [] aws-config)
      (getRequestMetricCollector [] metrics)
      (getRequestHandlers [] (CopyOnWriteArrayList.))
      (getClientSideMonitoringConfigurationProvider []
        (com.amazonaws.monitoring.DefaultCsmConfigurationProviderChain/getInstance))
      (getMonitoringListener [] nil))))


(defn- public? [^Method m]
  (Modifier/isPublic (.getModifiers m)))


(defn- find-constructor
  [^Class clazz arg-types]
  (or (try
        (.getConstructor clazz (into-array Class arg-types))
        (catch NoSuchMethodException e nil))
      (try
        (.getDeclaredConstructor clazz (into-array Class arg-types))
        (catch NoSuchMethodException e nil))))


(definline ^:private has-constructor?
  [arg-types clazz]
  `(some? (find-constructor ~clazz ~arg-types)))


(defn- typed-client-constructor-args
  [client-class aws-creds aws-config]
  (let [creds-provider (aws/get-credentials aws-creds)
        client-config  (client-configuration aws-config)]
    (partition 2
      (condp has-constructor? client-class
        [AwsSyncClientParams]
        [AwsSyncClientParams (aws-sync-client-params aws-creds aws-config)]

        [AWSCredentialsProvider ClientConfiguration]
        [AWSCredentialsProvider creds-provider
         ClientConfiguration    client-config]

        [AWSCredentialsProvider]
        [AWSCredentialsProvider creds-provider]

        []
        []
        ))))


(def ^:private ignored-client-methods
  #{"getClientConfiguration"
    "getEndpointPrefix"
    "getServiceName"
    "setEndpoint"})


(defn- client-proxy
  [client-class method-invocation-handler]
  (let [typed-args (typed-client-constructor-args
                     client-class
                     {:access-key ""
                      :secret-key ""
                      :endpoint   "http://localhost:1/"}
                     {})
        proxy-factory (doto (ProxyFactory.)
                        (.setSuperclass client-class)
                        (.setFilter (reify MethodFilter
                                      (isHandled [_ method]
                                        (cond (not (public? method))                          false
                                              (not= client-class (.getDeclaringClass method)) false
                                              (ignored-client-methods (.getName method))      false
                                              :else true)))))
        proxy-client (.create proxy-factory
                       (into-array Class (map first typed-args))
                       (into-array Object (map second typed-args)))]
    (doto ^ProxyObject proxy-client
      (.setHandler method-invocation-handler))))


(defn- client-proxy?
  [x]
  (if (class? x)
    (some? (some #{javassist.util.proxy.ProxyObject}
                 (ancestors x)))
    (instance? javassist.util.proxy.ProxyObject x)))


(defn- original-class
  [x]
  (cond (nil? x) nil
        (class? x)
        (if (client-proxy? x)
          (recur (.getSuperclass ^Class x))
          x)
        :else (recur (class x))))


(defn- require-ns
  [ns-sym]
  (let [ns-sym (symbol ns-sym)]
    (or (get (ns-aliases *ns*) ns-sym)
        (require ns-sym))))


(defn- unmarshall
  [^Class clazz result]
  (if (instance? clazz result)
    result
    (first (#'aws/unmarshall
             {:actual  [clazz]
              :generic [clazz]}
             [result]))))

(def ^:private aws-package? @#'aws/aws-package?)
(def ^:private camel->keyword @#'aws/camel->keyword)
(def ^:private prop->name @#'aws/prop->name)

(defn- aws-class-lineage
  [^Class clazz]
  (lazy-seq
    (when (and (instance? Class clazz)
               (aws-package? clazz)
               (not= "com.amazonaws.AmazonWebServiceRequest"
                    (.getName  clazz)))
      (cons clazz (aws-class-lineage (.getSuperclass clazz))))))

(declare marshall++)

(defn get-deep-fields
  "Returns a map of all non-null values returned by invoking all
  public getters on the specified object.  The functionality is
  similar to amazonica.core/get-fields but also performs a limitted
  search of the object's parent classes.

  This function can be used to implement the amazonica.core/IMarshall
  protocol for classes that are not well supported, such as
  com.amazonaws.services.s3.model.PutObjectRequest."
  [obj]
  (let [no-arg (make-array Object 0)]
    (into {}
          (mapcat (fn [^Class clazz]
                    (for [^Method m (aws/accessors clazz true)]
                      (let [r (marshall++ (.invoke m obj no-arg))]
                        (if-not (nil? r)
                          (hash-map
                            (camel->keyword (prop->name m))
                            r)))))
                  (aws-class-lineage (class obj))))))

(defn- needs-better-marshalling?
  [x]
  (cond (nil? x)   false
        (class? x) (boolean
                     (and (not (extends? aws/IMarshall x))
                          (next (aws-class-lineage x))))
        :else      (recur (class x))))

(defn marshall++
  "Like amazonica.core/marshall but improves handling of
  some edge cases."
  [x]
  (if (needs-better-marshalling? (class x))
    (get-deep-fields x)
    (aws/marshall x)))

(defn coerce-method-implementation
  "Returns a wrapped function that will marshall arguments to Clojure values,
and unmarshall Clojure values to a result type based on the given method signature."
  [f ^Method method]
  (let [rt (.getReturnType method)]
    (fn ([]
          (unmarshall rt (f)))
        ([a]
          (unmarshall rt (f (marshall++ a))))
        ([a b]
          (unmarshall rt (f (marshall++ a)
                            (marshall++ b))))
        ([a b & more]
          (unmarshall rt (apply f (mapv marshall++
                                        (list* a b more))))))))

(defn method-coercions
  "Given a function and a collection of methods,
returns a map of composed functions that will coerce arguments and
results to match the method signatures.
See also: coerce-method-implementation"
  [f methods]
  (zipmap (map #(.getName ^Method %) methods)
          (map (partial coerce-method-implementation f) methods)))


(defn- resolve-client-methods
  [impls]
  (reduce-kv (fn [clients k v]
               (cond (class? k)
                     (assoc clients k v)

                     (symbol? k)
                     (do (require-ns (namespace k))
                         (let [mk (meta (resolve k))]
                           (if-some [client-class (:amazonica/client mk)]
                             (update clients client-class merge
                                     (method-coercions v (:amazonica/methods mk)))
                             (throw (IllegalArgumentException.
                                      (str k " is not an amazonica client function"))))))))
             {}
             impls))


(defn- get-client-fn
  [k]
  (or (get @#'aws/*client-config* k)
      (get @@#'aws/client-config  k)))


(defn- wrap
  [f mw]
  (mw f))


(defn with-client-middleware*
  "Evaluates a function in the context of the provided amazon-client-fn."
  [client-middleware f]
  (let [memow (comp memoize wrap)
        {:keys [amazon-client-fn encryption-client-fn transfer-manager-fn]}
        (cond (fn? client-middleware) {:amazon-client-fn     client-middleware
                                       :encryption-client-fn client-middleware
                                       :transfer-manager-fn  client-middleware}
              (map? client-middleware) client-middleware)]
    (aws/with-client-config
      (cond-> @#'aws/*client-config*
        amazon-client-fn
        (assoc :amazon-client-fn     (memow (get-client-fn :amazon-client-fn)
                                            amazon-client-fn))
        encryption-client-fn
        (assoc :encryption-client-fn (memow (get-client-fn :encryption-client-fn)
                                            encryption-client-fn))
        transfer-manager-fn
        (assoc :transfer-manager-fn  (memow (get-client-fn :transfer-manager-fn)
                                            transfer-manager-fn)))
      (f))))


(defn just*
  "Constructs a client middleware function that just
implements specific AWS endpoint calls for specific clients.
The client endpoints are constructed based on a map of
Amazonica function names to implementing functions.
All other clients and method calls are delegated to the
original client function and client methods.
See also: 'just
Example:
(just* {'amazonica.aws.ecs/describe-instances
        (fn [req]
          (println \"Request:\" req)
          {})
        'amazonica.aws.sqs/delete-message
        (fn ([req]
              (println \"Request:\" req)
              {})
            ([queue-url receipt-handle]
              (println {:queue-url queue-url, :receipt-handle: receipt-handle})
              {})))
"
  [implementations]
  (let [impls (resolve-client-methods implementations)]
    (fn [client-fn]
      (fn [& client-fn-args]
        (let [client (apply client-fn client-fn-args)
              clazz  (original-class client)]
          (if-some [methods (get impls clazz)]
            (client-proxy clazz
              (invocation-handler [method args]
                (if-some [m (get methods (.getName method))]
                  (apply m (seq args))
                  (.invoke method client args))))
            client))))))


(defn- resolve-sym [sym]
  (if-some [ns (some->> (namespace sym)
                        (symbol)
                        (get (ns-aliases *ns*)))]
    (symbol (name (ns-name ns)) (name sym))
    sym))


(defmacro just
  "This macro constructs a client middleware function
which just implements specific AWS endpoint calls for
specific clients.  The endpoint calls are defined based
on the corresponding Amazonica function.
All other clients and method calls are delegated to the
original client function and client methods.
See also: 'just*
Example:
(just (amazonica.aws.ec2/describe-instances [req]
        (println \"Request:\" req)
        {:next-token \"abc\"
         :reservations []})
      (amazonica.aws.sqs/delete-message
        ([req]
          (println \"Request:\" req)
          {})
        ([queue-url receipt-handle]
          (println \"QueueUrl:\" queue-url \"ReceiptHandle:\" receipt-handle)
          {})))
"
  [& impls]
  `(just* ~(into {} (for [[sym & body] impls]
                      [(list 'quote (#'biiwide.sandboxica.alpha/resolve-sym sym))
                       (cons 'fn body)]))))


(defn- nothing-value
  "Return the appropriate \"nothing\" value for a type."
  [^java.lang.reflect.Type type]
  (case type
        Boolean/TYPE false
        Byte/TYPE    (byte 0)
        Short/TYPE   (short 0)
        Integer/TYPE (int 0)
        Long/Type    0
        Float/Type   Float/NaN
        Double/Type  Double/NaN
        nil))


(defn always-nothing
  "This client middleware function builds clients that
always return a \"nothing\" value for every client action.

Typically the \"nothing\" value is nil, but will also return:
  false, 0, and NaN for methods with primitive return types."
  [client-fn]
  (fn [& client-fn-args]
    (let [c (apply client-fn client-fn-args)]
      (client-proxy (original-class c)
        (invocation-handler [method args]
          (nothing-value (.getReturnType ^Method method)))))))


(defn- class-name
  [^Class clazz]
  (.getName clazz))


(defn- method-description
  "Return a string containing the class name,
method name, and parameter types for a method."
  [^Method method]
  (format "%s/%s(%s)"
          (class-name (.getDeclaringClass method))
          (.getName method)
          (string/join ", "
                       (map class-name
                            (seq (.getParameterTypes method))))))


(defn always-fail
  "This client-fn middleware builds clients that always throw
an UnsupportedOperationException for every client action."
  [client-fn]
  (fn [& client-fn-args]
    (let [c (apply client-fn client-fn-args)]
      (client-proxy (original-class c)
        (invocation-handler [method args]
          (throw (UnsupportedOperationException.
                   (format "Method %s is not supported in this context."
                           (method-description method)))))))))


(defmacro with
  "This macro creates a scope that overrides the construction
of AmazonWebServiceClients by amazonica.

It relies on a dynamic var to override these behaviors, so
all caveats that appy to dynamic vars apply here.
Example:
(sandbox/with
  sandbox/always-nothing
  (amazonica.aws.ec2/run-instances {:image-id \"foo\" ...}))
"
  [client-middleware & body]
  `(let [mw# ~client-middleware]
     (with-client-middleware*
       {:amazon-client-fn     mw#
        :encryption-client-fn mw#
        :transfer-manager-fn  mw#}
       (fn [] ~@body))))
