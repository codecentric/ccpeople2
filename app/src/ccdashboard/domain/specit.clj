(ns ccdashboard.domain.specit
  (:require [clojure.spec :as cs]
            [clojure.spec.gen :as gen]
            [clojure.string :as str]))


(cs/def :ccdashboard/non-empty-string (cs/and string? seq))


(defn fixed-size-string-generator [min-n max-n string-gen]
  (gen/fmap str/join (gen/vector string-gen min-n max-n)))

(def email-tld-gen
  (fixed-size-string-generator 2 5 (gen/char-alpha)))

(def non-empty-string-alphanumeric-gen (gen/such-that seq (gen/string-alphanumeric)))

(def email-prefix-char-gen (gen/such-that seq
                                          (gen/fmap str/join (gen/vector (gen/one-of [(gen/char-alpha)
                                                                                      (gen/char-alpha)
                                                                                      (gen/char-alpha)
                                                                                      (gen/elements [\. \_ \% \+ \-])])))))

(defn email-gen []
  (gen/fmap (fn [[prefix domain tld]]
              (str prefix "@" domain "." tld))
            (gen/tuple email-prefix-char-gen
                       non-empty-string-alphanumeric-gen
                       email-tld-gen)))

(def email-regex #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,63}$")

(cs/def :ccdashboard/email-type (cs/with-gen (cs/and string? #(re-matches email-regex %))
                                             email-gen))


(cs/def :ccdashboard.jira.user/name :ccdashboard/non-empty-string)
(cs/def :ccdashboard.jira.user/displayName :ccdashboard/non-empty-string)
(cs/def :ccdashboard.jira.user/emailAddress :ccdashboard/email-type)

(cs/def :ccdashboard.jira/user (cs/keys :req-un [:ccdashboard.jira.user/name
                                                 :ccdashboard.jira.user/emailAddress
                                                 :ccdashboard.jira.user/displayName]))