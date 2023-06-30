(ns puppetlabs.trapperkeeper.services.webserver.jetty10-websockets
  (:import (clojure.lang IFn)
           (org.eclipse.jetty.websocket.api WebSocketAdapter Session)
           (org.eclipse.jetty.websocket.server JettyWebSocketServlet JettyWebSocketServletFactory JettyWebSocketCreator JettyServerUpgradeRequest JettyServerUpgradeResponse)
           (java.security.cert X509Certificate)
           (java.time Duration)
           (java.nio ByteBuffer))

  (:require [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.services.websocket-session :refer [WebSocketProtocol]]
            [schema.core :as schema]
            [puppetlabs.i18n.core :as i18n]))

(def WebsocketHandlers
  {(schema/optional-key :on-connect) IFn
   (schema/optional-key :on-error) IFn
   (schema/optional-key :on-close) IFn
   (schema/optional-key :on-text) IFn
   (schema/optional-key :on-bytes) IFn})

(defprotocol WebSocketSend
  (-send! [x ws] "How to encode content sent to the WebSocket clients"))

(extend-protocol WebSocketSend
  (Class/forName "[B")
  (-send! [ba ws]
    (-send! (ByteBuffer/wrap ba) ws))

  ByteBuffer
  (-send! [bb ws]
    (-> ^WebSocketAdapter ws .getRemote (.sendBytes ^ByteBuffer bb)))

  String
  (-send! [s ws]
    (-> ^WebSocketAdapter ws .getRemote (.sendString ^String s))))

(extend-protocol WebSocketProtocol
  WebSocketAdapter
  (send! [this msg]
    (-send! msg this))
  (close!
    ([this]
     (.. this (getSession) (close)))
    ([this code reason]
     (.. this (getSession) (close code reason))))
  (disconnect [this]
    (when-let [session (.getSession this)]
     (.disconnect session)))
  (remote-addr [this]
    (.. this (getSession) (getRemoteAddress)))
  (ssl? [this]
    (.. this (getSession) (getUpgradeRequest) (isSecure)))
  (peer-certs [this]
    (.. this (getCerts)))
  (request-path [this]
    (.. this (getRequestPath)))
  (idle-timeout! [this ms]
    (let [duration-from-ms (Duration/ofMillis ms)]
      (.. this (getSession) (setIdleTimeout ^Duration duration-from-ms))))
  (connected? [this]
    (. this (isConnected))))

(definterface CertGetter
  (^Object getCerts [])
  (^String getRequestPath []))

(defn no-handler
  [event & args]
  (log/debug (i18n/trs "No handler defined for websocket event ''{0}'' with args: ''{1}''"
                       event args)))

(schema/defn ^:always-validate proxy-ws-adapter :- WebSocketAdapter
  [handlers :- WebsocketHandlers
   x509certs :- [X509Certificate]
   requestPath :- String]
  (let [{:keys [on-connect on-error on-text on-close on-bytes]
         :or {on-connect (partial no-handler :on-connect)
              on-error   (partial no-handler :on-error)
              on-text    (partial no-handler :on-text)
              on-close   (partial no-handler :on-close)
              on-bytes   (partial no-handler :on-bytes)}} handlers]
    (proxy [WebSocketAdapter CertGetter] []
      (onWebSocketConnect [^Session session]
        (let [^WebSocketAdapter this this]
          (proxy-super onWebSocketConnect session))
        (on-connect this))
      (onWebSocketError [^Throwable e]
        (let [^WebSocketAdapter this this]
          (proxy-super onWebSocketError e))
        (on-error this e))
      (onWebSocketText [^String message]
        (let [^WebSocketAdapter this this]
          (proxy-super onWebSocketText message))
        (on-text this message))
      (onWebSocketClose [statusCode ^String reason]
        (let [^WebSocketAdapter this this]
          (proxy-super onWebSocketClose statusCode reason))
        (on-close this statusCode reason))
      (onWebSocketBinary [^bytes payload offset len]
        (let [^WebSocketAdapter this this]
          (proxy-super onWebSocketBinary payload offset len))
        (on-bytes this payload offset len))
      (getCerts [] x509certs)
      (getRequestPath [] requestPath))))

(schema/defn ^:always-validate proxy-ws-creator :- JettyWebSocketCreator
  [handlers :- WebsocketHandlers]
  (reify JettyWebSocketCreator
    (createWebSocket [_this ^JettyServerUpgradeRequest req ^JettyServerUpgradeResponse _res]
      (let [x509certs (vec (.. req (getCertificates)))
            requestPath (.. req (getRequestPath))]
        (proxy-ws-adapter handlers x509certs requestPath)))))

(schema/defn JettyWebSocketServletInstance :- JettyWebSocketServlet
  [handlers]
  (proxy [JettyWebSocketServlet] []
    (configure [^JettyWebSocketServletFactory factory]
        (.setCreator factory (proxy-ws-creator handlers)))))
