(ns puppetlabs.trapperkeeper.services.webserver.jetty10-service
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.tools.logging :as log]
    [puppetlabs.i18n.core :as i18n]
    [puppetlabs.trapperkeeper.config :as tk-config]
    [puppetlabs.trapperkeeper.core :refer [defservice]]
    [puppetlabs.trapperkeeper.services :refer [get-service
                                               maybe-get-service
                                               service-context]]
    [puppetlabs.trapperkeeper.services.protocols.filesystem-watch-service
     :as watch-protocol]
    [puppetlabs.trapperkeeper.services.webserver.jetty10-config :as config]
    [puppetlabs.trapperkeeper.services.webserver.jetty10-core :as core]))

;; TODO: this should probably be moved to a separate jar that can be used as
;; a dependency for all webserver service implementations
(defprotocol WebserverService
  (add-context-handler [this base-path context-path] [this base-path context-path options])
  (add-ring-handler [this handler path] [this handler path options])
  (add-websocket-handler [this handlers path] [this handler path options])
  (add-servlet-handler [this servlet path] [this servlet path options])
  (add-war-handler [this war path] [this war path options])
  (add-proxy-route [this target path] [this target path options])
  (override-webserver-settings! [this overrides] [this server-id overrides])
  (get-registered-endpoints [this] [this server-id])
  (log-registered-endpoints [this] [this server-id])
  (join [this] [this server-id]))

(defservice jetty10-service
  "Provides a Jetty 10 web server as a service"
  WebserverService
  {:required [ConfigService]
   :optional [FilesystemWatchService]}
  (init [this context]
        (log/info (i18n/trs "Initializing web server(s)."))
        (let [config-service (get-service this :ConfigService)
              config (or (tk-config/get-in-config config-service [:webserver])
                         ;; Here for backward compatibility with existing projects
                         (tk-config/get-in-config config-service [:jetty])
                         {})]
          (config/validate-config config)
          (core/init! context config)))

  (start [this context]
         (log/info (i18n/trs "Starting web server(s)."))
         (let [config-service (get-service this :ConfigService)
               config (or (tk-config/get-in-config config-service [:webserver])
                          ;; Here for backward compatibility with existing projects
                          (tk-config/get-in-config config-service [:jetty])
                          {})
               _ (log/debug (i18n/trs "Jetty server(s) starting with config: {0}" config))
               started-context (core/start! context config)]
           ;; Log started server(s) configuration(s) for debugging purposes
           (doseq [started-server-context (:jetty10-servers started-context)]
             ;; Server context data is in the form {:servername {:handlers <handlers>, ... }}
             (let [server-name (name (first started-server-context))
                   started-server ^org.eclipse.jetty.server.Server (:server (second started-server-context))
                   connectors (.getConnectors started-server)]
               (log/debug (i18n/trs "Jetty server {0} started with context: {1}" server-name (with-out-str (pprint started-server-context))))
               (log/debug (i18n/trs "Jetty server {0} started with URI: {1} Stop timeout: {2} milliseconds."
                                  server-name (.getURI started-server) (.getStopTimeout started-server)))
               (doseq [^org.eclipse.jetty.server.Connector connector connectors
                       connector-index (range 0 (count connectors))]
                 (let [connector-name (or (.getName connector) connector-index)]
                   (log/debug (i18n/trs "Jetty server {0} started with connector {1} with idle-timeout {2}." server-name connector-name (.getIdleTimeout connector)))
                   (log/debug (i18n/trs "Jetty server {0} started with connector {1} protocols: {2}." server-name connector-name (.getProtocols connector)))))))
           (if-let [filesystem-watcher-service
                    (maybe-get-service this :FilesystemWatchService)]
             (let [watcher (watch-protocol/create-watcher filesystem-watcher-service {:recursive false})]
               (doseq [server (:jetty10-servers started-context)]
                 (when-let [ssl-context-factory (-> server
                                                    second
                                                    :state
                                                    deref
                                                    :ssl-context-server-factory)]
                   (core/reload-crl-on-change! ssl-context-factory watcher)))
               (assoc started-context :watcher watcher))
             started-context)))

  (stop [this context]
        (log/info (i18n/trs "Shutting down web server(s)."))
        (doseq [key (keys (:jetty10-servers context))]
          (if-let [server (key (:jetty10-servers context))]
            (core/shutdown server)))
        context)

  (add-context-handler [this base-path context-path]
                       (core/add-context-handler! (service-context this) base-path context-path {}))

  (add-context-handler [this base-path context-path options]
                       (core/add-context-handler! (service-context this) base-path context-path options))

  (add-ring-handler [this handler path]
                    (core/add-ring-handler! (service-context this) handler path {}))

  (add-ring-handler [this handler path options]
                    (core/add-ring-handler! (service-context this) handler path options))

  (add-websocket-handler [this handlers path]
    (core/add-websocket-handler! (service-context this) handlers path {}))

  (add-websocket-handler [this handlers path options]
    (core/add-websocket-handler! (service-context this) handlers path options))

  (add-servlet-handler [this servlet path]
                       (core/add-servlet-handler! (service-context this) servlet path {}))

  (add-servlet-handler [this servlet path options]
                       (core/add-servlet-handler! (service-context this) servlet path options))

  (add-war-handler [this war path]
                   (core/add-war-handler! (service-context this) war path {}))

  (add-war-handler [this war path options]
                   (core/add-war-handler! (service-context this) war path options))

  (add-proxy-route [this target path]
                   (core/add-proxy-route! (service-context this) target path {}))

  (add-proxy-route [this target path options]
                   (core/add-proxy-route! (service-context this) target path options))

  (override-webserver-settings! [this overrides]
                                (let [s (core/get-server-context (service-context this) nil)]
                                  (core/override-webserver-settings! s overrides)))

  (override-webserver-settings! [this server-id overrides]
                                (let [s (core/get-server-context (service-context this) server-id)]
                                  (core/override-webserver-settings! s overrides)))

  (get-registered-endpoints [this]
                            (let [s (core/get-server-context (service-context this) nil)]
                              (core/get-registered-endpoints s)))

  (get-registered-endpoints [this server-id]
                            (let [s (core/get-server-context (service-context this) server-id)]
                              (core/get-registered-endpoints s)))
  (log-registered-endpoints [this]
                            (log/info (str (get-registered-endpoints this))))

  (log-registered-endpoints[this server-id]
                           (log/info (str (get-registered-endpoints this server-id))))

  (join [this]
        (let [s (core/get-server-context (service-context this) nil)]
          (core/join s)))

  (join [this server-id]
        (let [s (core/get-server-context (service-context this) server-id)]
          (core/join s))))
