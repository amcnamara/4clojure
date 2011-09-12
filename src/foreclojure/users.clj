(ns foreclojure.users
  (:require [ring.util.response       :as response]
            [sandbar.stateful-session :as session])
  (:use [foreclojure.utils   :only [from-mongo def-page row-class get-user with-user]]
        [foreclojure.config  :only [config repo-url]]
        [somnium.congomongo  :only [fetch-one fetch update!]]
        [compojure.core      :only [defroutes GET POST]]
        [hiccup.form-helpers :only [form-to hidden-field]]
        [hiccup.page-helpers :only [link-to]]))

(def golfer-tags (into [:contributor]
                       (when (:golfing-active config)
                         [:golfer])))

(defn get-user-id [name]
  (:_id
   (fetch-one :users
              :where {:user name}
              :only [:_id])))

(def sort-by-solved-and-date (juxt (comp count :solved) :last-login))

(defn users-sort [users]
  (reverse (sort-by sort-by-solved-and-date users)))

(defn get-users []
  (let [users (from-mongo
               (fetch :users
                      :only [:user :solved :contributor]))
        sortfn  (comp - count :solved)]
    (sort-by sortfn users)))

(defn golfer? [user]
  (some user golfer-tags))

(defn disable-codebox? [user]
  (true? (:disable-code-box user)))

(defn email-address [username]
  (:email (fetch-one :users :where {:user username})))

(defn mailto [username]
  (link-to (str "mailto:" (email-address username))
           username))

(def-page users-page []
  {:title "Top Users"
   :content
   (list
    [:div
     [:span.contributor "*"] " "
     (link-to repo-url "4clojure contributor")]
    [:br]
    [:table#user-table.my-table
     [:thead
      [:tr
       [:th {:style "width: 40px;"} "Rank"]
       [:th "Username"]
       [:th "Problems Solved"]]]
     (map-indexed (fn [rownum {:keys [user contributor solved]}]
                    [:tr (row-class rownum)
                     [:td (inc rownum)]
                     [:td
                      (when contributor [:span.contributor "* "])
                      [:a.user-profile-link {:href (str "/user/" user)} user]]
                     [:td.centered (count solved)]])
                  (get-users))])})

;; TODO: this is snagged from problems.clj but can't be imported due to cyclic dependency, must refactor this out.
(defn get-problems
  ([]
     (from-mongo
      (fetch :problems
             :only  [:_id :difficulty]
             :where {:approved true}
             :sort  {:_id 1})))
  ([difficulty]
     (get (group-by :difficulty (get-problems)) difficulty [{}])))

(defn get-solved
  ([username]
     (:solved (get-user username)))
  ([username difficulty]
     (let [ids (->> (from-mongo
                     (fetch :problems
                            :only  [:_id]
                            :where {:approved true, :difficulty difficulty}))
                    (map :_id)
                    (set))]
       (filter ids (get-solved username)))))

(def-page user-profile [username]
  (let [page-title (str "User: " username)
        user-id (:_id (get-user username))]
    {:title page-title
     :content
     (list
      [:div.user-profile-name page-title]
      (if (session/session-get :user)
        (with-user [{:keys [_id following]}]
          (if (not= _id user-id)
            (if (some #{user-id} following)
              (form-to [:post (str "/user/" username)] (hidden-field :action "unfollow") [:button.user-follow-button {:type "submit"} "Unfollow"])
              (form-to [:post (str "/user/" username)] (hidden-field :action "follow"  ) [:button.user-follow-button {:type "submit"} "Follow"]))
            [:div {:style "clear: right; margin-bottom: 10px;"} "&nbsp;"]))
        [:div {:style "clear: right; margin-bottom: 10px;"} "&nbsp;"])
      [:hr]
      [:table
       (for [difficulty ["Elementary" "Easy" "Medium" "Hard"]]
         (let [solved (count (get-solved username difficulty))
               total  (count (get-problems difficulty))]
           [:tr
            [:td.count-label difficulty]
            [:td.count-value
             [:div.progress-bar-bg
              [:div.progress-bar
               {:style (str "width: "
                            (int (* 100 (/ solved total)))
                            "%")}]]]]))
       [:tr
        [:td.count-total "TOTAL:"    ]
        [:td.count-value
         (count (get-solved username)) "/"
         (count (get-problems))]]])}))

(defn follow-user [action username]
  (with-user [{:keys [_id]}]
    (let [follow-id (:_id (get-user username))]
      (update! :users
               {:_id _id}
               (if (= action "follow")
                 {:$addToSet {:following follow-id}}
                 {:$pull     {:following follow-id}}))))
  (user-profile username))

(defn set-disable-codebox [disable-flag]
  (with-user [{:keys [_id]}]
    (update! :users
             {:_id _id}
             {:$set {:disable-code-box (boolean disable-flag)}})
    (response/redirect "/problems")))

(defroutes users-routes
  (GET  "/users" [] (users-page))
  (GET  "/user/:username" [username] (user-profile username))
  (POST "/user/:username" [action username] (follow-user action username)))
