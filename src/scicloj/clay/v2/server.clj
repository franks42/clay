(ns scicloj.clay.v2.server
  (:require [babashka.fs :as fs]
            [clojure.java.browse :as browse]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [hiccup.page]
            [org.httpkit.server :as httpkit]
            [ring.util.mime-type :as mime-type]
            [ring.middleware.params :refer [wrap-params]]
            [scicloj.clay.v2.server.state :as server.state]
            [scicloj.clay.v2.util.time :as time]
            [clojure.string :as str]
            [cognitect.transit :as transit]
            [hiccup.core :as hiccup])
  (:import (java.net ServerSocket)))

(def default-port 1971)

;; Forward declarations for functions used before definition
(declare html-uri->source-path wrap-html cleanup-expired-states!)

(defonce *clients (atom #{}))

(defn broadcast! [msg]
  (doseq [ch @*clients]
    (httpkit/send! ch msg)))

;; =============================================================================
;; State Management for Parameterized Notebooks
;; =============================================================================

;; Configuration for state management
(def default-state-config
  {:state-ttl-hours 24           ; How long states persist
   :cleanup-interval-ms 3600000  ; Background cleanup frequency (1 hour)
   :max-states 10000})           ; Maximum number of states to store

(defonce *state-config (atom default-state-config))

;; Stores parameter states for parameterized notebook requests.
;; Maps UUID -> {:params {...} :expires <timestamp>}
(defonce *states (atom {}))

;; Background cleanup task executor
(defonce *cleanup-executor (atom nil))

(defn generate-state-id
  "Generate a cryptographically secure random state ID."
  []
  (let [uuid (java.util.UUID/randomUUID)]
    (str uuid)))

(defn create-state!
  "Create a new state with the given params and return the state-id.
   Respects max-states limit by cleaning up expired states first,
   then removing oldest if still over limit."
  ([params] (create-state! params (:state-ttl-hours @*state-config)))
  ([params ttl-hours]
   (let [max-states (:max-states @*state-config)]
     ;; Cleanup expired states first if approaching limit
     (when (>= (count @*states) max-states)
       (cleanup-expired-states!)
       ;; If still over limit, remove oldest states
       (when (>= (count @*states) max-states)
         (let [sorted-by-expires (->> @*states
                                      (sort-by (comp :expires val))
                                      (take (- (count @*states) (dec max-states)))
                                      (map first))]
           (swap! *states #(apply dissoc % sorted-by-expires)))))
     (let [id (generate-state-id)
           expires (+ (System/currentTimeMillis)
                      (* ttl-hours 3600000))]
       (swap! *states assoc id {:params params :expires expires})
       id))))

(defn get-state
  "Get state by ID. Returns nil if not found or expired."
  [id]
  (when-let [state (get @*states id)]
    (if (< (System/currentTimeMillis) (:expires state))
      state
      (do
        ;; Lazy cleanup of expired state
        (swap! *states dissoc id)
        nil))))

(defn cleanup-expired-states!
  "Remove all expired states from storage."
  []
  (let [now (System/currentTimeMillis)
        before-count (count @*states)]
    (swap! *states
           (fn [states]
             (->> states
                  (filter (fn [[_ state]] (< now (:expires state))))
                  (into {}))))
    (let [after-count (count @*states)
          removed (- before-count after-count)]
      (when (pos? removed)
        (println "State cleanup: removed" removed "expired states," after-count "remaining")))))

(defn start-cleanup-task!
  "Start background task to periodically clean up expired states."
  []
  (when-let [existing @*cleanup-executor]
    (.shutdown existing))
  (let [interval-ms (:cleanup-interval-ms @*state-config)
        executor (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)]
    (.scheduleAtFixedRate executor
                          (fn []
                            (try
                              (cleanup-expired-states!)
                              (catch Exception e
                                (println "Error in state cleanup task:" (.getMessage e)))))
                          interval-ms
                          interval-ms
                          java.util.concurrent.TimeUnit/MILLISECONDS)
    (reset! *cleanup-executor executor)
    (println "Started state cleanup task (interval:" (/ interval-ms 60000) "minutes)")))

(defn stop-cleanup-task!
  "Stop the background cleanup task."
  []
  (when-let [executor @*cleanup-executor]
    (.shutdown executor)
    (reset! *cleanup-executor nil)
    (println "Stopped state cleanup task")))

(defn configure-state-management!
  "Update state management configuration.
   Options:
   - :state-ttl-hours - How long states persist (default 24)
   - :cleanup-interval-ms - Cleanup frequency in ms (default 3600000 = 1 hour)
   - :max-states - Maximum states to store (default 10000)"
  [opts]
  (swap! *state-config merge opts)
  (println "State management configured:" @*state-config))

;; =============================================================================
;; JavaScript for Link-to-POST Conversion
;; =============================================================================

(defn link-to-post-script
  "JavaScript that converts same-origin links with query params to POST requests.
   This keeps all parameters out of URLs for privacy."
  []
  "<script type=\"text/javascript\">
// Clay: Convert same-origin links to POST for parameter privacy
document.addEventListener('click', (e) => {
  const link = e.target.closest('a');
  if (link && link.href && link.href.includes('?')) {
    try {
      const url = new URL(link.href, window.location.href);
      // Only convert same-origin links (Clay server)
      if (url.origin === window.location.origin) {
        e.preventDefault();
        const form = document.createElement('form');
        form.method = 'POST';
        form.action = url.pathname;
        form.style.display = 'none';
        url.searchParams.forEach((value, key) => {
          const input = document.createElement('input');
          input.type = 'hidden';
          input.name = key;
          input.value = value;
          form.appendChild(input);
        });
        document.body.appendChild(form);
        form.submit();
      }
    } catch (err) {
      // Invalid URL, let browser handle normally
    }
  }
});
</script>
")

;; =============================================================================
;; URL Parsing for State-Based Routes
;; =============================================================================

(defn parse-state-url
  "Parse URLs like /app/page.html/{state-id} into {:page 'page.html' :state-id 'uuid'}.
   Returns nil if URL doesn't match the state pattern."
  [uri]
  (when-let [[_ page state-id] (re-matches #"/app/([^/]+\.html)/([^/]+)" uri)]
    {:page page :state-id state-id}))

;; =============================================================================
;; Route Handlers for Stateful Parameterized Notebooks
;; =============================================================================

(defn handle-initial-post
  "Handle POST /page.html - Create state from body params and redirect.
   Flow: POST params → create state → 303 redirect to /app/page.html/{state-id}"
  [uri body-params _server-state]
  (let [page (str/replace uri #"^/" "")
        state-id (create-state! body-params)]
    (println "Initial POST:" page "params:" body-params "→ state-id:" state-id)
    {:status 303
     :headers {"Location" (str "/app/" page "/" state-id)}}))

(defn handle-state-post
  "Handle POST /app/page.html/{state-id} - Merge params and create new state.
   Flow: lookup current → merge with new → create new state → 303 redirect"
  [page current-state-id body-params _server-state]
  (if-let [current (get-state current-state-id)]
    (let [merged-params (merge (:params current) body-params)
          new-state-id (create-state! merged-params)]
      (println "State POST:" page "old-state:" current-state-id "→ new-state:" new-state-id)
      {:status 303
       :headers {"Location" (str "/app/" page "/" new-state-id)}})
    ;; Current state expired/not found
    {:status 404
     :body "State not found or expired. Please submit params again."}))

(defn handle-state-get
  "Handle GET /app/page.html/{state-id} - Render notebook with stored params.
   Flow: lookup state → evaluate notebook → inject link-to-POST script → return HTML"
  [page state-id server-state]
  (if-let [stored-state (get-state state-id)]
    (let [params (:params stored-state)
          uri (str "/" page)]
      (println "State GET:" page "state-id:" state-id "params:" params)
      (try
        (let [source-path (html-uri->source-path uri)
              config-fn (resolve 'scicloj.clay.v2.config/config)
              ->single-ns-spec-fn (resolve 'scicloj.clay.v2.make/->single-ns-spec)
              base-config (config-fn {:show false :live-reload false})
              spec (->single-ns-spec-fn {:return-html? true
                                          :url-params params}
                                        base-config
                                        source-path)
              handle-single-fn (resolve 'scicloj.clay.v2.make/handle-single-source-spec!)
              html (handle-single-fn spec)
              ;; Inject link-to-POST script for privacy
              html-with-script (str/replace html #"(<head[^>]*>)"
                                            (str "$1\n" (link-to-post-script)))]
          {:body (wrap-html html-with-script server-state)
           :headers {"Content-Type" "text/html"}
           :status 200})
        (catch Exception e
          (println "Error rendering state:" state-id (.getMessage e))
          (.printStackTrace e)
          {:body (str "Error generating page: " (.getMessage e))
           :status 500})))
    ;; State not found or expired
    {:status 404
     :body "State not found or expired. Please submit params again."}))

(defn scittle-eval-string!
  "Send ClojureScript code to be evaluated on the Clay page.
  The code will be executed directly using scittle.core.eval_string."
  [code]
  (broadcast! (str "scittle-eval-string " code)))

(defn get-free-port []
  (loop [port default-port]
    ;; Check if the port is free:
    ;; (https://codereview.stackexchange.com/a/31591)
    (or (try (do (.close (ServerSocket. port))
                 port)
             (catch Exception e nil))
        (recur (inc port)))))

(defn communication-script
  "The communication JS script to init a WebSocket to the server."
  [{:keys [port counter]}]
  (let [reload-regexp ".*/(#[a-zA-Z\\-]+)?\\$"
        ;; We use this regexp to recognize when to used
        ;; page reload rather than revert to the original URL,
        ;; see below.
        ]
    (->> [port counter reload-regexp]
         (apply format "
<script type=\"text/javascript\">

    clay_port = %d;
    clay_server_counter = '%d';
    reload_regexp = new RegExp('%s');

    clay_refresh = function() {
      // Check whether we are still in the main page
      // (but possibly in an anchor (#...) inside it):
      if(reload_regexp.test(window.location.href)) {
        // Just reload, keeping the current position:
        location.reload();
      } else {
         // We might be in a different book to the chapter.
         // So, reload and force returning to the main page.
         location.assign('http://localhost:'+clay_port);
      }
    }

    const clay_socket = new WebSocket('ws://localhost:'+clay_port);

    clay_socket.addEventListener('open', (event) => { clay_socket.send('Hello Server!')});

    clay_socket.addEventListener('message', (event)=> {
      if (event.data=='refresh') {
        clay_refresh();
      } else if (event.data=='loading') {
        document.body.style.opacity = 0.5;
        document.body.prepend(document.createElement('div', {class: 'loader'}));
      } else if (event.data.startsWith('scittle-eval-string ')) {
        // Evaluate ClojureScript code directly
        const code = event.data.substring('scittle-eval-string '.length);
        if (window.scittle && window.scittle.core && window.scittle.core.eval_string) {
          try {
            const result = window.scittle.core.eval_string(code);
            console.log('Clay eval result:', result);
          } catch (e) {
            console.error('Clay eval error:', e);
          }
        } else {
          console.warn('Scittle not available for eval-string');
        }
      } else {
        console.log('unknown ws message: ' + event.data);
      }
    });

  async function clay_1 () {
    const response = await fetch('/counter');
    const response_counter = await response.json();
    if (response_counter != clay_server_counter) {
      clay_refresh();
    }
  };
  clay_1();
</script>"))))

(defn header [state]
  (hiccup/html
   [:div
    [:div
     [:img
      {:style {:display "inline-block"
               :zoom 1
               :width "40px"
               :margin-left "20px"},
       ;; { zoom: 1; vertical-align: top; font-size: 12px;}
       :src "/Clay.svg.png"
       :alt "Clay logo"}]
     [:div {:style {:display "inline-block"
                    :margin "20px"}}
      [:pre {:style {:margin 0}}
       (some->> state
                :last-rendered-spec
                :full-target-path)]
      [:pre {:style {:margin 0}}
       (time/now)]]]]))

(defn page
  ([]
   (page @server.state/*state))
  ([state]
   (let [{:keys [last-rendered-spec live-reload]} state
         path (some-> last-rendered-spec :full-target-path)]
     (cond
       (and path (str/ends-with? path ".pdf"))
       (hiccup/html
        [:html
         [:head [:title "PDF Viewer"]]
         [:body
          [:embed {:src (str "/" (fs/unixify (fs/relativize (:base-target-path last-rendered-spec) path)))
                   :type "application/pdf"
                   :width "100%"
                   :height "900px"}]]])

       (fs/exists? path)
       (slurp path)

       :else
       (hiccup/html
        [:html
         [:head [:title "Clay Server State"]]
         [:body
          [:h2 "No file to display"]
          [:p "Create or edit source files"]
          [:details [:pre (with-out-str (pprint/pprint state))]]]])))))

(defn wrap-base-url [html {:as state
                           {:keys [flatten-targets
                                   full-target-path
                                   base-target-path]} :last-rendered-spec}]
  (if (and (false? flatten-targets)
           base-target-path
           full-target-path)
    (str/replace html #"(<\s*head[^>]*>)"
                 (str "$1"
                      "<base href=\"/"
                      (fs/unixify (fs/relativize base-target-path full-target-path))
                      "\" />\n"))
    html))

(defn wrap-html [html state]
  (-> html
      (str/replace #"(<\s*body[^>]*>)"
                   (str "$1"
                        (when-not (-> state
                                      :last-rendered-spec
                                      :hide-ui-header)
                          (hiccup/html
                           #_[:style "* {margin: 0; padding: 0; top: 0;}"]
                           [:div {:style {:height "70px"
                                          :background-color "#eee"}}
                            (header state)]))
                        (communication-script state)))))


(defn compute
  [input]
  (let [{:keys [func args]} input]
    (if-let [func-var (resolve func)]
      (if (-> func-var meta :kindly/servable)
        (apply func-var args)
        (throw (Exception. (str "Function is not safe to serve: "
                                func))))
      (throw (Exception. (str "Symbol not found: "
                              func))))))

(defn html-uri->source-path
  "Converts /index.html to notebooks/index.clj
   Handles Clay's filename conversion: hyphens to underscores"
  [uri]
  (let [html-file (str/replace uri #"^/" "")
        base-name (str/replace html-file #"\.html$" "")
        base-name (str/replace base-name #"_" "-")]  ; Clay converts - to _
    (str "notebooks/" base-name ".clj")))

;; Legacy param-preservation-script removed - replaced by stateful POST-based approach

;; Legacy handle-parameterized-request removed - replaced by handle-initial-post,
;; handle-state-post, and handle-state-get for privacy-preserving stateful approach

(defn routes
  "Web server routes."
  [{:keys [:body :request-method :uri :query-params :form-params]
    :as req}]
  (let [state @server.state/*state
        ;; Merge form-params (POST body) and query-params
        body-params (or form-params {})]
    (if (:websocket? req)
      (httpkit/as-channel req {:on-open (fn [ch]
                                          (swap! *clients conj ch)
                                          (when (:loading state)
                                            (httpkit/send! ch "loading")))
                               :on-close (fn [ch _reason] (swap! *clients disj ch))
                               :on-receive (fn [_ch msg])})

      ;; Check for state-based routes first (/app/page.html/{state-id})
      (if-let [{:keys [page state-id]} (parse-state-url uri)]
        (case request-method
          :get (handle-state-get page state-id state)
          :post (handle-state-post page state-id body-params state)
          {:status 405 :body "Method not allowed"})

        ;; Check for initial POST to create state (POST /page.html)
        (if (and (= request-method :post)
                 (re-matches #"/[^/]+\.html$" uri)
                 (seq body-params))
          (handle-initial-post uri body-params state)

          ;; EXISTING: All other routes (unchanged)
          (case [request-method uri]
            [:get "/"] {:body (-> state
                                  page
                                  (wrap-base-url state)
                                  (wrap-html state))
                        :headers {"Content-Type" "text/html"}
                        :status 200}
            [:get "/counter"] {:body (-> state
                                         :counter
                                         str)
                               :status 200}
            [:post "/kindly-compute"] (let [input (-> body
                                                      (transit/reader :json)
                                                      transit/read
                                                      read-string)
                                            output (compute input)]
                                        {:body (pr-str output)
                                         :status 200})
            ;; else
            (let [f (io/file (str (:base-target-path state) uri))]
              (if (.exists f)
                {:body    (if (re-matches #".*\.html$" uri)
                            (-> f
                                slurp
                                (wrap-html state))
                            f)
                 :headers (when-let [t (mime-type/ext-mime-type uri)]
                            {"Content-Type" t})
                 :status  200}
                (case [request-method uri]
                  ;; user files have priority, otherwise serve the default from resources
                  [:get "/favicon.ico"] {:body   (io/input-stream (io/resource "favicon.ico"))
                                         :status 200}
                  ;; this image is for the header above the page during interactive mode
                  [:get "/Clay.svg.png"] {:body   (io/input-stream (io/resource "Clay.svg.png"))
                                          :status 200}
                  {:body   "not found"
                   :status 404})))))))))

(defonce *stop-server! (atom nil))

(defn core-http-server [port]
  (httpkit/run-server (wrap-params #'routes) {:port port}))

(defn port->url [port]
  (str "http://localhost:" port "/"))

(defn port []
  (-> @server.state/*state
      :port))

(defn url []
  (some-> @server.state/*state
          :port
          port->url))

(defn browse! []
  (let [u (url)]
    (try
      (browse/browse-url u)
      (catch Exception e
        (println "Clay could not open the browser for" u)))))

(defn open!
  ([] (open! {}))
  ([{:as opts :keys [port browse ide]}]
   (when-not @*stop-server!
     (let [port (or port (get-free-port))
           stop-server (core-http-server port)]
       (server.state/set-port! port)
       (reset! *stop-server! stop-server)
       (println "Clay serving at" (port->url port))
       ;; Start background cleanup task for state management
       (start-cleanup-task!)
       ;; browse can be :browser to prefer using a browser always
       (when (or (= browse :browser)
                 ;; clay default is browse true,
                 ;; ide flag causes a flare to request a webview in the ide
                 ;; so if ide is true we do not show the browser, even when browse is true
                 (and browse (not ide)))
         (browse!))))))

(defn update-page! [{:as spec
                     :keys [show
                            base-target-path
                            page
                            full-target-path]
                     :or   {full-target-path (str base-target-path
                                                  "/"
                                                  ".clay.html")}}]
  (server.state/set-base-target-path! base-target-path)
  (when show
    (open! spec))
  (io/make-parents full-target-path)
  (when page
    (spit full-target-path page))
  (-> spec
      (assoc :full-target-path full-target-path)
      (server.state/reset-last-rendered-spec!))
  (when show
    (swap! server.state/*state dissoc :loading)
    (broadcast! "refresh"))
  [:ok])

(defn loading! []
  (swap! server.state/*state assoc :loading true)
  (broadcast! "loading"))

(defn close! []
  ;; Stop background cleanup task
  (stop-cleanup-task!)
  (when-let [s @*stop-server!]
    (s))
  (reset! *stop-server! nil))
