## unreleased changes

## 1.0.4
* Added additional trace level logging to help diagnose issues
* Removed some use of reflection by applying type metadata tags
* Avoided some nil pointer dereferences in the disconnect case.

## 1.0.3
* Added Websocket event trace logging, removed logback 1.2.x version pins, clj-parent now specifies 1.3.x dependencies.

## 1.0.2
* Updated to Jetty 10.0.18, added ClosureLatchSyncer interface Jetty WebSocketAdapter object to sync closure of websocket client and server.

## 1.0.1
* Added ExecutionException handling when shutting down the server. Corrected WebSocketProtocol idle-timeout! function to produce a Duration.

## 1.0.0
* Initial release transitioning repository from Jetty 9 to Jetty 10, everything appears to be in working order but more production testing is needed and a subsequent release may be in short order.
