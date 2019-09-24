(ns dtaoldm.aol
  "DativeTop Append-only Log Domain Model (DTAOLDM)."
  (:require [clojure.set :refer [rename-keys]]
            #?(:clj [cheshire.core :as ch])
            [clojure.string :as str]
            #?(:cljs [goog.crypt
                      goog.crypt.Md5]))
  #?(:clj (:import
           (java.util Calendar)
           (java.util TimeZone)
           (java.text SimpleDateFormat)
           (java.security MessageDigest)
           (java.math BigInteger))))

(def has-attr "has")
(def lacks-attr "lacks")
(def is-a-attr "is-a")
(def being-val "being")
(def extant-pred [has-attr, being-val])
(def non-existent-pred [lacks-attr, being-val])
(def being-preds [extant-pred, non-existent-pred])

(defn aol-to-domain-attr-convert
  "Convert an attribute from the AOL-level syntax (which should be more
  human-readable) to the domain-level syntax."
  [quad-attr]
  (if (str/starts-with? quad-attr "has-")
    (->> quad-attr (drop 4) (apply str))
    quad-attr))

(defn appendable->map
  [[[e a v _] _ _]]
  {e (merge
      {:id e}
      (cond
        (some #{[a v]} being-preds) (if (= a has-attr)
                                      {:extant true} {:extant false})
        (= is-a-attr a) {:type v}
        :else {(keyword (aol-to-domain-attr-convert a)) v}))})

(defmulti construct (fn [m] (:type m)))

(defmethod construct "dative-app"
  [m]
  (select-keys m [:id :type :url]))

(defmethod construct "old-service"
  [m]
  (select-keys m [:id :type :url]))

(defmethod construct "old-instance"
  [m]
  (-> m
      (select-keys [:id
                    :type
                    :slug
                    :name
                    :url
                    :leader
                    :state
                    :is-auto-syncing])
      (rename-keys {:is-auto-syncing :auto-sync?})))

(defn aol-to-domain-entities
  "Given an append-only log ``aol`` (a sequence of triples whose first element is
  a <Event, Attribute, Value, Time> quadruple), return a dict from plural domain
  entity type keywords (e.g., ``:old-instances) to sets of domain entity maps."
  [aol]
  (->> aol
       (map appendable->map)
       (apply (partial merge-with merge))
       vals
       (filter :extant)
       (map construct)
       (group-by :type)
       (map (fn [[k v]] [(-> k (str "s") keyword) v]))
       (into {})))


(defn locate-start-index-reducer
  "Function to be passed to ``reduce`` in order to determine the starting index
  (int) of the suffix of a mergee changset (sequence) into a target changeset
  (sequence)"
  [{:keys [target-seen mergee-seen mergee-length index] :as agg}
   [[_ _ target-hash] [_ _ mergee-hash]]]
  (if (or (= target-hash mergee-hash)
          (some #{mergee-hash} target-seen))
    (reduced (assoc agg :start-index (- mergee-length index)))
    (let [target-seen (conj target-seen target-hash)
          other-index
          (->> mergee-seen
               reverse
               (filter (fn [[hash- _]] (some #{hash-} target-seen)))
               first
               second)]
      (if other-index
        (reduced (assoc agg :start-index (- mergee-length other-index)))
        (merge agg
               {:target-seen target-seen
                :mergee-seen (conj mergee-seen [mergee-hash index])
                :index (inc index)})))))

(defn find-changes
  "Find the suffix (possibly empty) of coll ``mergee`` that is not in coll
  ``target``. Algorithm involves processing the two colls pairwise and
  simultaneously in reverse order."
  [target mergee]
  (if-not (seq target)  ;;  target is empty, all of mergee is new
    mergee
    (let [{:keys [start-index]}
          (reduce
           locate-start-index-reducer
           {:target-seen []  ;; suffix of target hashes seen
            :mergee-seen []  ;; suffix of mergee hashes seen (elements are [hash index] 2-vecs)
            :mergee-length (count mergee)
            :index 0
            :start-index nil}
           (->> [(reverse target) (reverse mergee)]
                (apply interleave)
                (partition 2)))]
      (if start-index (drop start-index mergee) mergee))))

(def need-rebase-err
  (str "There are changes in the target AOL that are not present"
       " in the mergee AOL. Please manually rebase the mergee's"
       " changes or try again with the 'ebase' conflict"
       " resolution strategy."))

(defn get-hash
  "Taken from https://github.com/thedavidmeister/cljc-md5/blob/master/src/md5/core.cljc"
  [s]
  {:pre [(string? s)]
   :post [(string? %)]}
  #?(:cljs
     (goog.crypt/byteArrayToHex
      (let [md5 (goog.crypt.Md5.)]
        (.update md5 (goog.crypt/stringToUtf8ByteArray s))
        (.digest md5)))
     :clj
     (let [algorithm (MessageDigest/getInstance "MD5")
           raw (.digest algorithm (.getBytes s))]
       (format "%032x" (BigInteger. 1 raw)))))

(defn get-json
  [quad]
  #?(:clj (ch/generate-string quad)
     :cljs (.stringify js/JSON (clj->js quad))))

(defn parse-json
  [string]
  #?(:clj (ch/parse-string string)
     :cljs (.parse js/JSON string)))

(def serialize-quad get-json)

(defn get-hash-of-quad
  [quad]
  (-> quad serialize-quad get-hash))

(defn get-tip-hash
  [aol]
  (-> aol last (nth 2)))

(defn get-now
  []
  #?(:clj (.getTime (Calendar/getInstance))
     :cljs (js/Date.)))

(defn get-now-str
  "Return an ISO 8601 string representation of the current datetime. Use no
  dependencies. WARNING: microsecond precision is not present although it appears
  to be so since 3 zeroes are appended to the milliseconds. The purpose of this
  is to match the format of the representation returned by Python's
  ``datetime.datetime.utcnow().isoformat()``. This is important because the
  client-side ClojureScript AOL will be coordinating data with a server-side
  Python web service."
  []
  #?(:clj (let [timezone (TimeZone/getTimeZone "UTC")
                formatter (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSS")]
            (.setTimeZone formatter timezone)
            (->> (.format formatter (get-now))
                 (#(concat % (take 3 (repeat \0))))
                 (apply str)))
     :cljs (->> (get-now)
                .toISOString
                (drop-last 1)
                (#(concat % (take 3 (repeat \0))))
                (apply str))))

(defn get-now-str-clj
  []
  (let [timezone (TimeZone/getTimeZone "UTC")
        formatter (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSS")]
    (.setTimeZone formatter timezone)
    (->> (.format formatter (get-now))
         (#(concat % (take 3 (repeat \0))))
         (apply str))))

(defn get-now-str-cljs
  []
  (->> (get-now)
       .toISOString
       (drop-last 1)
       (#(concat % (take 3 (repeat \0))))
       (apply str)))

(defn append-to-aol
  "Append ``quad`` (4-ary EAVT vector) to AOL ``aol``."
  [aol quad]
  (let [hash-of-quad (get-hash-of-quad quad)
        top-integrated-hash (get-tip-hash aol)
        integrated-hash-of-quad
        (get-hash (get-json [top-integrated-hash hash-of-quad]))]
    (conj aol [quad hash-of-quad integrated-hash-of-quad])))

(defn merge-aols
  "Merge AOL ``mergee`` into AOL ``target``.

  Parameter ``target`` (coll) is the AOL that will receive the changes.
  Parameter ``mergee`` (coll) is the AOL that will be merged into target; the
  AOL that provides the changes.
  Keyword parameter conflict-resolution-strategy (keyword) describes how to
  handle conflicts. If the strategy is 'rebase', we will append the new quads
  from ``mergee`` onto ``target``, despite the fact that this will result in
  hashes for those appended quads that differ from their input hashes in
  ``mergee``.
  Keyword parameter ``diff-only`` (boolean) will, when true, result in a return
  value consisting simply of the suffix of the merged result that ``mergee``
  would need to append to itself in order to become identical with ``target``;
  when false, we return the entire modified ``target``.
  Always returns a 2-tuple maybe-type structure."
  [target mergee
   & {:keys [conflict-resolution-strategy diff-only]
      :or {conflict-resolution-strategy :abort
           diff-only false}}]
  (let [new-from-mergee (find-changes target mergee)]
    (if-not (seq new-from-mergee)
      (if diff-only
        [(find-changes mergee target) nil]
        [target nil])
      (let [new-from-target (find-changes mergee target)]
        (if (and (seq new-from-target)
                 (not= conflict-resolution-strategy :rebase))
          [nil need-rebase-err]
          (if (and diff-only (not (seq new-from-target)))
            [new-from-target nil]
            [(reduce append-to-aol target new-from-mergee) nil]))))))