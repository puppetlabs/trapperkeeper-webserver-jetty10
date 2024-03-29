(ns puppetlabs.trapperkeeper.services.webserver.jetty10-service-handlers-test
  (:require [clojure.test :refer :all]
            [hato.client :as http]
            [hato.websocket :as ws-client]
            [puppetlabs.trapperkeeper.services.websocket-session :as ws-session]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.trapperkeeper.services.webserver.jetty10-service :refer :all]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.testutils.logging
             :refer [with-test-logging]]
            [puppetlabs.trapperkeeper.testutils.webserver :as testutils]
            [puppetlabs.trapperkeeper.testutils.webserver.common :refer :all]
            [schema.test :as schema-test])
  (:import (java.nio.file Files Paths)
           (java.nio.file.attribute FileAttribute)
           (javax.servlet ServletContextListener)
           (javax.servlet.http HttpServlet HttpServletRequest HttpServletResponse)
           (servlet SimpleServlet)))

(use-fixtures :once
  schema-test/validate-schemas
  testutils/assert-clean-shutdown)

(deftest static-content-test
  (with-test-logging
    (testing "static content context"
      (with-app-with-config app
        [jetty10-service]
        jetty-plaintext-config
        (let [s                   (get-service app :WebserverService)
              add-context-handler (partial add-context-handler s)
              path                "/resources"
              resource            "logback.xml"]
          (add-context-handler dev-resources-dir path)
          (let [response (http-get (str "http://localhost:8080" path "/" resource))]
            (is (= (:status response) 200))
            (is (= (:body response) (slurp (str dev-resources-dir resource))))))))

    (testing "static content context with add-context-handler-to"
      (with-app-with-config app
        [jetty10-service]
        jetty-multiserver-plaintext-config
        (let [s                      (get-service app :WebserverService)
              add-context-handler (partial add-context-handler s)
              path                   "/resources"
              resource               "logback.xml"]
          (add-context-handler dev-resources-dir path {:server-id :foo})
          (let [response (http-get (str "http://localhost:8085" path "/" resource))]
            (is (= (:status response) 200))
            (is (= (:body response) (slurp (str dev-resources-dir resource))))))))

    (testing "customization of static content context"
      (with-app-with-config app
        [jetty10-service]
        jetty-plaintext-config
        (let [s                   (get-service app :WebserverService)
              add-context-handler (partial add-context-handler s)
              path                "/resources"
              body                "Hey there"
              servlet-path        "/hey"
              servlet             (SimpleServlet. body "text/html" false)
              context-listeners   [(reify ServletContextListener
                                     (contextInitialized [this event]
                                       (doto (.addServlet (.getServletContext event) "simple" servlet)
                                         (.addMapping (into-array [servlet-path]))))
                                     (contextDestroyed [this event]))]]
          (add-context-handler dev-resources-dir path {:context-listeners context-listeners})
          (let [response (http-get (str "http://localhost:8080" path servlet-path))]
            (is (= (:status response) 200))
            (is (= "text/html;charset=utf-8" (get-in response [:headers "content-type"])))
            (is (= (:body response) body))))))))

(deftest add-context-handler-symlinks-test
  (with-test-logging
    (let [resource  "logback.xml"
          resource-link "logback-link.xml"
          logback (slurp (str dev-resources-dir resource))
          link (Paths/get (str dev-resources-dir resource-link) (into-array java.lang.String []))
          file (Paths/get resource (into-array java.lang.String []))]
      (try
        (Files/createSymbolicLink link file (into-array FileAttribute []))

        (testing "symlinks served when :follow-links is true"
          (with-app-with-config app
            [jetty10-service]
            jetty-plaintext-config
            (let [s (get-service app :WebserverService)
                  add-context-handler (partial add-context-handler s)
                  path "/resources"]
              (add-context-handler dev-resources-dir path {:follow-links true})
              (let [response (http-get (str "http://localhost:8080" path "/" resource))]
                (is (= (:status response) 200))
                (is (= (:body response) logback)))
              (let [response (http-get (str "http://localhost:8080" path "/" resource-link))]
                (is (= (:status response) 200))
                (is (= (:body response) logback))))))

        (testing "symlinks not served when :follow-links is false"
          (with-app-with-config app
            [jetty10-service]
            jetty-plaintext-config
            (let [s (get-service app :WebserverService)
                  add-context-handler (partial add-context-handler s)
                  path "/resources"]
              (add-context-handler dev-resources-dir path {:follow-links false})
              (let [response (http-get (str "http://localhost:8080" path "/" resource))]
                (is (= (:status response) 200))
                (is (= (:body response) logback)))
              (let [response (http-get (str "http://localhost:8080" path "/" resource-link))]
                (is (= (:status response) 404))))))

        (finally
          (Files/delete link))))))

(deftest servlet-test
  (with-test-logging
    (testing "request to servlet over http succeeds"
      (with-app-with-config app
        [jetty10-service]
        jetty-plaintext-config
        (let [s                   (get-service app :WebserverService)
              add-servlet-handler (partial add-servlet-handler s)
              body                "Hey there"
              path                "/hey"
              servlet             (SimpleServlet. body "text/html" false)]
          (add-servlet-handler servlet path)
          (let [response (http-get
                           (str "http://localhost:8080" path))]
            (is (= (:status response) 200))
            (is (= "text/html;charset=utf-8" (get-in response [:headers "content-type"])))
            (is (= (:body response) body))))))

    (testing "request to servlet with json content over http succeeds"
      (with-app-with-config app
        [jetty10-service]
        jetty-plaintext-config
        (let [s (get-service app :WebserverService)
              add-servlet-handler (partial add-servlet-handler s)
              body                "{}"
              path                "/hey"
              servlet             (SimpleServlet. body "application/json;charset=utf-8" true)]
          (add-servlet-handler servlet path)
          (let [response (http-get (str "http://localhost:8080" path))]
            (is (= (:status response) 200))
            (is (= "application/json;charset=utf-8" (get-in response [:headers "content-type"])))
            (is (= (:body response) body))))))

    (testing "request to servlet over http succeeds with add-servlet-handler-to"
      (with-app-with-config app
        [jetty10-service]
        jetty-multiserver-plaintext-config
        (let [s                      (get-service app :WebserverService)
              add-servlet-handler    (partial add-servlet-handler s)
              body                   "Hey there"
              path                   "/hey"
              servlet                (SimpleServlet. body "text/html" false)]
          (add-servlet-handler servlet path {:server-id :foo})
          (let [response (http-get
                           (str "http://localhost:8085" path))]
            (is (= (:status response) 200))
            (is (= "text/html;charset=utf-8" (get-in response [:headers "content-type"])))
            (is (= (:body response) body))))))

    (testing "request to servlet initialized with empty param succeeds"
      (with-app-with-config app
        [jetty10-service]
        jetty-plaintext-config
        (let [s                   (get-service app :WebserverService)
              add-servlet-handler (partial add-servlet-handler s)
              body                "Hey there"
              path                "/hey"
              servlet             (SimpleServlet. body "text/html" false)]
          (add-servlet-handler servlet path {:servlet-init-params {}})
          (let [response (http-get (str "http://localhost:8080" path))]
            (is (= (:status response) 200))
            (is (= "text/html;charset=utf-8" (get-in response [:headers "content-type"])))
            (is (= (:body response) body))))))

    (testing "request to servlet initialized with non-empty params succeeds"
      (with-app-with-config app
        [jetty10-service]
        jetty-plaintext-config
        (let [s                   (get-service app :WebserverService)
              add-servlet-handler (partial add-servlet-handler s)
              body                "Hey there"
              path                "/hey"
              init-param-one      "value of init param one"
              init-param-two      "value of init param two"
              servlet             (SimpleServlet. body "text/html" false)]
          (add-servlet-handler servlet
                               path
                               {:servlet-init-params {"init-param-one" init-param-one
                                                      "init-param-two" init-param-two}})
          (let [response (http-get
                           (str "http://localhost:8080" path "/init-param-one"))]
            (is (= (:status response) 200))
            (is (= "text/html;charset=utf-8" (get-in response [:headers "content-type"])))
            (is (= (:body response) init-param-one)))
          (let [response (http-get
                           (str "http://localhost:8080" path "/init-param-two"))]
            (is (= (:status response) 200))
            (is (= "text/html;charset=utf-8" (get-in response [:headers "content-type"])))
            (is (= (:body response) init-param-two))))))))

(deftest websocket-test
  (with-test-logging
    (testing "Websocket handlers"
      (with-app-with-config app
        [jetty10-service]
        jetty-plaintext-config
        (let [s                     (get-service app :WebserverService)
              add-websocket-handler (partial add-websocket-handler s)
              path                  "/test"
              connected              (atom 0)
              server-messages        (atom [])
              server-binary-messages (atom [])
              client-messages        (atom [])
              client-binary-messages (atom [])
              client-request-path    (atom "")
              client-remote-addr     (atom "")
              client-is-ssl          (atom nil)
              closed-request-path    (atom "")
              binary-client-message  (promise)
              closed                 (promise)
              handlers               {:on-connect (fn [ws]
                                                    (ws-session/send! ws "Hello client!")
                                                    (swap! connected inc)
                                                    (reset! client-request-path (ws-session/request-path ws))
                                                    (reset! client-remote-addr (.. (ws-session/remote-addr ws) (toString)))
                                                    (reset! client-is-ssl (ws-session/ssl? ws)))
                                      :on-text    (fn [ws text]
                                                    (ws-session/send! ws (str "You said: " text))
                                                    (swap! server-messages conj text))
                                      :on-bytes   (fn [ws bytes offset len]
                                                    (let [as-vec (vec bytes)]
                                                      (ws-session/send! ws (byte-array (reverse as-vec)))
                                                      (swap! server-binary-messages conj as-vec)))
                                      :on-error   (fn [ws error])  ;; TODO - Add test for on-error behaviour
                                      :on-close   (fn [ws code reason]
                                                    (swap! connected dec)
                                                    (reset! closed-request-path (ws-session/request-path ws))
                                                    (deliver closed true))}]
          (add-websocket-handler handlers path)
          (let [socket @(ws-client/websocket (str "ws://localhost:8080" path "/foo")
                                             {:on-message (fn [ws msg last?]
                                                            ;; I don't know what it is but this homegrown instance? test
                                                            ;; works, but (instance? java.nio.HeapCharBuffer msg) seemingly
                                                            ;; crashes this function without exception
                                                            (if (= (str (type msg)) "class java.nio.HeapCharBuffer")
                                                              (swap! client-messages conj (str msg))
                                                              ;; One would think there would be a java class function for this,
                                                              ;; but I didn't see anything obvious, there is no backing array to call
                                                              ;; https://resources.mpi-inf.mpg.de/d5/teaching/ss05/is05/javadoc/java/nio/CharBuffer.html#array()
                                                              (let [char-buffer-array (loop [i 0
                                                                                             array []]
                                                                                        (if (= i 5)
                                                                                          array
                                                                                          (recur (+ i 1) (conj array (.get msg i)))))]
                                                                (swap! client-binary-messages conj char-buffer-array)
                                                                (deliver binary-client-message true))))
                                              :http-client (http/build-http-client
                                                            {:ssl-context {:insecure? true}})})]
            (ws-client/send! socket "Hello websocket handler")
            (ws-client/send! socket "You look dandy")
            (ws-client/send! socket (byte-array [2 1 2 3 3]))
            (deref binary-client-message)
            (is (= @connected 1))
            (is (= @client-request-path "/foo"))
            (is (re-matches #"/127\.0\.0\.1:\d+" @client-remote-addr))
            (is (= @client-is-ssl false))
            (ws-client/close! socket)
            (deref closed)
            (is (= @closed-request-path "/foo"))
            (is (= @connected 0))
            (is (= @server-binary-messages [[2 1 2 3 3]]))
            (is (= @client-binary-messages [[3 3 2 1 2]]))

            (is (= @client-messages ["Hello client!"
                                     "You said: Hello websocket handler"
                                     "You said: You look dandy"]))
            (is (= @server-messages ["Hello websocket handler"
                                     "You look dandy"]))))))

    (testing "can close without supplying a reason"
      (with-app-with-config app
        [jetty10-service]
        jetty-plaintext-config
        (let [s                     (get-service app :WebserverService)
              add-websocket-handler (partial add-websocket-handler s)
              path                  "/test"
              closed                 (promise)
              handlers               {:on-connect (fn [ws] (ws-session/close! ws))}]
          (add-websocket-handler handlers path)
          (let [_socket @(ws-client/websocket (str "ws://localhost:8080" path)
                                              {:on-close (fn [ws code _reason] (deliver closed code))
                                               :http-client (http/build-http-client
                                                             {:ssl-context {:insecure? true}})})]
            ;; 1000 is for normal closure https://tools.ietf.org/html/rfc6455#section-7.4.1
            (is (= 1000 @closed))))))

    (testing "can close with reason"
      (with-app-with-config app
        [jetty10-service]
        jetty-plaintext-config
        (let [s                     (get-service app :WebserverService)
              add-websocket-handler (partial add-websocket-handler s)
              path                  "/test"
              closed                 (promise)
              handlers               {:on-connect (fn [ws] (ws-session/close! ws 4000 "Bye"))}]
          (add-websocket-handler handlers path)
          (let [_socket @(ws-client/websocket (str "ws://localhost:8080" path)
                                              {:on-close (fn [ws code reason] (deliver closed [code reason]))
                                               :http-client (http/build-http-client
                                                             {:ssl-context {:insecure? true}})})]
            (is (= [4000 "Bye"] @closed))))))))

(deftest war-test
  (with-test-logging
    (testing "WAR support"
      (with-app-with-config app
        [jetty10-service]
        jetty-plaintext-config
        (let [s               (get-service app :WebserverService)
              add-war-handler (partial add-war-handler s)
              path            "/test"
              war             "helloWorld.war"]
          (add-war-handler (str dev-resources-dir war) path)
          (let [response (http-get (str "http://localhost:8080" path "/hello"))]
            (is (= (:status response) 200))
            (is (= (:body response)
                   "<html>\n<head><title>Hello World Servlet</title></head>\n<body>Hello World!!</body>\n</html>\n"))))))

    (testing "WAR support with add-war-handler-to"
      (with-app-with-config app
        [jetty10-service]
        jetty-multiserver-plaintext-config
        (let [s                  (get-service app :WebserverService)
              add-war-handler    (partial add-war-handler s)
              path               "/test"
              war                "helloWorld.war"]
          (add-war-handler (str dev-resources-dir war) path {:server-id :foo})
          (let [response (http-get (str "http://localhost:8085" path "/hello"))]
            (is (= (:status response) 200))
            (is (= (:body response)
                   "<html>\n<head><title>Hello World Servlet</title></head>\n<body>Hello World!!</body>\n</html>\n"))))))))

(deftest endpoints-test
  (testing "Retrieve all endpoints"
    (with-test-logging
      (with-app-with-config app
        [jetty10-service]
        jetty-plaintext-config
        (let [s                        (get-service app :WebserverService)
              path-context             "/ernie"
              path-context2            "/gonzo"
              path-context3            "/goblinking"
              path-ring                "/bert"
              path-servlet             "/foo"
              path-war                 "/bar"
              path-proxy               "/baz"
              path-websocket           "/quux"
              get-registered-endpoints (partial get-registered-endpoints s)
              add-context-handler      (partial add-context-handler s)
              add-ring-handler         (partial add-ring-handler s)
              add-servlet-handler      (partial add-servlet-handler s)
              add-war-handler          (partial add-war-handler s)
              add-proxy-route          (partial add-proxy-route s)
              add-websocket-handler    (partial add-websocket-handler s)
              ring-handler             (fn [req] {:status 200 :body "Hi world"})
              body                     "This is a test"
              servlet                  (SimpleServlet. body "text/html" false)
              context-listeners        [(reify ServletContextListener
                                          (contextInitialized [this event]
                                            (doto (.addServlet (.getServletContext event) "simple" servlet)
                                              (.addMapping (into-array [path-servlet]))))
                                          (contextDestroyed [this event]))]
              war                      "helloWorld.war"
              websocket-handlers       {:on-connect (fn [ws])}
              target                   {:host "0.0.0.0"
                                        :port 9000
                                        :path "/ernie"}
              target2                  {:host "localhost"
                                        :port 10000
                                        :path "/kermit"}]
          (add-context-handler dev-resources-dir path-context)
          (add-context-handler dev-resources-dir path-context2 {:context-listeners []})
          (add-context-handler dev-resources-dir path-context3 {:context-listeners context-listeners})
          (add-ring-handler ring-handler path-ring)
          (add-servlet-handler servlet path-servlet)
          (add-war-handler (str dev-resources-dir war) path-war)
          (add-proxy-route target path-proxy)
          (add-proxy-route target2 path-proxy {})
          (add-websocket-handler websocket-handlers path-websocket)
          (let [endpoints (get-registered-endpoints)]
            (is (= endpoints {"/ernie" [{:type :context :base-path dev-resources-dir
                                         :context-listeners []}]
                              "/gonzo" [{:type :context :base-path dev-resources-dir
                                         :context-listeners []}]
                              "/goblinking" [{:type :context :base-path dev-resources-dir
                                              :context-listeners context-listeners}]
                              "/bert" [{:type :ring}]
                              "/foo" [{:type :servlet :servlet (type servlet)}]
                              "/bar" [{:type :war :war-path (str dev-resources-dir war)}]
                              "/baz" [{:type :proxy :target-host "0.0.0.0" :target-port 9000
                                       :target-path "/ernie"}
                                      {:type :proxy :target-host "localhost" :target-port 10000
                                       :target-path "/kermit"}]
                              "/quux" [{:type :websocket}]})))))))


  (testing "Log endpoints"
    (with-test-logging
      (with-app-with-config app
        [jetty10-service]
        jetty-multiserver-plaintext-config
        (let [s                        (get-service app :WebserverService)
              log-registered-endpoints (partial log-registered-endpoints s)
              add-ring-handler         (partial add-ring-handler s)
              ring-handler             (fn [req] {:status 200 :body "Hi world"})
              path-ring                "/bert"]
          (add-ring-handler ring-handler path-ring)
          (log-registered-endpoints)
          (is (logged? #"^\{\"\/bert\" \[\{:type :ring\}\]\}$"))
          (is (logged? #"^\{\"\/bert\" \[\{:type :ring\}\]\}$" :info)))))))

(deftest trailing-slash-redirect-test
  (with-test-logging
    (testing "redirects when no trailing slash is present are disabled by default"
      (with-app-with-config app
        [jetty10-service]
        jetty-plaintext-config
        (let [s                (get-service app :WebserverService)
              add-ring-handler (partial add-ring-handler s)
              ring-handler     (fn [req] {:status 200 :body "Hi world"})
              path             "/hello"]
          (add-ring-handler ring-handler path)
          (let [response (http-get "http://localhost:8080/hello" {:as :text
                                                                  :follow-redirects false})]
            (is (= (:status response) 200))
            (is (= (:body response) "Hi world"))
            (is (= (get-in response [:opts :url]) "http://localhost:8080/hello"))))))

    (testing "redirects when no trailing slash is present and option is enabled"
      (with-app-with-config app
        [jetty10-service]
        jetty-plaintext-config
        (let [s                (get-service app :WebserverService)
              add-ring-handler (partial add-ring-handler s)
              ring-handler     (fn [req] {:status 200 :body "Hi world"})
              path             "/hello"]
          (add-ring-handler ring-handler path {:redirect-if-no-trailing-slash true})
          (let [response (http-get "http://localhost:8080/hello" {:as :text
                                                                  :follow-redirects false})]
            (is (= (:status response) 302))
            (is (= (get-in response [:headers "location"]) "http://localhost:8080/hello/"))
            (is (= (get-in response [:opts :url]) "http://localhost:8080/hello"))))))))

(defn ring-handler-echoing-request-uri
  []
  (fn [req] {:status 200 :body (:uri req)}))

(deftest normalize-request-uri-enabled-for-ring-handler-test
  (with-test-logging
    (testing "when uri request normalization enabled for ring handler"
      (with-app-with-config
       app
       [jetty10-service]
       jetty-plaintext-config
       (let [webserver-service (get-service app :WebserverService)]
         (add-ring-handler webserver-service
                           (ring-handler-echoing-request-uri)
                           "/hello"
                           {:normalize-request-uri true})
         (testing "uri with encoded characters is properly decoded"
           (let [response (http-get "http://localhost:8080/hello%2f%2f%77o%72l%64"
                                    {:as :text})]
             (is (= (:status response) 200))
             (is (= (:body response) "/hello/world"))))
         (testing "uri with relative path above root is rejected"
           (let [response
                 (http-get
                  "http://localhost:8080/hello/world/%2E%2E/%2E%2E/%2E%2E/cleveland"
                  {:as :text})]
             (is (= (:status response) 400))))
         (testing "uri with relative path below root is rejected"
           (let [response (http-get
                           "http://localhost:8080/hello/world/%2E%2E/cleveland"
                           {:as :text})]
             (is (= (:status response) 400)))))))))

(deftest normalize-request-uri-disabled-for-ring-handler-test
  (with-test-logging
    (testing "when uri request normalization disabled for ring handler"
      (with-app-with-config
       app
       [jetty10-service]
       jetty-plaintext-config
       (let [webserver-service (get-service app :WebserverService)]
         (add-ring-handler webserver-service
                           (ring-handler-echoing-request-uri)
                           "/hello"
                           {:normalize-request-uri false})
         (testing "uri with encoded characters is properly decoded"
           (let [response (http-get "http://localhost:8080/hello%2f%2f%77o%72l%64"
                                    {:as :text})]
             (is (= (:status response) 200))
             (is (= (:body response) "/hello%2f%2f%77o%72l%64"))))
         (testing "uri with relative path above root is rejected"
           (let [response
                 (http-get
                  "http://localhost:8080/hello/world/%2E%2E/%2E%2E/%2E%2E/cleveland"
                  {:as :text})]
             (is (= (:status response) 400))))
         (testing "uri with relative path below root is resolved"
           (let [response (http-get
                           "http://localhost:8080/hello/world/%2E%2E/cleveland"
                           {:as :text})]
             (is (= (:body response) "/hello/world/%2E%2E/cleveland")))))))))

(defn servlet-echoing-request-uri
  []
  (proxy [HttpServlet] []
    (doGet [^HttpServletRequest request
            ^HttpServletResponse response]
      (-> response
          (.getWriter)
          (.print (.getRequestURI request)))
      (.setStatus response 200))))

(deftest normalize-request-uri-enabled-for-servlet-test
  (with-test-logging
    (testing "when uri request normalization enabled for servlet"
      (with-app-with-config
       app
       [jetty10-service]
       jetty-plaintext-config
       (let [webserver-service (get-service app :WebserverService)]
         (add-servlet-handler
          webserver-service
          (servlet-echoing-request-uri)
          "/hello"
          {:normalize-request-uri true})
         (testing "uri with encoded characters is properly decoded"
           (let [response (http-get "http://localhost:8080/hello%2f%2f%77o%72l%64"
                                    {:as :text})]
             (is (= (:status response) 200))
             (is (= (:body response) "/hello/world"))))
         (testing "uri with relative path above root is rejected"
           (let [response
                 (http-get
                  "http://localhost:8080/hello/world/%2E%2E/%2E%2E/%2E%2E/cleveland"
                  {:as :text})]
             (is (= (:status response) 400))))
         (testing "uri with relative path below root is rejected"
           (let [response (http-get
                           "http://localhost:8080/hello/world/%2E%2E/cleveland"
                           {:as :text})]
             (is (= (:status response) 400)))))))))

(deftest normalize-request-uri-disabled-for-servlet-test
  (with-test-logging
    (testing "when uri request normalization disabled for servlet"
      (with-app-with-config
       app
       [jetty10-service]
       jetty-plaintext-config
       (let [webserver-service (get-service app :WebserverService)]
         (add-servlet-handler
          webserver-service
          (servlet-echoing-request-uri)
          "/hello"
          {:normalize-request-uri false})
         (testing "uri with encoded characters is not decoded"
           (let [response (http-get "http://localhost:8080/hello%2f%2f%77o%72l%64"
                                    {:as :text})]
             (is (= (:status response) 200))
             (is (= (:body response) "/hello%2f%2f%77o%72l%64"))))
         (testing "uri with relative path above root is rejected"
           (let [response
                 (http-get
                  "http://localhost:8080/hello/world/%2E%2E/%2E%2E/%2E%2E/cleveland"
                  {:as :text})]
             (is (= (:status response) 400))))
         (testing "uri with relative path below root is resolved"
           (let [response (http-get
                           "http://localhost:8080/hello/world/%2E%2E/cleveland"
                           {:as :text})]
             (is (= (:status response) 200))
             (is (= (:body response) "/hello/world/%2E%2E/cleveland")))))))))
