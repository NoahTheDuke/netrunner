(ns jinteki.msgpack-ext
  (:require
   [cljc.java-time.local-date-time :as ldt]
   [taoensso.msgpack.common :as msgpack]
   #?@(:cljs [[cljc.java-time.instant :as inst]
              [cljs.reader :as reader]
              [java.time :refer [LocalDateTime]]
              ;; loads taoensso.msgpack.impl to override -1
              [taoensso.msgpack]]))
  #?(:clj (:import (java.time LocalDateTime))))

#?(:clj (set! *warn-on-reflection* true))

(msgpack/extend-packable 100 LocalDateTime
  (pack [x]
    #?(:clj (.getBytes (str x) "UTF-8")
       :cljs (.encode (js/TextEncoder.) (str x))))
  (unpack [ba]
    (ldt/parse
     #?(:clj (String. ^bytes ba "UTF-8")
        :cljs (.decode (js/TextDecoder.) ba)))))

#?(:cljs
   ;; read msgpack timestamp (-1) as cljc.java-time.instant instead of js/Date
   (let [unpack-date (get-method msgpack/unpack-ext -1)]
     (defmethod msgpack/unpack-ext -1 [byte-id ba]
       (inst/of-epoch-milli (.getTime (unpack-date byte-id ba))))))

#?(:cljs
   ;; read edn #inst tags as cljc.java-time.instant instead of js/Date
   (reader/register-tag-parser! 'inst #(inst/of-epoch-milli (.getTime (reader/parse-timestamp %)))))
