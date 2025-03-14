(ns status-im.contexts.wallet.swap.events
  (:require [re-frame.core :as rf]
            [status-im.constants :as constants]
            [status-im.contexts.wallet.common.utils :as utils]
            [status-im.contexts.wallet.data-store :as data-store]
            [status-im.contexts.wallet.send.utils :as send-utils]
            [status-im.contexts.wallet.sheets.network-selection.view :as network-selection]
            [status-im.contexts.wallet.swap.utils :as swap-utils]
            [taoensso.timbre :as log]
            [utils.i18n :as i18n]
            [utils.money :as money]
            [utils.number :as number]))

(rf/reg-event-fx :wallet.swap/start
 (fn [{:keys [db]} [{:keys [network asset-to-receive open-new-screen? from-account] :as data}]]
   (let [{:keys [wallet]}       db
         test-networks-enabled? (get-in db [:profile/profile :test-networks-enabled?])
         view-id                (:view-id db)
         root-screen?           (or (= view-id :wallet-stack) (nil? view-id))
         available-accounts     (utils/get-accounts-with-token-balance (:accounts wallet)
                                                                       (:asset-to-pay data))
         account                (or from-account
                                    (swap-utils/current-viewing-account wallet)
                                    (first available-accounts))
         asset-to-pay           (if (and (not from-account) (get-in data [:asset-to-pay :networks]))
                                  (:asset-to-pay data)
                                  (swap-utils/select-asset-to-pay-by-symbol
                                   {:wallet                 wallet
                                    :account                account
                                    :test-networks-enabled? test-networks-enabled?
                                    :token-symbol           (get-in data [:asset-to-pay :symbol])}))
         received-asset         (if-not (nil? asset-to-receive)
                                  asset-to-receive
                                  (swap-utils/select-asset-to-pay-by-symbol
                                   {:wallet                 wallet
                                    :account                account
                                    :test-networks-enabled? test-networks-enabled?
                                    :token-symbol           (if (= (:symbol asset-to-pay) "SNT")
                                                              "ETH"
                                                              "SNT")}))
         multi-account-balance? (-> available-accounts
                                    (count)
                                    (> 1))
         network'               (or network
                                    (swap-utils/select-network asset-to-pay))
         start-point            (if open-new-screen? :action-menu :swap-button)]
     {:db (-> db
              (assoc-in [:wallet :ui :swap :asset-to-pay] asset-to-pay)
              (assoc-in [:wallet :ui :swap :asset-to-receive] received-asset)
              (assoc-in [:wallet :ui :swap :network] network')
              (assoc-in [:wallet :ui :swap :launch-screen] view-id)
              (assoc-in [:wallet :ui :swap :start-point] start-point))
      :fx (if (and multi-account-balance? root-screen? (not from-account))
            [[:dispatch [:open-modal :screen/wallet.swap-select-account]]]
            (if network'
              [[:dispatch [:wallet/switch-current-viewing-account (:address account)]]
               [:dispatch
                (if open-new-screen?
                  [:open-modal :screen/wallet.setup-swap]
                  [:navigate-to-within-stack
                   [:screen/wallet.setup-swap :screen/wallet.swap-select-asset-to-pay]])]
               [:dispatch
                [:centralized-metrics/track :metric/swap-start
                 {:network       (:chain-id network)
                  :pay_token     (:symbol asset-to-pay)
                  :receive_token (:symbol received-asset)
                  :start_point   start-point
                  :launch_screen view-id}]]
               [:dispatch [:wallet.swap/set-default-slippage]]]
              [[:dispatch
                [:show-bottom-sheet
                 {:content (fn []
                             [network-selection/view
                              {:token-symbol      (:symbol asset-to-pay)
                               :on-select-network (fn [network]
                                                    (rf/dispatch [:hide-bottom-sheet])
                                                    (rf/dispatch
                                                     [:wallet.swap/start
                                                      {:asset-to-pay asset-to-pay
                                                       :asset-to-receive received-asset
                                                       :network network
                                                       :open-new-screen?
                                                       open-new-screen?
                                                       :from-account from-account}]))}])}]]]))})))

(rf/reg-event-fx :wallet.swap/select-asset-to-pay
 (fn [{:keys [db]} [{:keys [token]}]]
   (let [previous-token (get-in db [:wallet :ui :swap :asset-to-pay])
         network        (get-in db [:wallet :ui :swap :network])]
     {:db (update-in db
                     [:wallet :ui :swap]
                     #(-> %
                          (assoc :asset-to-pay token)
                          (dissoc :amount
                                  :amount-hex
                                  :last-request-uuid
                                  :swap-proposal
                                  :error-response
                                  :loading-swap-proposal?
                                  :approval-transaction-id
                                  :approved-amount)))
      :fx [[:dispatch
            [:centralized-metrics/track :metric/swap-asset-to-pay-changed
             {:network        (:chain-id network)
              :previous_token (:symbol previous-token)
              :new_token      (:symbol token)}]]]})))

(rf/reg-event-fx :wallet.swap/select-asset-to-receive
 (fn [{:keys [db]} [{:keys [token]}]]
   (let [previous-token (get-in db [:wallet :ui :swap :asset-to-receive])
         network        (get-in db [:wallet :ui :swap :network])]
     {:db (assoc-in db [:wallet :ui :swap :asset-to-receive] token)
      :fx [[:dispatch
            [:centralized-metrics/track :metric/swap-asset-to-receive-changed
             {:network        (:chain-id network)
              :previous_token (:symbol previous-token)
              :new_token      (:symbol token)}]]]})))

(rf/reg-event-fx :wallet.swap/set-default-slippage
 (fn [{:keys [db]}]
   {:db (assoc-in db [:wallet :ui :swap :max-slippage] constants/default-slippage)}))

(rf/reg-event-fx :wallet.swap/set-max-slippage
 (fn [{:keys [db]} [max-slippage]]
   {:db (assoc-in db [:wallet :ui :swap :max-slippage] (number/parse-float max-slippage))}))

(rf/reg-event-fx :wallet.swap/set-loading-swap-proposal
 (fn [{:keys [db]}]
   {:db (assoc-in db [:wallet :ui :swap :loading-swap-proposal?] true)}))

(defn- get-swap-proposal-params
  [{:keys [db amount-in amount-out request-uuid]}]
  (let [wallet-address          (get-in db [:wallet :current-viewing-account-address])
        {:keys [asset-to-pay asset-to-receive
                network]}       (get-in db [:wallet :ui :swap])
        test-networks-enabled?  (get-in db [:profile/profile :test-networks-enabled?])
        networks                ((if test-networks-enabled? :test :prod)
                                 (get-in db [:wallet :networks]))
        network-chain-ids       (map :chain-id networks)
        pay-token-decimal       (:decimals asset-to-pay)
        pay-token-id            (:symbol asset-to-pay)
        receive-token-id        (:symbol asset-to-receive)
        gas-rates               constants/gas-rate-medium
        receive-token-decimals  (:decimals asset-to-receive)
        amount-in-hex           (if amount-in
                                  (send-utils/amount-in-hex amount-in pay-token-decimal)
                                  0)
        amount-out-hex          (when amount-out
                                  (send-utils/amount-in-hex amount-out receive-token-decimals))
        to-address              wallet-address
        from-address            wallet-address
        swap-chain-id           (:chain-id network)
        disabled-to-chain-ids   (filter #(not= % swap-chain-id) network-chain-ids)
        disabled-from-chain-ids (filter #(not= % swap-chain-id) network-chain-ids)
        from-locked-amount      {}
        send-type               constants/send-type-swap
        params                  [(cond->
                                   {:uuid                 request-uuid
                                    :sendType             send-type
                                    :addrFrom             from-address
                                    :addrTo               to-address
                                    :tokenID              pay-token-id
                                    :toTokenID            receive-token-id
                                    :disabledFromChainIDs disabled-from-chain-ids
                                    :disabledToChainIDs   disabled-to-chain-ids
                                    :gasFeeMode           gas-rates
                                    :fromLockedAmount     from-locked-amount
                                    :amountOut            (or amount-out-hex "0x0")}
                                   amount-in (assoc :amountIn amount-in-hex))]]
    params))

(rf/reg-event-fx :wallet/start-get-swap-proposal
 (fn [{:keys [db]} [{:keys [amount-in amount-out clean-approval-transaction?]}]]
   (let [{:keys [asset-to-pay asset-to-receive
                 network]} (get-in db [:wallet :ui :swap])
         pay-token-decimal (:decimals asset-to-pay)
         pay-token-id      (:symbol asset-to-pay)
         receive-token-id  (:symbol asset-to-receive)
         amount-in-hex     (if amount-in
                             (send-utils/amount-in-hex amount-in pay-token-decimal)
                             0)
         swap-chain-id     (:chain-id network)
         request-uuid      (str (random-uuid))
         params            (get-swap-proposal-params
                            {:db           db
                             :amount-in    amount-in
                             :amount-out   amount-out
                             :request-uuid request-uuid})]
     (when-let [amount (or amount-in amount-out)]
       {:db            (update-in db
                                  [:wallet :ui :swap]
                                  #(cond-> %
                                     :always
                                     (assoc
                                      :last-request-uuid request-uuid
                                      :amount            amount
                                      :amount-hex        amount-in-hex
                                      :initial-response? true)
                                     clean-approval-transaction?
                                     (dissoc :approval-transaction-id :approved-amount :swap-proposal)))
        :fx            [[:dispatch
                         [:centralized-metrics/track :metric/swap-proposal-start
                          {:network       swap-chain-id
                           :pay_token     pay-token-id
                           :receive_token receive-token-id}]]]
        :json-rpc/call [{:method   "wallet_getSuggestedRoutesAsync"
                         :params   params
                         :on-error (fn [error]
                                     (rf/dispatch [:wallet/swap-proposal-error error])
                                     (log/error "failed to get suggested routes (async)"
                                                {:event  :wallet/start-get-swap-proposal
                                                 :error  (:message error)
                                                 :params params}))}]}))))

(rf/reg-event-fx :wallet/swap-proposal-success
 (fn [{:keys [db]} [swap-proposal]]
   (let [last-request-uuid    (get-in db [:wallet :ui :swap :last-request-uuid])
         amount-hex           (get-in db [:wallet :ui :swap :amount-hex])
         asset-to-pay         (get-in db [:wallet :ui :swap :asset-to-pay])
         asset-to-receive     (get-in db [:wallet :ui :swap :asset-to-receive])
         network              (get-in db [:wallet :ui :swap :network])
         initial-response?    (get-in db [:wallet :ui :swap :initial-response?])
         view-id              (:view-id db)
         request-uuid         (:uuid swap-proposal)
         best-routes          (:best swap-proposal)
         updated-token-prices (:updated-prices swap-proposal)
         error-response       (:error-response swap-proposal)]
     (when (and (= request-uuid last-request-uuid)
                (or (and (empty? best-routes) error-response)
                    (and
                     (pos? (count best-routes))
                     (= (:amount-in (first best-routes)) amount-hex))))
       (cond-> {:db (update-in db
                               [:wallet :ui :swap]
                               assoc
                               :swap-proposal          (when-not (empty? best-routes)
                                                         (assoc (first best-routes) :uuid request-uuid))
                               :error-response         error-response
                               :updated-token-prices   updated-token-prices
                               :loading-swap-proposal? false
                               :initial-response?      false)}
         (and initial-response? (seq best-routes))
         (assoc :fx
                [[:dispatch
                  [:centralized-metrics/track :metric/swap-proposal-received
                   {:network       (:chain-id network)
                    :pay_token     (:symbol asset-to-pay)
                    :receive_token (:symbol asset-to-receive)}]]])
         (and initial-response? (empty? best-routes))
         (assoc :fx
                [[:dispatch
                  [:centralized-metrics/track :metric/swap-proposal-failed
                   {:error (:code error-response)}]]])
         ;; Router is unstable and it can return a swap proposal and after auto-refetching it can
         ;; return an error. Ideally this shouldn't happen, but adding this behavior so if the
         ;; user is in swap confirmation screen or in token approval confirmation screen, we
         ;; navigate back to setup swap screen so proper error is displayed.
         (and (empty? best-routes) (= view-id :screen/wallet.swap-set-spending-cap))
         (assoc :fx
                [[:dispatch
                  [:centralized-metrics/track :metric/swap-proposal-failed
                   {:error (:code error-response)}]]
                 [:dismiss-modal :screen/wallet.swap-set-spending-cap]])
         (and (empty? best-routes) (= view-id :screen/wallet.swap-confirmation))
         (assoc :fx
                [[:dispatch
                  [:centralized-metrics/track :metric/swap-proposal-failed
                   {:error (:code error-response)}]]
                 [:navigate-back]]))))))

(rf/reg-event-fx :wallet/swap-proposal-error
 (fn [{:keys [db]} [error-response]]
   {:db (-> db
            (update-in [:wallet :ui :swap] dissoc :route :swap-proposal)
            (assoc-in [:wallet :ui :swap :loading-swap-proposal?] false)
            (assoc-in [:wallet :ui :swap :error-response] error-response))
    :fx [[:dispatch
          [:centralized-metrics/track :metric/swap-proposal-failed {:error (:code error-response)}]]]}))

(rf/reg-event-fx :wallet/stop-get-swap-proposal
 (fn [{:keys [db]}]
   {:db            (update-in db [:wallet :ui :swap] dissoc :last-request-uuid)
    :json-rpc/call [{:method   "wallet_stopSuggestedRoutesAsyncCalculation"
                     :params   []
                     :on-error (fn [error]
                                 (log/error "failed to stop fetching swap proposals"
                                            {:event :wallet/stop-get-swap-proposal
                                             :error error}))}]}))

(rf/reg-event-fx
 :wallet/clean-swap-proposal
 (fn [{:keys [db]} [{:keys [clean-amounts? clean-approval-transaction?]}]]
   (let [keys-to-dissoc (cond-> [:last-request-uuid
                                 :swap-proposal
                                 :error-response
                                 :loading-swap-proposal?]
                          clean-amounts?              (conj :amount :amount-hex)
                          clean-approval-transaction? (conj :approval-transaction-id :approved-amount))]
     {:db (apply update-in db [:wallet :ui :swap] dissoc keys-to-dissoc)
      :fx [[:dispatch [:wallet/stop-get-swap-proposal]]]})))

(rf/reg-event-fx :wallet/clean-swap
 (fn [{:keys [db]}]
   {:db (update-in db [:wallet :ui] dissoc :swap)}))

(rf/reg-event-fx :wallet.swap/add-authorized-transaction
 (fn [{:keys [db]} [{:keys [sent-transactions swap-data approval-transaction?]}]]
   (let [wallet-transactions  (get-in db [:wallet :transactions] {})
         transactions         (utils/transactions->hash-to-transaction-map sent-transactions)
         transaction-ids      (keys transactions)
         transaction-id       (first transaction-ids)
         transaction-details  (cond-> transactions
                                :always   (assoc-in [transaction-id :tx-type] :swap)
                                swap-data (assoc-in [transaction-id :swap-data] swap-data))
         swap-transaction-ids (get-in db [:wallet :swap-transaction-ids])]
     {:db (cond-> db
            :always                     (assoc-in [:wallet :transactions]
                                         (merge wallet-transactions transaction-details))
            :always                     (assoc-in [:wallet :ui :swap :transaction-ids] transaction-ids)
            approval-transaction?       (assoc-in [:wallet :ui :swap :approval-transaction-id]
                                         transaction-id)
            (not approval-transaction?) (assoc-in [:wallet :swap-transaction-ids]
                                         (if swap-transaction-ids
                                           (conj swap-transaction-ids transaction-id)
                                           (hash-set transaction-id))))})))

(rf/reg-event-fx :wallet.swap/approve-transaction-update
 (fn [{:keys [db]} [{:keys [status]}]]
   (let [{:keys [amount asset-to-pay swap-proposal
                 network]}                (get-in db [:wallet :ui :swap])
         provider-name                    (:bridge-name swap-proposal)
         token-symbol                     (:symbol asset-to-pay)
         swap-chain-id                    (:chain-id network)
         current-viewing-account-address  (get-in db
                                                  [:wallet :current-viewing-account-address])
         account-name                     (get-in db
                                                  [:wallet :accounts
                                                   current-viewing-account-address :name])
         transaction-confirmed-or-failed? (#{:confirmed :failed} status)
         transaction-confirmed?           (= status :confirmed)]
     (when transaction-confirmed-or-failed?
       (cond-> {:fx
                [[:dispatch
                  [:centralized-metrics/track :metric/swap-approval-execution-finished
                   {:network   swap-chain-id
                    :pay_token token-symbol
                    :succeeded transaction-confirmed?}]]
                 [:dispatch
                  [:toasts/upsert
                   {:id   :approve-transaction-update
                    :type (if transaction-confirmed? :positive :negative)
                    :text (if transaction-confirmed?
                            (i18n/label :t/spending-cap-set
                                        {:token-amount  amount
                                         :token-symbol  token-symbol
                                         :provider-name provider-name
                                         :account-name  account-name})
                            (i18n/label :t/spending-cap-failed
                                        {:token-amount  amount
                                         :token-symbol  token-symbol
                                         :provider-name provider-name
                                         :account-name  account-name}))}]]]}
         transaction-confirmed?
         (assoc :db (assoc-in db [:wallet :ui :swap :approved-amount] amount))
         (not transaction-confirmed?)
         (assoc :db (update-in db [:wallet :ui :swap] dissoc :approval-transaction-id)))))))

(rf/reg-event-fx
 :wallet.swap/swap-transaction-update
 (fn [{:keys [db]} [{:keys [tx-hash status]}]]
   (let [{:keys [pay-amount pay-token-symbol
                 receive-amount receive-token-symbol
                 swap-chain-id]}          (get-in db
                                                  [:wallet :transactions tx-hash
                                                   :swap-data])
         transaction-confirmed-or-failed? (#{:confirmed :failed} status)
         transaction-confirmed?           (= status :confirmed)]
     (when transaction-confirmed-or-failed?
       {:db (-> db
                (update-in [:wallet :swap-transaction-ids] disj tx-hash)
                (update-in [:wallet :transactions] dissoc tx-hash))
        :fx [[:dispatch
              [:centralized-metrics/track :metric/swap-transaction-execution-finished
               {:network       swap-chain-id
                :pay_token     pay-token-symbol
                :receive_token receive-token-symbol
                :succeeded     transaction-confirmed?}]]
             [:dispatch
              [:toasts/upsert
               {:id   :swap-transaction-update
                :type (if transaction-confirmed? :positive :negative)
                :text (if transaction-confirmed?
                        (i18n/label :t/swapped-to
                                    {:pay-amount           pay-amount
                                     :pay-token-symbol     pay-token-symbol
                                     :receive-token-symbol receive-token-symbol
                                     :receive-amount       receive-amount})
                        (i18n/label :t/swap-failed))}]]]}))))

(rf/reg-event-fx
 :wallet.swap/flip-assets
 (fn [{:keys [db]}]
   (let [{:keys [asset-to-pay asset-to-receive
                 swap-proposal amount network]} (get-in db [:wallet :ui :swap])
         receive-token-decimals                 (:decimals asset-to-receive)
         amount-out                             (when swap-proposal (:amount-out swap-proposal))
         receive-amount                         (when amount-out
                                                  (-> amount-out
                                                      (number/hex->whole receive-token-decimals)
                                                      (money/to-fixed receive-token-decimals)))]
     {:db (update-in db
                     [:wallet :ui :swap]
                     #(-> %
                          (assoc
                           :asset-to-pay     asset-to-receive
                           :asset-to-receive asset-to-pay
                           :amount           (or receive-amount amount))
                          (dissoc :swap-proposal
                                  :error-response
                                  :loading-swap-proposal?
                                  :last-request-uuid
                                  :approved-amount
                                  :approval-transaction-id)))
      :fx [[:dispatch
            [:centralized-metrics/track :metric/swap-asset-to-pay-changed
             {:network        (:chain-id network)
              :previous_token (:symbol asset-to-pay)
              :new_token      (:symbol asset-to-receive)}]]
           [:dispatch
            [:centralized-metrics/track :metric/swap-asset-to-receive-changed
             {:network        (:chain-id network)
              :previous_token (:symbol asset-to-receive)
              :new_token      (:symbol asset-to-pay)}]]]})))

(rf/reg-event-fx
 :wallet.swap/set-sign-transactions-callback-fx
 (fn [{:keys [db]} [callback-fx]]
   {:db (assoc-in db [:wallet :ui :swap :sign-transactions-callback-fx] callback-fx)}))

(rf/reg-event-fx
 :wallet.swap/review-swap
 (fn [{:keys [db]}]
   {:db (-> db
            (update-in [:wallet :ui :swap] dissoc :transaction-for-signing))
    :fx [[:dispatch
          [:navigate-to-within-stack
           [:screen/wallet.swap-confirmation
            :screen/wallet.setup-swap]]]]}))

(rf/reg-event-fx
 :wallet/prepare-signatures-for-swap-transactions
 (fn [{:keys [db]}]
   (let [last-request-uuid (get-in db [:wallet :ui :swap :last-request-uuid])
         max-slippage      (get-in db [:wallet :ui :swap :max-slippage])]
     {:fx [[:dispatch
            [:wallet/build-transactions-from-route
             {:request-uuid last-request-uuid
              :slippage     max-slippage}]]
           [:dispatch
            [:wallet.swap/set-sign-transactions-callback-fx
             [:dispatch [:wallet/prepare-signatures-for-transactions :swap]]]]]})))

(defn transaction-approval-required?
  [transactions {:keys [swap-proposal approval-transaction-id]}]
  (let [approval-transaction (when approval-transaction-id
                               (get transactions approval-transaction-id))
        already-approved?    (and approval-transaction
                                  (= (:status approval-transaction)
                                     :confirmed))]
    (and (:approval-required swap-proposal)
         (not already-approved?))))

(rf/reg-event-fx
 :wallet.swap/mark-as-pending
 (fn [{:keys [db]} [transaction-id]]
   {:db (-> db
            (assoc-in [:wallet :transactions transaction-id :status] :pending)
            (assoc-in [:wallet :ui :swap :approval-transaction-id] transaction-id))}))

(rf/reg-event-fx
 :wallet.swap/transaction-success
 (fn [{:keys [db]} [sent-transactions]]
   (let [transactions           (get-in db [:wallet :transactions])
         {:keys [swap-proposal
                 asset-to-pay
                 asset-to-receive
                 network
                 amount]
          :as   swap}           (get-in db [:wallet :ui :swap])
         swap-chain-id          (:chain-id network)
         token-id-from          (:symbol asset-to-pay)
         token-id-to            (:symbol asset-to-receive)
         receive-token-decimals (:decimals asset-to-receive)
         amount-out             (:amount-out swap-proposal)
         receive-amount         (when amount-out
                                  (-> amount-out
                                      (number/hex->whole receive-token-decimals)
                                      (money/to-fixed receive-token-decimals)))
         approval-required?     (transaction-approval-required? transactions swap)]
     {:fx [[:dispatch
            [:centralized-metrics/track
             (if approval-required?
               :metric/swap-approval-execution-start
               :metric/swap-transaction-execution-start)
             (cond-> {:network   swap-chain-id
                      :pay_token token-id-from}
               (not approval-required?)
               (assoc :receive_token token-id-to))]]
           [:dispatch
            [:wallet.swap/add-authorized-transaction
             (cond-> {:sent-transactions     sent-transactions
                      :approval-transaction? approval-required?}
               (not approval-required?)
               (assoc :swap-data
                      {:pay-token-symbol     token-id-from
                       :pay-amount           amount
                       :receive-token-symbol token-id-to
                       :receive-amount       receive-amount
                       :swap-chain-id        swap-chain-id}))]]
           (when approval-required?
             ;; dismiss the spending cap dialog if the transaction needs to be approved
             [:dispatch [:dismiss-modal :screen/wallet.swap-set-spending-cap]])
           (when approval-required?
             [:dispatch [:wallet.swap/mark-as-pending (-> sent-transactions first :hash)]])
           (when-not approval-required?
             ;; just end the whole transaction flow if no approval needed
             [:dispatch [:wallet.swap/end-transaction-flow]])
           (when-not approval-required?
             [:dispatch-later
              {:ms       500
               :dispatch [:toasts/upsert
                          {:id   :swap-transaction-pending
                           :icon :i/info
                           :type :neutral
                           :text (i18n/label :t/swapping-to
                                             {:pay-amount           amount
                                              :pay-token-symbol     token-id-from
                                              :receive-token-symbol token-id-to
                                              :receive-amount       receive-amount})}]}])]})))

(rf/reg-event-fx
 :wallet.swap/transaction-failure
 (fn [{:keys [db]} [{:keys [details] :as error}]]
   (let [transactions       (get-in db [:wallet :transactions])
         {:keys [asset-to-pay
                 asset-to-receive
                 network]
          :as   swap}       (get-in db [:wallet :ui :swap])
         swap-chain-id      (:chain-id network)
         token-id-from      (:symbol asset-to-pay)
         token-id-to        (:symbol asset-to-receive)
         approval-required? (transaction-approval-required? transactions swap)]
     {:fx [[:centralized-metrics/track
            (if approval-required?
              :metric/swap-approval-execution-failed
              :metric/swap-transaction-execution-failed)
            (cond-> {:network   swap-chain-id
                     :error     error
                     :pay_token token-id-from}
              (not approval-required?)
              (assoc :receive_token token-id-to))]
           [:dispatch [:wallet.swap/end-transaction-flow]]
           [:dispatch
            [:toasts/upsert
             {:id   :send-transaction-error
              :type :negative
              :text (or details "An error occured")}]]]})))

(rf/reg-event-fx
 :wallet.swap/clean-up-transaction-flow
 (fn [{:keys [db]}]
   (let [transactions       (get-in db [:wallet :transactions])
         swap               (get-in db [:wallet :ui :swap])
         approval-required? (transaction-approval-required? transactions swap)]
     {:db (update-in db [:wallet :ui] dissoc :swap)
      :fx [[:dispatch
            [:dismiss-modal
             (if approval-required?
               :screen/wallet.swap-set-spending-cap
               :screen/wallet.swap-confirmation)]]]})))

(rf/reg-event-fx
 :wallet.swap/end-transaction-flow
 (fn [{:keys [db]}]
   (let [address (get-in db [:wallet :current-viewing-account-address])]
     {:fx [[:dispatch [:wallet/navigate-to-account-within-stack address]]
           [:dispatch [:wallet/select-account-tab :activity]]
           [:dispatch-later
            [{:ms       20
              :dispatch [:wallet.swap/clean-up-transaction-flow]}]]]})))

(rf/reg-event-fx :wallet.swap/start-from-account
 (fn [{:keys [db]} [account]]
   (let [asset-to-pay     (get-in db [:wallet :ui :swap :asset-to-pay])
         asset-to-receive (get-in db [:wallet :ui :swap :asset-to-receive])]
     {:fx (if asset-to-pay
            [[:dispatch [:dismiss-modal :screen/wallet.swap-select-account]]
             [:dispatch
              [:wallet.swap/start
               {:asset-to-pay     asset-to-pay
                :asset-to-receive asset-to-receive
                :open-new-screen? true
                :from-account     account}]]]
            [[:dispatch [:wallet/switch-current-viewing-account (:address account)]]
             [:dispatch
              [:navigate-to-within-stack
               [:screen/wallet.swap-select-asset-to-pay :screen/wallet.swap-select-account]]]])})))

(rf/reg-event-fx :wallet/get-swap-proposal-fee
 (fn [{:keys [db]} [{:keys [amount-in amount-out]}]]
   (let [request-uuid (str (random-uuid))
         params       (get-swap-proposal-params
                       {:db           db
                        :amount-in    amount-in
                        :amount-out   amount-out
                        :request-uuid request-uuid})]
     {:db            (assoc-in db [:wallet :ui :swap :loading-swap-proposal-fee?] true)
      :json-rpc/call [{:method     "wallet_getSuggestedRoutes"
                       :params     params
                       :on-success (fn [data]
                                     (let [swap-proposal (data-store/fix-routes data)]
                                       (rf/dispatch [:wallet/swap-proposal-fee-success
                                                     swap-proposal])))
                       :on-error   (fn [error]
                                     (rf/dispatch [:wallet/swap-proposal-fee-error])
                                     (log/error "failed to get suggested routes"
                                                {:event  :wallet/get-swap-proposal-fee
                                                 :error  (:message error)
                                                 :params params}))}]})))

(rf/reg-event-fx
 :wallet/swap-proposal-fee-success
 (fn [{:keys [db]} [swap-proposal]]
   (let [best-routes         (:best swap-proposal)
         selected-route      (first best-routes)
         relevant-fee-fields [:gas-amount :gas-fees :token-fees :approval-required
                              :approval-fee :approval-l-1-fee :bonder-fees]
         fee-data            (select-keys selected-route relevant-fee-fields)]
     {:db (update-in db
                     [:wallet :ui :swap]
                     assoc
                     :loading-swap-proposal-fee? false
                     :swap-proposal
                     (when-not (empty? best-routes)
                       fee-data))})))

(rf/reg-event-fx
 :wallet/swap-proposal-fee-error
 (fn [{:keys [db]}]
   {:db (update-in db
                   [:wallet :ui :swap]
                   assoc
                   :loading-swap-proposal-fee?
                   false)}))
