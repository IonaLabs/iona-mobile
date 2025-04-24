(ns status-im.contexts.wallet.send.events-test
  (:require
    [cljs.test :refer-macros [is testing]]
    [matcher-combinators.matchers :as m]
    matcher-combinators.test
    [re-frame.db :as rf-db]
    status-im.contexts.wallet.send.events
    [status-im.contexts.wallet.send.utils :as send-utils]
    [test-helpers.unit :as h]
    [utils.money :as money]))

(defn make-send-structure
  [send-data]
  {:wallet {:ui {:send send-data}}})

(defn extract-send-key
  [result send-key]
  (get-in result [:db :wallet :ui :send send-key]))

(defn collectible-with-balance
  [balance]
  {:name "DOG #1"
   :description
   "dogs are cute and this one is the cutestdogs are cute and this one is the cutest"
   :ownership [{:address "0x01"
                :balance balance}]
   :id {:contract-id {:address  "0x11"
                      :chain-id 1}
        :token-id    "some-id"}})

(h/deftest-event :wallet/set-token-to-send
  [event-id dispatch]
  (let [token-symbol      "ETH"
        token             {:symbol   "ETH"
                           :name     "Ether"
                           :networks #{{:chain-id 421614}
                                       {:chain-id 11155420}
                                       {:chain-id 11155111}}}
        receiver-networks [421614 11155420]]
    (testing "can be called with :token"
      (let [initial-db  (make-send-structure {:receiver-networks receiver-networks})
            expected-db (make-send-structure {:token-display-name token-symbol
                                              :token-symbol       token-symbol})
            _ (reset! rf-db/app-db initial-db)
            result      (dispatch [event-id {:token token}])]
        (is (match? expected-db (:db result)))))
    (testing "can be called with :token-symbol"
      (let [initial-db  (make-send-structure {:receiver-networks receiver-networks})
            expected-db (make-send-structure {:token-symbol token-symbol})
            _ (reset! rf-db/app-db initial-db)
            result      (dispatch [event-id {:token-symbol token-symbol}])]
        (is (match? expected-db (:db result)))))
    (testing "shouldn't have changes if called without :token or :token-symbol")
    (let [initial-db  (make-send-structure {:receiver-networks receiver-networks})
          expected-db nil
          _ (reset! rf-db/app-db initial-db)
          result      (dispatch [event-id {}])]
      (is (match? expected-db (:db result))))
    (testing "should clean :collectible set"
      (let [initial-db  (make-send-structure {:receiver-networks receiver-networks
                                              :collectible       "some-collectible"})
            expected-db (make-send-structure {:token-display-name token-symbol})
            _ (reset! rf-db/app-db initial-db)
            result      (dispatch [event-id
                                   {:token   token
                                    :network "network"}])]
        (is (match? expected-db (:db result)))
        (is (match? nil (extract-send-key result :collectible)))))
    (testing "should set :token-not-supported-in-receiver-networks?"
      (let [initial-db  (make-send-structure {:receiver-networks []})
            expected-db (make-send-structure {:token-display-name token-symbol})
            _ (reset! rf-db/app-db initial-db)
            result      (dispatch [event-id {:token token}])]
        (is (match? expected-db (:db result)))))))

(h/deftest-event :wallet/edit-token-to-send
  [event-id dispatch]
  (let [token-symbol      "ETH"
        token             {:symbol             "ETH"
                           :name               "Ether"
                           :networks           #{{:chain-id 421614}
                                                 {:chain-id 11155420}
                                                 {:chain-id 11155111}}
                           :supported-networks #{{:chain-id 421614}
                                                 {:chain-id 11155420}
                                                 {:chain-id 11155111}}}
        receiver-networks [421614 11155420]]
    (testing "can be called with :token"
      (let [initial-db  (make-send-structure {:receiver-networks receiver-networks
                                              :token-display-name "DAI"
                                              :token-not-supported-in-receiver-networks? true})
            expected-db (make-send-structure {:token-display-name                        token-symbol
                                              :token-not-supported-in-receiver-networks? false})
            _ (reset! rf-db/app-db initial-db)
            result      (dispatch [event-id token])]
        (is (match? expected-db (:db result)))))
    (testing "should set :token-not-supported-in-receiver-networks?"
      (let [initial-db  (make-send-structure {:receiver-networks                         []
                                              :token-display-name                        "DAI"
                                              :token-not-supported-in-receiver-networks? false})
            expected-db (make-send-structure {:token-display-name                        token-symbol
                                              :token-not-supported-in-receiver-networks? true})
            _ (reset! rf-db/app-db initial-db)
            result      (dispatch [event-id token])]
        (is (match? expected-db (:db result)))))))

(h/deftest-event :wallet/set-collectible-to-send
  [event-id dispatch]
  (let
    [collectible
     {:data-type 2
      :id
      {:contract-id
       {:chain-id 11155111
        :address  "0x1ed60fedff775d500dde21a974cd4e92e0047cc8"}
       :token-id "15"}
      :contract-type 3
      :collectible-data
      {:name "DOG #1"
       :description
       "dogs are cute and this one is the cutestdogs are cute and this one is the cutest"}
      :collection-data
      {:name "ERC-1155 Faucet"}
      :ownership
      [{:address      "0xf90014b2027e584fc96e6f6c8078998fe46c5ccb"
        :balance      "1"
        :tx-timestamp 1710331776}]
      :preview-url
      {:uri
       "https://ipfs.io/ipfs/bafybeie7b7g7iibpac4k6ydw4m5ivgqw5vov7oyzlf4v5zoor57wokmsxy/isolated-happy-smiling-dog-white-background-portrait-4_1562-693.avif"}}
     initial-db (make-send-structure {:token {:symbol "ETH"}})
     _ (reset! rf-db/app-db initial-db)
     result (dispatch [event-id {:collectible collectible}])]
    (testing ":collectible field assigned"
      (is (match? collectible (extract-send-key result :collectible))))
    (testing ":token should be removed"
      (is (match? nil (extract-send-key result :token))))
    (testing ":token-display-name assigned"
      (is (match? "DOG #1" (extract-send-key result :token-display-name))))
    (testing ":tx-type assigned"
      (is (match? :tx/collectible-erc-1155 (extract-send-key result :tx-type))))
    (testing "amount set if collectible was single"
      (is (match? 1 (extract-send-key result :amount))))))

(h/deftest-event :wallet/set-collectible-amount-to-send
  [event-id dispatch]
  (let [initial-db  (make-send-structure nil)
        expected-fx [[:dispatch [:wallet/start-get-suggested-routes {:amount 10}]]
                     [:dispatch
                      [:wallet/wizard-navigate-forward
                       {:current-screen nil :flow-id :wallet-send-flow}]]]
        amount      10
        _ (reset! rf-db/app-db initial-db)
        result      (dispatch [event-id {:amount amount}])]
    (testing "amount set"
      (is (match? amount (extract-send-key result :amount))))
    (testing "effects match"
      (is (match? expected-fx (:fx result))))))

(h/deftest-event :wallet/set-token-amount-to-send
  [event-id dispatch]
  (let [initial-db (make-send-structure {:token {:symbol "ETH"}})
        amount     10
        _ (reset! rf-db/app-db initial-db)
        result     (dispatch [event-id {:amount amount}])]
    (testing "amount set"
      (is (match? amount (extract-send-key result :amount))))))

(h/deftest-event :wallet/clean-send-data
  [event-id dispatch]
  (let [token-symbol      "ETH"
        token             {:symbol   "ETH"
                           :name     "Ether"
                           :networks #{{:chain-id 421614}
                                       {:chain-id 11155420}
                                       {:chain-id 11155111}}}
        receiver-networks [421614 11155420]
        expected-db       {:wallet {:ui {:other-props :value}}}]
    (reset! rf-db/app-db
      (-> (make-send-structure {:token-display-name token-symbol
                                :token              token
                                :receiver-networks  receiver-networks})
          (assoc-in [:wallet :ui :other-props] :value)))
    (is (match-strict? expected-db (:db (dispatch [event-id]))))))

(h/deftest-event :wallet/select-address-tab
  [event-id dispatch]
  (let [expected-db (make-send-structure {:select-address-tab "tab"})]
    (reset! rf-db/app-db (make-send-structure nil))
    (is (match? expected-db (:db (dispatch [event-id "tab"]))))))

(h/deftest-event :wallet/clean-send-address
  [event-id dispatch]
  (let [expected-db (make-send-structure {:other-props :value})]
    (reset! rf-db/app-db
      (make-send-structure {:to-address  "0x01"
                            :recipient   {:recipient-type :saved-address
                                          :label          "label"}
                            :other-props :value}))
    (is (match-strict? expected-db (:db (dispatch [event-id]))))))

(h/deftest-event :wallet/clean-send-amount
  [event-id dispatch]
  (let [expected-db (make-send-structure {:other-props :value})]
    (reset! rf-db/app-db
      (make-send-structure {:amount      10
                            :other-props :value}))
    (is (match-strict? expected-db (:db (dispatch [event-id]))))))

(h/deftest-event :wallet/clean-selected-token
  [event-id dispatch]
  (let [expected-db (make-send-structure {:other-props :value})]
    (reset! rf-db/app-db
      (make-send-structure {:other-props        :value
                            :token              "ETH"
                            :token-display-name "ETH"}))
    (is (match-strict? expected-db (:db (dispatch [event-id]))))))

(h/deftest-event :wallet/clean-selected-collectible
  [event-id dispatch]
  (let [expected-db (make-send-structure {:other-props :value})]
    (reset! rf-db/app-db
      (make-send-structure {:other-props        :value
                            :collectible        "ETH"
                            :token-display-name "ETH"
                            :amount             10}))
    (is (match-strict? expected-db (:db (dispatch [event-id]))))))

(h/deftest-event :wallet/suggested-routes-error
  [event-id dispatch]
  (let [sender-network-amounts   [{:chain-id 1 :total-amount (money/bignumber "100") :type :loading}
                                  {:chain-id 10 :total-amount (money/bignumber "200") :type :default}]
        receiver-network-amounts [{:chain-id 1 :total-amount (money/bignumber "100") :type :loading}]
        expected-result          {:db (make-send-structure
                                       {:sender-network-values
                                        (send-utils/reset-loading-network-amounts-to-zero
                                         sender-network-amounts)
                                        :receiver-network-values
                                        (send-utils/reset-loading-network-amounts-to-zero
                                         receiver-network-amounts)
                                        :loading-suggested-routes? false
                                        :suggested-routes {:best []}})
                                  :fx [[:dispatch
                                        [:toasts/upsert
                                         {:id   :send-transaction-error
                                          :type :negative
                                          :text "error"}]]]}]
    (reset! rf-db/app-db
      (make-send-structure {:sender-network-values     sender-network-amounts
                            :receiver-network-values   receiver-network-amounts
                            :route                     :values
                            :loading-suggested-routes? true}))
    (is (match? expected-result (dispatch [event-id "error"])))))

(h/deftest-event :wallet/reset-network-amounts-to-zero
  [event-id dispatch]
  (let [sender-network-values   [{:chain-id 1 :total-amount (money/bignumber "100") :type :default}
                                 {:chain-id 10 :total-amount (money/bignumber "200") :type :default}]
        receiver-network-values [{:chain-id 1 :total-amount (money/bignumber "100") :type :loading}]
        sender-network-zero     (send-utils/reset-network-amounts-to-zero sender-network-values)
        receiver-network-zero   (send-utils/reset-network-amounts-to-zero receiver-network-values)]
    (testing "if sender-network-value and receiver-network-value are not empty"
      (let [expected-db (make-send-structure {:other-props             :value
                                              :sender-network-values   sender-network-zero
                                              :receiver-network-values receiver-network-zero})]
        (reset! rf-db/app-db
          (make-send-structure {:other-props             :value
                                :sender-network-values   sender-network-values
                                :receiver-network-values receiver-network-values
                                :network-links           [{:from-chain-id 1
                                                           :to-chain-id   10
                                                           :position-diff 1}]}))
        (is (match? expected-db (:db (dispatch [event-id]))))))
    (testing "if only receiver-network-value is empty"
      (let [expected-db (make-send-structure {:other-props           :value
                                              :sender-network-values sender-network-zero})]
        (reset! rf-db/app-db
          (make-send-structure {:other-props             :value
                                :sender-network-values   sender-network-values
                                :receiver-network-values []
                                :network-links           [{:from-chain-id 1
                                                           :to-chain-id   10
                                                           :position-diff 1}]}))
        (is (match? expected-db (:db (dispatch [event-id]))))))
    (testing "if receiver-network-value and sender-network-values are empty"
      (let [expected-db (make-send-structure {:other-props :value})]
        (reset! rf-db/app-db
          (make-send-structure {:other-props             :value
                                :sender-network-values   []
                                :receiver-network-values []
                                :network-links           [{:from-chain-id 1
                                                           :to-chain-id   10
                                                           :position-diff 1}]}))
        (is (match? expected-db (:db (dispatch [event-id]))))))))

(h/deftest-event :wallet/select-send-address
  [event-id dispatch]
  (let [address     "eth:arb1:0x707f635951193ddafbb40971a0fcaab8a6415160"
        to-address  "0x707f635951193ddafbb40971a0fcaab8a6415160"
        recipient   {:type  :saved-address
                     :label "0x70...160"}
        stack-id    :screen/wallet.select-address
        start-flow? false
        tx-type     :tx/collectible-erc-721]
    (testing "testing when collectible balance is more than 1"
      (let [collectible      (collectible-with-balance 2)
            testnet-enabled? false
            expected-result  {:db (make-send-structure {:other-props :value
                                                        :recipient   recipient
                                                        :to-address  to-address
                                                        :tx-type     tx-type
                                                        :collectible collectible})
                              :fx [nil
                                   [:dispatch
                                    [:wallet/wizard-navigate-forward
                                     {:current-screen stack-id
                                      :start-flow?    start-flow?
                                      :flow-id        :wallet-send-flow}]]]}]
        (reset! rf-db/app-db
          {:wallet          {:current-viewing-account-address "0x01"
                             :ui                              {:send {:other-props :value
                                                                      :tx-type     tx-type
                                                                      :collectible collectible}}}
           :profile/profile {:test-networks-enabled? testnet-enabled?}})
        (is (match? expected-result
                    (dispatch [event-id
                               {:address     address
                                :recipient   recipient
                                :stack-id    stack-id
                                :start-flow? start-flow?}])))))
    (testing "testing when collectible balance is 1"
      (let [collectible      (collectible-with-balance 1)
            testnet-enabled? false
            expected-result  {:db (make-send-structure {:other-props :value
                                                        :recipient   recipient
                                                        :to-address  to-address
                                                        :tx-type     tx-type
                                                        :collectible collectible})
                              :fx [[:dispatch [:wallet/start-get-suggested-routes {:amount 1}]]
                                   [:dispatch
                                    [:wallet/wizard-navigate-forward
                                     {:current-screen stack-id
                                      :start-flow?    start-flow?
                                      :flow-id        :wallet-send-flow}]]]}]
        (reset! rf-db/app-db
          {:wallet          {:current-viewing-account-address "0x01"
                             :ui                              {:send {:other-props :value
                                                                      :tx-type     tx-type
                                                                      :collectible collectible}}}
           :profile/profile {:test-networks-enabled? testnet-enabled?}})
        (is (match? expected-result
                    (dispatch [event-id
                               {:address     address
                                :recipient   recipient
                                :stack-id    stack-id
                                :start-flow? start-flow?}])))))))

(h/deftest-event :wallet/suggested-routes-success
  [event-id dispatch]
  (let [timestamp                     :timestamp
        suggested-routes              {:Best
                                       [{:From           {:isTest    false
                                                          :chainName "Arbitrum"
                                                          :chainId   42161}
                                         :AmountInLocked false
                                         :AmountIn       "0x5af3107a4000"
                                         :MaxAmountIn    "0x4f7920c6831d6"
                                         :GasFees        {:gasPrice       "0.01"
                                                          :baseFee        "0.008750001"
                                                          :eip1559Enabled true}
                                         :BridgeName     "Transfer"
                                         :AmountOut      "0x5af3107a4000"
                                         :To             {:isTest    false
                                                          :chainName "Arbitrum"
                                                          :chainId   42161}
                                         :Cost           "0.006539438247064285301"
                                         :GasAmount      108197}]
                                       :Candidates
                                       [{:From           {:isTest    false
                                                          :chainName "Arbitrum"
                                                          :chainId   42161}
                                         :AmountInLocked false
                                         :AmountIn       "0x5af3107a4000"
                                         :MaxAmountIn    "0x4f7920c6831d6"
                                         :GasFees        {:gasPrice       "0.01"
                                                          :baseFee        "0.008750001"
                                                          :eip1559Enabled true}
                                         :BridgeName     "Transfer"
                                         :AmountOut      "0x5af3107a4000"
                                         :To             {:isTest    false
                                                          :chainName "Arbitrum"
                                                          :chainId   42161}
                                         :Cost           "0.006539438247064285301"
                                         :GasAmount      108197}
                                        {:From           {:isTest    false
                                                          :chainName "Ethereum"
                                                          :chainId   1}
                                         :AmountInLocked false
                                         :AmountIn       "0x0"
                                         :MaxAmountIn    "0x245aa392272e6"
                                         :GasFees        {:gasPrice       "1.01"
                                                          :baseFee        "1.008750001"
                                                          :eip1559Enabled true}
                                         :BridgeName     "Transfer"
                                         :AmountOut      "0x0"
                                         :To             {:isTest    false
                                                          :chainName "Arbitrum"
                                                          :chainId   42161}
                                         :Cost           "1.906539438247064285301"
                                         :GasAmount      23487}]
                                       :NativeChainTokenPrice 123
                                       :TokenPrice 123}
        suggested-routes-data         suggested-routes
        chosen-route                  (:best suggested-routes-data)
        token-symbol                  "ETH"
        token                         {:symbol   "ETH"
                                       :name     "Ether"
                                       :networks #{{:chain-id 421614}
                                                   {:chain-id 11155420}
                                                   {:chain-id 11155111}}}
        routes-available?             (pos? (count chosen-route))
        sender-network-values         [1 10]
        receiver-network-values       [1 10]
        receiver-networks             [1 10 421614]
        token-decimals                (if (collectible-with-balance 1) 0 (:decimals token))
        native-token?                 (and token (= token-symbol "ETH"))
        from-network-amounts-by-chain (send-utils/network-amounts-by-chain {:route chosen-route
                                                                            :token-decimals
                                                                            token-decimals
                                                                            :native-token?
                                                                            native-token?
                                                                            :receiver? false})
        to-network-amounts-by-chain   (send-utils/network-amounts-by-chain {:route chosen-route
                                                                            :token-decimals
                                                                            token-decimals
                                                                            :native-token?
                                                                            native-token?
                                                                            :receiver? true})
        to-network-values-for-ui      (send-utils/network-values-for-ui to-network-amounts-by-chain)
        tx-type                       :tx/collectible-erc-1155
        from-network-values-for-ui    (send-utils/network-values-for-ui from-network-amounts-by-chain)
        sender-possible-chain-ids     (mapv :chain-id sender-network-values)
        receiver-network-values       (if routes-available?
                                        (send-utils/network-amounts to-network-values-for-ui)
                                        (send-utils/reset-loading-network-amounts-to-zero
                                         receiver-network-values))
        sender-network-values         (if routes-available?
                                        (send-utils/network-amounts
                                         (if (= tx-type :tx/bridge)
                                           from-network-values-for-ui
                                           (send-utils/add-zero-values-to-network-values
                                            from-network-values-for-ui
                                            sender-possible-chain-ids)))
                                        (send-utils/reset-loading-network-amounts-to-zero
                                         sender-network-values))
        expected-db                   (make-send-structure
                                       {:other-props                     :value
                                        :suggested-routes                suggested-routes-data
                                        :route                           chosen-route
                                        :token                           token
                                        :suggested-routes-call-timestamp timestamp
                                        :collectible                     (collectible-with-balance 1)
                                        :token-display-name              token-symbol
                                        :receiver-networks               receiver-networks
                                        :tx-type                         tx-type
                                        :from-values-by-chain            from-network-values-for-ui
                                        :to-values-by-chain              to-network-values-for-ui
                                        :sender-network-values           sender-network-values
                                        :receiver-network-values         receiver-network-values
                                        :network-links                   (when routes-available?
                                                                           (send-utils/network-links
                                                                            chosen-route
                                                                            sender-network-values
                                                                            receiver-network-values))
                                        :loading-suggested-routes?       false})]
    (reset! rf-db/app-db
      (make-send-structure {:other-props                     :value
                            :suggested-routes-call-timestamp timestamp
                            :token                           token
                            :collectible                     (collectible-with-balance 1)
                            :token-display-name              token-symbol
                            :receiver-networks               receiver-networks
                            :receiver-network-values         [1 10]
                            :sender-network-values           [1 10]
                            :tx-type                         tx-type}))
    (is (match? expected-db (:db (dispatch [event-id suggested-routes timestamp]))))))

(h/deftest-event :wallet/select-from-account
  [event-id dispatch]
  (let [stack-id    :screen/stack
        start-flow? false
        address     "0x01"
        network     {:chain-id 1}]
    (testing "when tx-type is :tx/bridge and token-symbol is nil"
      (let [flow-id         :wallet-bridge-flow
            tx-type         :tx/bridge
            expected-result {:db (make-send-structure {:to-address address
                                                       :tx-type    tx-type})
                             :fx [[:dispatch [:wallet/switch-current-viewing-account address]]
                                  [:dispatch
                                   [:wallet/wizard-navigate-forward
                                    {:current-screen stack-id
                                     :start-flow?    start-flow?
                                     :flow-id        flow-id}]]]}]
        (reset! rf-db/app-db (make-send-structure {:tx-type tx-type}))
        (is (match? expected-result
                    (dispatch [event-id
                               {:address     address
                                :stack-id    stack-id
                                :start-flow? start-flow?}])))))
    (testing "when tx-type is :tx/bridge, network is selected and token-symbol is nil"
      (let [flow-id         :wallet-bridge-flow
            tx-type         :tx/bridge
            expected-result {:db (make-send-structure {:to-address address
                                                       :tx-type    tx-type})
                             :fx [[:dispatch [:wallet/switch-current-viewing-account address]]
                                  [:dispatch
                                   [:wallet/wizard-navigate-forward
                                    {:current-screen stack-id
                                     :start-flow?    start-flow?
                                     :flow-id        flow-id}]]]}]
        (reset! rf-db/app-db (make-send-structure {:tx-type tx-type}))
        (is (match? expected-result
                    (dispatch [event-id
                               {:address     address
                                :stack-id    stack-id
                                :network     network
                                :start-flow? start-flow?}])))))
    (testing "when tx-type is not :tx/bridge and token-symbol is nil"
      (let [flow-id         :wallet-send-flow
            tx-type         :tx/collectible-erc-721
            expected-result {:db (make-send-structure {:tx-type tx-type})
                             :fx [[:dispatch [:wallet/switch-current-viewing-account address]]
                                  [:dispatch
                                   [:wallet/wizard-navigate-forward
                                    {:current-screen stack-id
                                     :start-flow?    start-flow?
                                     :flow-id        flow-id}]]]}]
        (reset! rf-db/app-db (make-send-structure {:tx-type tx-type}))
        (is (match? expected-result
                    (dispatch [event-id
                               {:address     address
                                :stack-id    stack-id
                                :network     network
                                :start-flow? start-flow?}])))))
    (testing "when tx-type is :tx/bridge and token-symbol is not nil"
      (let [tx-type         :tx/bridge
            expected-result {:db (make-send-structure {:to-address address
                                                       :tx-type    tx-type})
                             :fx [[:dispatch [:dismiss-modal :screen/wallet.select-from]]
                                  [:dispatch [:wallet/switch-current-viewing-account address]]
                                  [:dispatch [:show-bottom-sheet {:content (m/pred fn?)}]]]}]
        (reset! rf-db/app-db (make-send-structure {:tx-type      tx-type
                                                   :token-symbol "ETH"}))
        (is (match? expected-result
                    (dispatch [event-id
                               {:address     address
                                :stack-id    stack-id
                                :start-flow? start-flow?}])))))
    (testing "when tx-type is not :tx/bridge and token-symbol is not nil"
      (let [flow-id         :wallet-send-flow
            tx-type         :tx/collectible-erc-721
            tokens          [{:symbol             "ETH"
                              :chain-id           1
                              :balances-per-chain {1     {:raw-balance (money/bignumber 100)}
                                                   10    {:raw-balance (money/bignumber 200)}
                                                   42161 {:raw-balance (money/bignumber 300)}}
                              :decimals           2}]
            network-details #{{:chain-id 1}
                              {:chain-id 10}
                              {:chain-id 42161}}
            expected-result {:db (-> (make-send-structure {:tx-type      tx-type
                                                           :token-symbol "ETH"
                                                           :token        (assoc (first tokens)
                                                                                :networks #{nil}
                                                                                :total-balance
                                                                                (money/bignumber
                                                                                 6))})
                                     (assoc-in [:wallet :accounts] {address {:tokens tokens}}))

                             :fx [[:dispatch [:wallet/switch-current-viewing-account address]]
                                  [:dispatch
                                   [:wallet/wizard-navigate-forward
                                    {:current-screen stack-id
                                     :start-flow?    start-flow?
                                     :flow-id        flow-id}]]]}]
        (reset! rf-db/app-db (-> (make-send-structure {:tx-type      tx-type
                                                       :token-symbol "ETH"})
                                 (assoc-in [:wallet :accounts] {address {:tokens tokens}})))
        (is (match? expected-result
                    (dispatch [event-id
                               {:address        address
                                :stack-id       stack-id
                                :network        network
                                :start-flow?    start-flow?
                                :netork-details network-details}])))))))
