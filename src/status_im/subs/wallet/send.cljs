(ns status-im.subs.wallet.send
  (:require
    [re-frame.core :as rf]
    [status-im.constants :as constants]
    [status-im.contexts.wallet.common.activity-tab.constants :as activity-tab-constants]
    [status-im.contexts.wallet.common.utils :as common-utils]
    [status-im.contexts.wallet.common.utils.networks :as network-utils]
    [status-im.contexts.wallet.networks.core :as networks]
    [status-im.contexts.wallet.send.utils :as send-utils]
    [utils.money :as money]
    [utils.number :as number]))

(rf/reg-sub
 :wallet/send-tab
 :<- [:wallet/ui]
 (fn [ui]
   (get-in ui [:send :select-address-tab])))

(rf/reg-sub
 :wallet/wallet-send
 :<- [:wallet/ui]
 :-> :send)

(rf/reg-sub
 :wallet/send-recipient
 :<- [:wallet/wallet-send]
 :-> :recipient)

(rf/reg-sub
 :wallet/send-route
 :<- [:wallet/wallet-send]
 :-> :route)

(rf/reg-sub
 :wallet/send-token-symbol
 :<- [:wallet/wallet-send]
 :-> :token-symbol)

(rf/reg-sub
 :wallet/send-transaction-ids
 :<- [:wallet/wallet-send]
 :-> :transaction-ids)

(rf/reg-sub
 :wallet/send-amount
 :<- [:wallet/wallet-send]
 :-> :amount)

(rf/reg-sub
 :wallet/send-tx-type
 :<- [:wallet/wallet-send]
 :-> :tx-type)

(rf/reg-sub
 :wallet/send-network
 :<- [:wallet/wallet-send]
 :-> :network)

(rf/reg-sub
 :wallet/sending-collectible?
 :<- [:wallet/send-tx-type]
 #(send-utils/tx-type-collectible? %))

(rf/reg-sub
 :wallet/send-transaction-progress
 :<- [:wallet/send-transaction-ids]
 :<- [:wallet/transactions]
 (fn [[tx-ids transactions]]
   (let [send-tx-ids (set (keys transactions))]
     (select-keys transactions
                  (filter send-tx-ids tx-ids)))))

(rf/reg-sub
 :wallet/recent-recipients
 :<- [:wallet/all-activities]
 :<- [:wallet/current-viewing-account-address]
 (fn [[all-activities current-viewing-account-address]]
   (let [address-activity (vals (get all-activities current-viewing-account-address))]
     (->> address-activity
          (sort :timestamp)
          (keep (fn [{:keys [activity-type recipient]}]
                  (when (= activity-tab-constants/wallet-activity-type-send activity-type)
                    recipient)))
          (distinct)))))

(rf/reg-sub
 :wallet/bridge-from-networks
 :<- [:wallet/wallet-send]
 :<- [:wallet/active-networks]
 (fn [[{:keys [bridge-to-chain-id]} networks]]
   (set (filter (fn [network]
                  (not= (:chain-id network) bridge-to-chain-id))
                networks))))

(rf/reg-sub
 :wallet/bridge-from-chain-ids
 :<- [:wallet/wallet-send]
 :<- [:wallet/active-networks]
 (fn [[{:keys [bridge-to-chain-id]} networks]]
   (keep (fn [network]
           (when (not= (:chain-id network) bridge-to-chain-id)
             (:chain-id network)))
         networks)))

(rf/reg-sub
 :wallet/send-token-decimals
 :<- [:wallet/wallet-send]
 (fn [{:keys [token collectible]}]
   (if collectible 0 (:decimals token))))

(rf/reg-sub
 :wallet/send-display-token-decimals
 :<- [:wallet/wallet-send]
 :<- [:wallet/prices-per-token]
 (fn [[{:keys [token collectible]} prices-per-token]]
   (if collectible
     0
     (-> token
         (common-utils/token-usd-price prices-per-token)
         common-utils/one-cent-value
         common-utils/calc-max-crypto-decimals
         (min constants/min-token-decimals-to-display)))))

(rf/reg-sub
 :wallet/send-native-token?
 :<- [:wallet/wallet-send]
 (fn [{:keys [token token-display-name]}]
   (and token (= token-display-name "ETH"))))

(rf/reg-sub
 :wallet/total-amount
 :<- [:wallet/send-route]
 :<- [:wallet/send-token-decimals]
 :<- [:wallet/send-native-token?]
 (fn [[route token-decimals native-token?]]
   (let [default-amount (money/bignumber 0)]
     (if route
       (->> (send-utils/estimated-received-by-chain
             route
             token-decimals
             native-token?)
            vals
            (reduce money/add default-amount))
       default-amount))))

(rf/reg-sub
 :wallet/send-total-amount-formatted
 :<- [:wallet/total-amount]
 :<- [:wallet/send-display-token-decimals]
 :<- [:wallet/wallet-send-token-symbol]
 (fn [[amount token-decimals token-symbol]]
   (-> amount
       (number/to-fixed token-decimals)
       (str " " token-symbol))))

(rf/reg-sub
 :wallet/send-amount-fixed
 :<- [:wallet/send-display-token-decimals]
 (fn [token-decimals [_ amount]]
   (number/to-fixed (money/->bignumber amount) token-decimals)))

(rf/reg-sub
 :wallet/bridge-to-network-details
 :<- [:wallet/wallet-send]
 :<- [:wallet/networks-by-id]
 (fn [[{:keys [bridge-to-chain-id]} networks-by-id]]
   (get networks-by-id bridge-to-chain-id)))

(rf/reg-sub
 :wallet/send-token-grouped-networks
 :<- [:wallet/wallet-send-token]
 (fn [token]
   (let [{token-networks :networks} token
         grouped-networks           (group-by :layer
                                              token-networks)
         mainnet-network            (first (get grouped-networks constants/layer-1-network))
         layer-2-networks           (get grouped-networks constants/layer-2-network)]
     {:mainnet-network  mainnet-network
      :layer-2-networks layer-2-networks})))

(rf/reg-sub
 :wallet/send-token-network-balance
 :<- [:wallet/wallet-send-token]
 :<- [:profile/currency]
 :<- [:profile/currency-symbol]
 :<- [:wallet/prices-per-token]
 (fn [[token currency currency-symbol prices-per-token] [_ chain-id]]
   (let [{:keys [balances-per-chain
                 decimals]} token
         balance-for-chain  (get balances-per-chain chain-id)
         total-balance      (money/token->unit (:raw-balance balance-for-chain) decimals)
         fiat-value         (common-utils/calculate-token-fiat-value
                             {:currency         currency
                              :balance          total-balance
                              :token            token
                              :prices-per-token prices-per-token})
         crypto-formatted   (common-utils/get-standard-crypto-format token
                                                                     total-balance
                                                                     prices-per-token)
         fiat-formatted     (common-utils/fiat-formatted-for-ui currency-symbol
                                                                fiat-value)]
     {:crypto (str crypto-formatted " " (:symbol token))
      :fiat   fiat-formatted})))

(rf/reg-sub
 :wallet/send-estimated-time
 :<- [:wallet/send-best-route]
 (fn [route]
   (-> route
       :estimated-time
       common-utils/estimated-time-v2-format)))

(rf/reg-sub
 :wallet/send-enough-assets?
 :<- [:wallet/wallet-send]
 (fn [{:keys [enough-assets?]}]
   (if (nil? enough-assets?) true enough-assets?)))

(rf/reg-sub
 :wallet/no-routes-found?
 :<- [:wallet/wallet-send-loading-suggested-routes?]
 :<- [:wallet/send-route]
 (fn [[loading? route]]
   (and (empty? route) (not loading?))))

(rf/reg-sub :wallet/send-selected-network
 :<- [:wallet/networks-by-id]
 :<- [:wallet/wallet-send]
 (fn [[networks {:keys [to-values-by-chain]}]]
   (->> to-values-by-chain
        keys
        first
        (get networks))))

(rf/reg-sub
 :wallet/send-network-values
 :<- [:wallet/networks-by-id]
 :<- [:wallet/wallet-send]
 (fn [[networks {:keys [from-values-by-chain to-values-by-chain token-display-name token] :as send-data}]
      [_ to-values?]]
   (let [network-values (if to-values? to-values-by-chain from-values-by-chain)
         token-symbol   (or token-display-name
                            (-> send-data :token :symbol))
         token-decimals (:decimals token)]
     (reduce-kv
      (fn [acc chain-id amount]
        (let [network-name (-> networks (get chain-id) :network-name)
              amount-fixed (number/to-fixed (money/->bignumber amount) token-decimals)]
          (merge acc (network-utils/network-summary network-name token-symbol amount-fixed))))
      {}
      network-values))))

(rf/reg-sub
 :wallet/bridge-to-networks
 :<- [:wallet/active-networks]
 :<- [:wallet/send-network]
 (fn [[networks send-network]]
   (let [available-networks-for-bridge (remove #(= (:chain-id send-network)
                                                   (:chain-id %))
                                               networks)]
     {:layer-1 (networks/get-networks-for-layer available-networks-for-bridge 1)
      :layer-2 (networks/get-networks-for-layer available-networks-for-bridge 2)})))
