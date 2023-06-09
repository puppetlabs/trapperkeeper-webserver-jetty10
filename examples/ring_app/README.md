# Simple Web Service Example

This example demonstrates how to create a simple set of web services which both depend upon a hit counter service for
generating content. When run, this code will attach two endpoints, `/bert` and `/ernie` which will generate a simple
block of HTML that displays separate hit counters for each service.

All code needed to execute this example is located in `examples/ring_app`. The Clojure code is
contained in the `example_services.clj` file.

And now, a few quick housekeeping items before we get to the code...

## Launching trapperkeeper and running the app

To start up _trapperkeeper_ and launch the sample application, use the following _lein_ command while in the
_trapperkeeper-webserver-jetty10_ home directory:

```sh
lein trampoline run --config examples/ring_app/ring-example.conf \
                    --bootstrap-config examples/ring_app/bootstrap.cfg
```

Once _trapperkeeper_ is running, point your browser to either http://localhost:8080/ernie or http://localhost:8080/bert
to see the ring handlers and hit counter in action.

As you can see from the command line there are two configuration files needed to launch _trapperkeeper_.

### The `bootstrap.cfg` file

The bootstrap config file contains a list of services that _trapperkeeper_ will load up and make available. They are
listed as fully-qualified Clojure namespaces and service names. For this example the bootstrap.cfg looks like this:

```
puppetlabs.trapperkeeper.services.webserver.jetty10-service/jetty10-service
examples.ring-app.example-services/count-service
examples.ring-app.example-services/bert-service
examples.ring-app.example-services/ernie-service
```

This configuration indicates the jetty10 `WebserverService` is to be loaded, as
well as the three new services defined in the `example_services.clj` file.

### The `ring-example.conf` configuration file

For the application configuration, a file called `ring-example.conf` provides the most minimal
configuration of the webserver-service, which is simply the port the service will be listening
on and also a `logging-config` key which contains a path to a logback config file which defines
the logging configuration.

```
global {
    # Points to a logback config file
    logging-config: examples/ring_app/logback.xml
}

webserver {
    # Port to listen on for clear-text HTTP.
    port: 8080
}
```

### Debug mode

There is a debugging statement inside the count-service which displays the state of the counter when it is
to be incremented. To turn on debugging logging pass in the `--debug` option on the command line, like so:

```sh
lein trampoline run --config examples/ring_app/ring-example.conf \
                    --bootstrap-config examples/ring_app/bootstrap.cfg \
                    --debug
```

When run you will see debug output any time you hit the hit-counting endpoint. This is the equivalent of setting the
logback root logger to `DEBUG` instead of `INFO`, and will override whatever log level the root logger is set to.

## Defining the Services

And now, without further ado, let's look at some code!

### Define the _hit count_ service

First we will need to define the hit counter service, which will later be used by the web services to show users which
visitor number they are. It is entirely expressed with this code:

```clj
(def ^{:private true} hit-count (atom {}))

(defn- inc-and-get
  "Increments the hit count for the provided endpoint and returns the new hit count."
  [endpoint]
  {:pre [(string? endpoint)]
   :post [(integer? %) (> % 0)]}

  (let [new-hit-counts (swap! hit-count #(assoc % endpoint (cond (contains? % endpoint)
                                                             (inc (% endpoint)) :else 1)))]

    (log/debug "Incrementing hit count for" endpoint "from"
               (dec (new-hit-counts endpoint)) "to" (new-hit-counts endpoint))

    (new-hit-counts endpoint)))

(defprotocol CountService
  (inc-and-get [this endpoint]))

(defservice count-service
  "This is a simple service which simply keeps a counter. It contains one function, inc-and-get, which
   increments the count and returns it."
  ;; Here we specify the service's protocol
  CountService
  ;; This vector declares the service's dependencies on other services and their functions,
  []
  ;; Implement the `init` function from the `Lifecycle` protocol to

  ;; initialize state:
  (init [this context]
    (assoc context :hit-counts (atom {})))
  ;; Implement the inc-and-get function.
  (inc-and-get [this endpoint]
    (inc-and-get* ((service-context this) :hit-counts) endpoint)))
```

The `defservice` macro is used to define a _trapperkeeper_ service and it is located in the
`puppetlabs._trapperkeeper_.core` namespace.

For more info on how the `defservice` macro works, see the
[`defservice` section of the trapperkeeper docs](https://github.com/puppetlabs/trapperkeeper/tree/0.3.0#defservice)

The `inc-and-get` function will keep a tally of hit counts for a provided endpoint. It
is later exported with the last form in the service definition which is a map of this service's function names to the
actual functions which do all the work.

### Define the _bert_ service

The `bert-service` is a more interesting service which utilizes the `webserver` service to create HTTP
responses to requests made to specific endpoints, and is defined here:

```clj
(defn- success-response
  "Return a ring response map containing a HTTP response code of 200 (OK) and HTML which displays the hitcount on this
   endpoint as well as all the data provided by Ring."
  [hit-count req]
  {:status 200
   :body (str "<h1>Hello from http://" (:server-name req) ":" (:server-port req) (:uri req) "</h1>"
              (if (:debug? req) "<h3>DEBUGGING ENABLED!</h3>" "")
              "<p>You are visitor number " hit-count ".</p>"
              "<pre>" (pprint-to-string req) "</pre>")})

(defn- ring-handler
  "Executes the inc-and-get command and passes it into success-reponse which generates a ring response."
  [inc-and-get endpoint req]
  (success-response (inc-and-get endpoint) req))

(defservice bert-service
  "This is the bert web service. The Clojure web application library, Ring, is used to create simple
   responses to an endpoint. It depends on the count-service above to use as a primitive hit counter.
   See https://github.com/ring-clojure/ring for documentation on Ring."

  ;; This service needs functionality from the webserver service, and the count service.
  [[:WebserverService add-ring-handler]
   [:CountService inc-and-get]]

  ;; Implement the `init` lifecycle function to register the ring handler
  (init [this context]
    (let [endpoint "/bert"]
      (add-ring-handler (partial ring-handler inc-and-get endpoint) endpoint))
    context)

  (stop [this context]
    (log/info "Bert service shutting down")
    context))
```

The general structure of this service is similar to the _hit count_ service.

Since this service requires the use of functionality from other services, the dependency list contains two
dependent services and the functions that are required from each. The element containing
`[:WebserverService add-ring-handler]` states that the `add-ring-handler` function from the
`:WebserverService` is needed by this service. And, of course, we also need to pull in the `inc-and-get` function
from the _hit count_ service previously defined. This is accomplished by the `[:CountService inc-and-get]` dependency
list item.

#### Ring handlers

In the body of the service definition is a call to the `add-ring-handler` function. This function takes two
parameters, the first being a _ring handler_ which is, essentially, a function which takes a `request` data map as a
single parameter and returns a map containing different parts of an HTTP response.  The second parameter to
`add-ring-handler` is the base endpoint that the handler is attached to.

In this example, a partial function is created from the `ring-handler` function which is passed an endpoint to operate
on and the `inc-and-get` function from the _hit count_ service which generates the hit count.

See https://github.com/ring-clojure/ring for further documentation on the Ring API.

### Define the _ernie_ service

The _ernie_ service is very similar to the _bert_ service, but also leverages
another bit of built-in _trapperkeeper_ functionality: the `:ConfigService`.

This service can be specified as a dependency, and provides functions that can be
used to retrieve user-specified configuration values.  In this case, we've added an `example`
section to the `ring-example.conf` file, and specified a setting `ernie-url-prefix`
that can be used to control the URL prefix where the `ernie-service` will
be available in the web server.

The config service also provides a top-level config setting named `:debug`, which
is a boolean that reflects whether or not the user launched _trapperkeeper_ in
debug mode.  In the `ernie-service` we use a simple ring middleware function
to inject that value into the ring request map, so that it can be checked by
the ring handler.

```clj
(defn debug-middleware
  "Ring middleware to add the :debug configuration value to the request map."
  [app debug?]
  (fn [req]
    (app (assoc req :debug? debug?))))

(defservice ernie-service
  "This is the ernie service which operates on the /ernie endpoint. It is essentially identical to the bert service."
  [[:WebserverService add-ring-handler]
   [:CountService inc-and-get]
   [:ConfigService get-in-config]]

  (init [this context]
    (let [endpoint (get-in-config [:example :ernie-url-prefix])
          ring-handler (-> (partial ring-handler inc-and-get endpoint)
                           (debug-middleware (get-in-config [:debug])))]
      (add-ring-handler ring-handler endpoint))
    context)

  (stop [this context]
    (log/info "Ernie service shutting down")
    context))
```

This means that you can change the URL of the `ernie-service` simply by editing
the configuration file.

## Logging

At startup, _trapperkeeper_ will configure the logging system based on a logback configuration
file.  This means that your services can all just dive run it and call the
logging functions available in `clojure.tools.logging` without worrying about configuration.

### The `logback.xml` file

A minimal `logback.xml` file is provided in this example to demonstrate how to configure logging in
_trapperkeeper_.

```xml
<configuration scan="true">
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d %-5p [%c{2}] %m%n</pattern>
        </encoder>
    </appender>

    <root level="info">
        <appender-ref ref="STDOUT" />
    </root>
</configuration>
```

See http://logback.qos.ch/manual/configuration.html for documentation on how to configure logback.
