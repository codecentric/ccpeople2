(ns ccdashboard.util)

(defn matching [k v]
  (fn [m]
    (= v (get m k))))

(defn min-key-comparable [k x & ys]
  (reduce (fn [result a]
            (if (not= 1 (compare (k result) (k a)))
              result
              a))
          x ys))