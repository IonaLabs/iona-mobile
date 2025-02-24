(ns status-im.contexts.keycard.migrate.fail.view
  (:require [quo.core :as quo]
            [react-native.core :as rn]
            [status-im.common.resources :as resources]
            [status-im.constants :as constants]
            [utils.i18n :as i18n]
            [utils.re-frame :as rf]))

(defn view
  []
  (let [profile-name        (rf/sub [:profile/name])
        profile-picture     (rf/sub [:profile/image])
        customization-color (rf/sub [:profile/customization-color])]
    [:<>
     [quo/page-top
      {:title           (i18n/label :t/something-didnt-go-as-planned)
       :description     :context-tag
       :context-tag     {:full-name           profile-name
                         :profile-picture     profile-picture
                         :customization-color customization-color}
       :container-style {:margin-top constants/page-nav-height}}]
     [rn/image
      {:resize-mode :contain
       :style       {:flex 1 :width (:width (rn/get-window))}
       :source      (resources/get-image :keycard-migration-failed)}]
     [quo/divider-label (i18n/label :t/what-you-can-do)]
     [quo/markdown-list {:description (i18n/label :t/keycard-migration-failed-instruction-1)}]
     [quo/markdown-list {:description (i18n/label :t/keycard-migration-failed-instruction-2)}]
     [quo/bottom-actions
      {:actions          :one-action
       :button-one-label (i18n/label :t/logout)
       :button-one-props {:on-press #(rf/dispatch [:profile/logout])}}]]))
