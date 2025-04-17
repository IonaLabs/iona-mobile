(ns status-im.contexts.chat.messenger.composer.mentions.view
  (:require
    [quo.context]
    [react-native.core :as rn]
    [react-native.reanimated :as reanimated]
    [react-native.safe-area :as safe-area]
    [status-im.common.contact-list-item.view :as contact-list-item]
    [status-im.contexts.chat.messenger.composer.constants :as constants]
    [status-im.contexts.chat.messenger.composer.mentions.style :as style]
    [status-im.contexts.chat.messenger.messages.constants :as messages.constants]
    [utils.re-frame :as rf]))

(defn- mention-item
  [[user-key user]]
  (with-meta
    [contact-list-item/contact-list-item {:on-press #(rf/dispatch [:chat.ui/select-mention user])}
     user]
    {:key user-key}))

(defn view
  [layout-height]
  (let [suggestions             (rf/sub [:chat/mention-suggestions])
        suggestions?            (seq suggestions)
        theme                   (quo.context/use-theme)
        opacity                 (reanimated/use-shared-value (if suggestions? 1 0))
        [suggestions-state
         set-suggestions-state] (rn/use-state suggestions)
        top                     (min constants/mentions-max-height
                                     (* (count suggestions-state) 56)
                                     (- @layout-height
                                        (+ safe-area/top
                                           messages.constants/top-bar-height
                                           5)))]
    (rn/use-effect
     (fn []
       (if suggestions?
         (set-suggestions-state suggestions)
         (js/setTimeout #(set-suggestions-state suggestions) 300))
       (reanimated/animate opacity (if suggestions? 1 0)))
     [suggestions])
    [reanimated/view {:style (style/container opacity top theme)}
     [rn/scroll-view
      {:accessibility-label          :mentions-list
       :keyboard-should-persist-taps :always}
      (map mention-item suggestions-state)]]))
