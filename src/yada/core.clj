;; Copyright © 2015, JUXT LTD.

(ns yada.core
  (:require
   [manifold.deferred :as d]
   [hiccup.core :refer (html h)]
   [schema.core :as s]
   [yada.protocols :as p]
   [yada.conneg :refer (best-allowed-content-type)]
   [yada.representation :as rep]
   [yada.util :refer (parse-http-date)]
   [clojure.tools.logging :refer :all]
   ))

;; API specs. are created like this

;; This is kind of like a bidi route structure

;; But we shouldn't limit ourselves to only that which is declared, because so much more can be generated, like 404s, etc.

;; "It is better to have 100 functions operate on one data structure than 10 functions on 10 data structures." —Alan Perlis


;; TODO For authentication, implementation is out-of-band, in Ring
;; middleware or another mechanism for assoc'ing evidence of credentials
;; to the Ring request.

(defn- not* [[result m]]
  [(not result) m])

(defmacro nonblocking-exit-when*
  "Short-circuit exit a d/chain with an error if expr evaluates to
  truthy. To avoid blocking the request thread, the callback can return
  a deferred value."
  [callback expr status]
  `(fn [x#]
     (let [[b# m#] (when (some? ~callback) ; guard for performance
                   ~expr)]
       ;; Exit, intended to be caught with a d/catch
       (if b#
         (d/error-deferred (ex-info "" (merge {:status ~status
                                               ::http-response true} m#)))
         x#))))

(defmacro nonblocking-exit-when
  [callback expr status]
  `(nonblocking-exit-when* ~callback (deref (d/chain ~expr)) ~status))

(defmacro nonblocking-exit-when-not
  [callback expr status]
  `(nonblocking-exit-when* ~callback (deref (d/chain ~expr not*)) ~status))

(defmacro exit-when [expr status]
  `(fn [x#]
     (let [[b# m#] ~expr]
       (if b#
         (d/error-deferred (ex-info "" (merge {:status ~status
                                               ::http-response true}
                                              m#)))
         x#))))

(defmacro exit-when-not [expr status]
  `(exit-when (not* ~expr) ~status))



(defn spyctx [ctx]
  (debugf "Context is %s" ctx)
  ctx)

(defn make-async-handler
  [{:keys
    [service-available?                 ; async-supported
     known-method?
     request-uri-too-long?

     #_allowed-method?

     ;; The allowed? callback will contain the entire resource, the callback must
     ;; therefore extract the OAuth2 scopes, or whatever is needed to
     ;; authorize the request.
     ;; allowed?

     status                             ; async-supported
     headers                            ; async-supported

     resource                           ; async-supported
     state                              ; async-supported
     body                               ; async-supported

     ;; Actions
     put                                ; async-supported
     post                               ; async-supported
     delete                             ; async-supported
     patch                              ; async-supported

     produces
     ] ;; :or {resource {}}
    :as resource-map
    } ]

  ;; We use this let binding to deduce the values of resource-map
  ;; entries that have not been given, based on the values of entries
  ;; that have. This approach makes it possible for developers to leave
  ;; out entries that are implied by the other entries. For example, if a body has been specified, we resource



  (fn [req]
    (let [method (:request-method req)]

      (-> {:request req
           :resource-map resource-map}

          (d/chain
           (nonblocking-exit-when-not service-available? (p/service-available? service-available?) 503)
           (exit-when-not (p/known-method? known-method? method) 501)
           (exit-when (p/request-uri-too-long? request-uri-too-long? (:uri req)) 414)

           (fn [ctx]
             (if-not
                 (case method
                   :get (or (some? resource) state body)
                   :put put
                   nil)
               (d/error-deferred (ex-info ""
                                          {:status 405
                                           ::http-response true}))
               ctx))


           ;; TODO Malformed

           ;; TODO Unauthorized
           ;; TODO Forbidden

           ;; TODO Not implemented (if unknown Content-* header)

           ;; TODO Unsupported media type

           ;; TODO Request entity too large - shouldn't we do this later,
           ;; when we determine we actually need to read the request body?

           ;; TODO OPTIONS

           ;; Content-negotiation - partly done here to throw back to the client any errors
           #(let [produces (or (p/produces produces)
                               (p/produces-from-body body))]
              (if-let [content-type
                       (best-allowed-content-type
                        (or (get-in req [:headers "accept"]) "*/*")
                        produces
                        )]
                (assoc-in % [:response :content-type] content-type)
                (if produces
                  ;; If there is a produces specification, but not
                  ;; matched content-type, it's a 406.
                  (d/error-deferred (ex-info "" {:status 406
                                                 ::http-response true}))
                  ;; Otherwise return the context unchanged
                  %)))


           ;; Does the resource exist? Call resource, which returns
           ;; the resource, containing the resource's metadata (optionally
           ;; deferred to prevent blocking this thread). It does not (yet)
           ;; contain the resource's data. The reason for this is that it
           ;; would be wasteful to load the resource's data if we can
           ;; determine that the client already has a copy and return a 304 (Not Modified).
           (fn [ctx]
             (d/chain
              (p/resource resource req)
              #(assoc ctx :resource %)))

           ;; Split the flow based on the existence of the resource
           (fn [{:keys [resource] :as ctx}]

             (if (and (not (false? resource)) (or resource state body))

               ;; 'Exists' flow
               (case method
                 (:get :head)
                 (d/chain
                  ctx

                  ;; Conditional request
                  (fn [ctx]
                    (if-let [last-modified
                             (when-let [hdr (:last-modified resource)]
                               (p/last-modified hdr ctx))]

                      (if-let [if-modified-since (parse-http-date (get-in req [:headers "if-modified-since"]))]
                        (let [last-modified (if (d/deferrable? last-modified) @last-modified last-modified)]
                          (if (<
                               (.getTime last-modified)
                               (.getTime if-modified-since))

                            ;; exit with 304
                            (d/error-deferred (ex-info "" (merge {:status 304
                                                                  ::http-response true}
                                                                 ctx)))

                            (assoc-in ctx [:response :headers "last-modified"] last-modified)

                            ))

                        (assoc-in ctx [:response :headers "last-modified"] (if (d/deferrable? last-modified) @last-modified last-modified))
                        )
                      ctx))

                  ;; OK, let's pick the resource's state
                  (fn [ctx]
                    (d/chain
                     ;; note the priorities:
                     (or (:state resource) state) ; could be nil
                     #(p/state % ctx)
                     #(assoc-in ctx [:resource :state] %)))

                  ;; Create body
                  (fn [ctx]
                    (let [state (get-in ctx [:resource :state])
                          content-type
                          (or (get-in ctx [:response :content-type])
                              ;; It's possible another callback has set the content-type header
                              (get-in headers ["content-type"])
                              (when-not body (rep/content-type-default state)))]

                      (d/chain

                       ;; Determine body
                       (cond
                         body (p/body body ctx)
                         state state ; the state here can still be deferred
                         )

                       ;; serialize to representation (an existing string will be left intact)
                       (fn [state]
                         (debugf "calling content with state = %s, content-type = %s" state content-type)
                         (rep/content state content-type))


                       ;; on nil, compose default result (if in dev)
                       #_(fn [x] (if x x (rep/content nil content-type)))

                       #(assoc-in ctx [:response :body] %)
                       #(if content-type
                          (update-in % [:response :headers] assoc "content-type" content-type)
                          %
                          ))))



                  (fn [ctx]
                    (merge
                     {:status (or (get-in ctx [:response :status])
                                  (p/status status)
                                  200)
                      :headers (merge
                                (get-in ctx [:response :headers])
                                (p/headers headers))
                      ;; TODO :status and :headers should be implemented like this in all cases
                      :body (get-in ctx [:response :body])})))

                 :put
                 (d/chain
                  ctx

                  (fn [ctx]
                    (when-let [etag (get-in req [:headers "if-match"])]
                      (when (not= etag (get-in ctx [:resource :etag]))
                        (throw
                         (ex-info "Precondition failed"
                                  {:status 412
                                   ::http-response true})))

                      )
                    ctx
                    )

                  (fn [ctx]
                    {:status 204
                     :headers (get-in ctx [:response :headers])
                     :body (get-in ctx [:response :body])
                     }
                    ))

                 (throw (ex-info "TODO!" {}))
                 )

               ;; 'Not exists' flow
               (d/chain ctx (constantly {:status 404})))))

          ;; Handle exits
          (d/catch clojure.lang.ExceptionInfo
              #(let [data (ex-data %)]
                 (if (::http-response data)
                   data
                   (throw (ex-info "Internal Server Error (ex-info)" {} %))
                   #_{:status 500
                      :body (format "Internal Server Error: %s" (pr-str data))})))

          (d/catch #(identity
                     (throw (ex-info "Internal Server Error" {} %))
                     #_{:status 500 :body
                        (html
                         [:body
                          [:h1 "Internal Server Error"]
                          [:p (str %)]
                          [:pre (with-out-str (apply str (interpose "\n" (seq (.getStackTrace %)))))]
                          ])}))))))


;; TODO: pets should return resource-metadata with a (possibly deferred) model

;; handle-method-not-allowed 405 "Method not allowed."

;; This is OK
;; ((api-handler api) (mock/request :get "/persons"))

;; This is should yield 405 "Method not allowed."
;; ((api-handler api) (mock/request :get "/persons"))

;; List of interesting things to do

;; There should be a general handler that does the right thing
;; wrt. available methods (Method Not Allowed) and calls out to
;; callbacks accordingly. Perhaps there's no sense in EVERYTHING being
;; overridable, as with Liberator. It should hard-code the things that
;; don't make sense to override, and use hooks for the rest.

;; Resource existence is most important - and not covered by swagger, so it's a key hook.

;; Return deferreds, if necessary, if the computation is length to compute (e.g. for exists? checks)

;; CORS support: build this in, make allow-origin first-class, which headers should be allowed should be a hook (with default)