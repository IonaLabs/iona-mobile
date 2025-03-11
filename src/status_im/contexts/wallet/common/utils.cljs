(ns status-im.contexts.wallet.common.utils
  (:require [clojure.string :as string]
            [quo.foundations.resources :as resources]
            [status-im.common.qr-codes.view :as qr-codes]
            [status-im.constants :as constants]
            [status-im.contexts.wallet.common.utils.networks :as network-utils]
            [utils.address]
            [utils.money :as money]
            [utils.number :as number]
            [utils.string]))

(def missing-price-decimals 6) ; if we don't have the monetary value of the token, we default to 6 decimals
(defn get-first-name
  [full-name]
  (first (string/split full-name #" ")))

(defn cut-fiat-balance
  [balance decimals]
  (let [valid-balance? (and balance
                            (or (number? balance) (.-toFixed balance)))]
    (as-> balance $
      (if valid-balance? $ 0)
      (.toFixed $ decimals))))

(defn cut-fiat-balance-to-two-decimals
  [balance]
  (cut-fiat-balance balance 2))

(defn prepend-curency-symbol-to-fiat-balance
  [fiat-balance currency-symbol]
  (let [formatted-symbol (if (> (count currency-symbol) 1)
                           (str currency-symbol " ")
                           currency-symbol)]
    (str formatted-symbol fiat-balance)))

(defn prettify-balance
  [currency-symbol fiat-balance]
  (-> fiat-balance
      cut-fiat-balance-to-two-decimals
      (prepend-curency-symbol-to-fiat-balance currency-symbol)))

(defn get-derivation-path
  [number-of-accounts]
  (str constants/path-wallet-root "/" number-of-accounts))

(defn format-derivation-path
  [path]
  (string/replace path "/" " / "))

(defn get-formatted-derivation-path
  [number-of-accounts]
  (let [path (get-derivation-path number-of-accounts)]
    (format-derivation-path path)))

(defn total-raw-balance-in-all-chains
  [balances-per-chain]
  (->> balances-per-chain
       (map (comp :raw-balance val))
       (reduce money/add)))

(defn extract-exponent
  [s]
  (if-let [index (string/index-of s "e")]
    (subs s (+ index 2))
    nil))

(defn calc-max-crypto-decimals
  [one-cent-value]
  (let [str-representation   (str one-cent-value)
        decimal-part         (second (clojure.string/split str-representation #"\."))
        exponent             (extract-exponent str-representation)
        zeroes-count         (count (take-while #(= \0 %) decimal-part))
        max-decimals         (or exponent zeroes-count)
        first-non-zero-digit (first (filter #(not (= \0 %)) decimal-part))]
    (if (= \1 first-non-zero-digit)
      (inc max-decimals)
      max-decimals)))

(defn token-price-by-symbol
  "Get token price for specific currency from prices-per-token structure"
  [prices-per-token token-symbol currency]
  (get-in prices-per-token [(keyword token-symbol) currency]))

(defn token-usd-price
  [token prices-per-token]
  (token-price-by-symbol prices-per-token (:symbol token) :usd))

(defn one-cent-value
  [token-price-in-usd]
  (if (pos? token-price-in-usd)
    (/ 0.01 token-price-in-usd)
    0))

(defn analyze-token-amount-for-price
  "For full details: https://github.com/status-im/status-mobile/issues/18225"
  [token-price-in-usd token-units]
  (if (or (nil? token-units)
          (not (money/bignumber? token-units))
          (money/equal-to token-units 0))
    {:zero-value? true}
    (let [cent-value (one-cent-value token-price-in-usd)]
      {:usd-cent-value              cent-value
       :standardized-decimals-count (if (nil? token-price-in-usd)
                                      missing-price-decimals
                                      (calc-max-crypto-decimals cent-value))})))

(defn cut-crypto-decimals-to-fit-usd-cents
  [token-units token-price-in-usd]
  (let [{:keys [zero-value? usd-cent-value standardized-decimals-count]}
        (analyze-token-amount-for-price token-price-in-usd token-units)]
    (cond
      zero-value?                    "0"
      (< token-units usd-cent-value) "0"
      :else                          (number/remove-trailing-zeroes
                                      (.toFixed token-units standardized-decimals-count)))))

(defn add-token-symbol-to-crypto-balance
  [crypto-balance token-symbol]
  (str crypto-balance " " (string/upper-case token-symbol)))

(defn prettify-crypto-balance
  [token-symbol crypto-balance conversion-rate]
  (-> crypto-balance
      (cut-crypto-decimals-to-fit-usd-cents conversion-rate)
      (add-token-symbol-to-crypto-balance token-symbol)))

(defn get-standard-crypto-format
  "For full details: https://github.com/status-im/status-mobile/issues/18225"
  [token token-units prices-per-token]
  (let [token-price-in-usd (token-usd-price token prices-per-token)
        {:keys [zero-value? usd-cent-value standardized-decimals-count]}
        (analyze-token-amount-for-price token-price-in-usd token-units)]
    (cond
      zero-value?
      "0"

      (< token-units usd-cent-value)
      (str "<" (number/remove-trailing-zeroes (.toFixed usd-cent-value standardized-decimals-count)))

      :else
      (number/remove-trailing-zeroes (.toFixed token-units standardized-decimals-count)))))

(defn get-market-value
  [currency {token-symbol :symbol} prices-per-token]
  (or (token-price-by-symbol prices-per-token token-symbol currency)
      (token-price-by-symbol prices-per-token token-symbol constants/profile-default-currency)
      ;; NOTE: adding fallback value (zero) in case prices are
      ;; unavailable and to prevent crash on calculating fiat value
      0))

(defn- filter-chains
  [balances-per-chain chain-ids]
  (if chain-ids
    (select-keys balances-per-chain chain-ids)
    balances-per-chain))

(defn calculate-total-token-balance
  ([token]
   (calculate-total-token-balance token nil))
  ([{:keys [balances-per-chain decimals]} chain-ids]
   (-> balances-per-chain
       (filter-chains chain-ids)
       (total-raw-balance-in-all-chains)
       (money/token->unit decimals))))

(defn get-account-by-address
  [accounts address]
  (some #(when (= (:address %) address) %) accounts))

(defn get-default-account
  [accounts]
  (some #(when (:default-account? %) %) accounts))

(defn calculate-token-fiat-value
  "Returns the token fiat value for provided raw balance"
  [{:keys [currency balance token prices-per-token]}]
  (let [price (get-market-value currency token prices-per-token)]
    (money/crypto->fiat balance price)))

(defn calculate-token-fiat-change
  "Returns the fiat value change for a token based on the percentage change"
  [fiat-value change-pct-24h]
  (let [fiat-change (-> (money/bignumber change-pct-24h)
                        (money/div (money/bignumber 100))
                        (money/mul fiat-value)
                        (money/absolute-value))]
    fiat-change))

(defn sanitized-token-amount-to-display
  "Formats a token amount to a specified number of decimals.
  Returns a threshold value if the formatted amount is less than the minimum value represented by the decimals."
  [amount display-decimals]
  (let [number                (or (money/bignumber amount)
                                  (money/bignumber 0))
        amount-fixed-decimals (number/to-fixed number display-decimals)]
    (if (and (= amount-fixed-decimals "0")
             (money/above-zero? amount))
      (number/small-number-threshold display-decimals)
      (str amount-fixed-decimals))))

(defn token-balance-display-for-network
  "Formats a token balance for a specific chain and rounds it to a specified number of decimals.
  If the balance is less than the smallest representable value based on rounding decimals,
  a threshold value is displayed instead."
  [token chain-id rounding-decimals]
  (let [token-decimals   (:decimals token)
        display-decimals (min token-decimals rounding-decimals)]
    (-> (get-in token [:balances-per-chain chain-id :raw-balance] 0)
        (number/convert-to-whole-number token-decimals)
        money/bignumber
        (sanitized-token-amount-to-display display-decimals))))

(defn calculate-balance-from-tokens
  [{:keys [currency tokens chain-ids prices-per-token]}]
  (->> tokens
       (map #(calculate-token-fiat-value
              {:currency         currency
               :balance          (calculate-total-token-balance % chain-ids)
               :token            %
               :prices-per-token prices-per-token}))
       (reduce money/add)))

(defn- add-balances-per-chain
  [b1 b2]
  {:raw-balance (money/add (:raw-balance b1) (:raw-balance b2))
   :chain-id    (:chain-id b2)})

(defn- merge-token
  [existing-token token]
  (assoc token
         :balances-per-chain
         (merge-with add-balances-per-chain
                     (:balances-per-chain existing-token)
                     (:balances-per-chain token))))

(defn aggregate-tokens-for-all-accounts
  "Receives accounts (seq) and returns aggregated tokens in all accounts
   NOTE: We use double reduce for faster performance (faster than mapcat and flatten)"
  [accounts]
  (->> accounts
       (map :tokens)
       (reduce
        (fn [result-map tokens-per-account]
          (reduce
           (fn [acc token]
             (update acc (:symbol token) merge-token token))
           result-map
           tokens-per-account))
        {})
       vals))

(defn get-wallet-qr
  [{:keys [wallet-type selected-networks address]}]
  (if (= wallet-type :multichain)
    (as-> selected-networks $
      (map qr-codes/get-network-short-name-url $)
      (apply str $)
      (str $ address))
    address))

(defn fiat-formatted-for-ui
  [currency-symbol fiat-value]
  (cond
    (money/equal-to fiat-value 0)     (str currency-symbol "0.00")
    (money/less-than fiat-value 0.01) (str "<" currency-symbol "0.01")
    :else                             (prettify-balance currency-symbol fiat-value)))

(defn prettify-percentage-change
  "Returns unsigned precentage"
  [percentage]
  (-> (if (number? percentage) percentage 0)
      money/bignumber
      money/absolute-value
      (money/to-fixed 2)))

(defn formatted-token-fiat-value
  "Converts a token balance into its equivalent fiat value, formatted with a currency symbol.
  If the fiat value is below $0.01, it returns a <$0.01 string"
  [{:keys [currency currency-symbol balance token prices-per-token]}]
  (let [price             (or (get-market-value currency token prices-per-token) 0)
        price-zero?       (zero? price)
        balance           (or balance 0)
        balance-positive? (pos? balance)
        fiat-value        (money/crypto->fiat balance price)
        fiat-value-zero?  (money/equal-to fiat-value (money/bignumber 0))]
    (if (and fiat-value-zero? (not price-zero?) balance-positive?)
      (number/small-number-threshold 2 currency-symbol)
      (fiat-formatted-for-ui currency-symbol fiat-value))))

(defn calculate-token-value
  "This function returns token values in the props of token-value (quo) component"
  [{:keys [token color currency currency-symbol prices-per-token market-values-per-token]}]
  (let [balance                  (calculate-total-token-balance token)
        fiat-unformatted-value   (calculate-token-fiat-value
                                  {:currency         currency
                                   :balance          balance
                                   :token            token
                                   :prices-per-token prices-per-token})
        market-values            (get market-values-per-token (keyword (:symbol token)))
        {:keys [change-pct-24h]} market-values
        percentage-change        (prettify-percentage-change change-pct-24h)
        crypto-value             (get-standard-crypto-format token balance prices-per-token)
        fiat-value               (fiat-formatted-for-ui currency-symbol
                                                        fiat-unformatted-value)
        fiat-change              (calculate-token-fiat-change fiat-unformatted-value change-pct-24h)
        formatted-fiat-change    (prettify-balance currency-symbol fiat-change)]
    {:token               (:symbol token)
     :token-name          (:name token)
     :state               :default
     :metrics?            (money/above-zero? balance)
     :status              (cond
                            (pos? change-pct-24h) :positive
                            (neg? change-pct-24h) :negative
                            :else                 :empty)
     :customization-color color
     :values              {:crypto-value           crypto-value
                           :fiat-value             fiat-value
                           :fiat-unformatted-value fiat-unformatted-value
                           :fiat-change            formatted-fiat-change
                           :percentage-change      percentage-change}}))

(defn get-multichain-address
  [networks address]
  (str (->> networks
            (map #(str (:short-name %) ":"))
            (clojure.string/join ""))
       address))

(defn split-prefix-and-address
  [input-string]
  (let [split-result (string/split input-string #"0x")]
    [(first split-result) (str "0x" (second split-result))]))

(defn make-network-item
  "This function generates props for quo/category component item"
  [{:keys [network-name color on-change networks label-props type blur?]}]
  (cond-> {:title                 (string/capitalize (name network-name))
           :image                 :icon-avatar
           :image-props           {:icon (resources/get-network network-name)
                                   :size :size-20}
           ;; Remove the following line for v2.35
           :show-new-feature-tag? (= network-name constants/base-network-name)
           :action                :selector
           :action-props          {:type                (or type :checkbox)
                                   :blur?               blur?
                                   :customization-color color
                                   :checked?            (contains? networks network-name)
                                   :on-change           on-change}}

    label-props
    (assoc :label       :text
           :label-props label-props)))

(defn filter-tokens-in-chains
  [tokens chain-ids]
  (map #(update % :balances-per-chain select-keys chain-ids) tokens))

(defn calculate-balances-per-chain
  [{:keys [tokens currency currency-symbol prices-per-token]}]
  (->
    (reduce (fn [acc {:keys [balances-per-chain decimals] :as token}]
              (let [currency-value         (get-market-value currency token prices-per-token)
                    fiat-balance-per-chain (update-vals balances-per-chain
                                                        #(-> (money/token->unit (:raw-balance %)
                                                                                decimals)
                                                             (money/crypto->fiat currency-value)))]
                (merge-with money/add acc fiat-balance-per-chain)))
            {}
            tokens)
    (update-vals #(prettify-balance currency-symbol %))))

(defn format-token-id
  [token collectible]
  (if token
    (:symbol token)
    (str (get-in collectible [:id :contract-id :address])
         ":"
         (get-in collectible [:id :token-id]))))

(defn get-token-from-account
  [db token-symbol address]
  (let [address-tokens (-> db :wallet :accounts (get address) :tokens)]
    (some #(when (= token-symbol (:symbol %))
             %)
          address-tokens)))

(defn get-shortened-address
  "Takes first and last 4 digits from address including leading 0x
  and adds unicode ellipsis in between"
  [address]
  (when address
    (let [counter (count address)]
      (str (subs address 0 6) "\u2026" (subs address (- counter 3) counter)))))

(defn get-account-name-error
  [s existing-account-names]
  (cond
    (utils.string/contains-emoji? s)             :emoji
    (existing-account-names s)                   :existing-name
    (utils.string/contains-special-character? s) :special-character))

(defn calculate-and-sort-tokens
  [{:keys [tokens color currency currency-symbol prices-per-token market-values-per-token]}]
  (let [calculate-token   (fn [token]
                            (calculate-token-value {:token                   token
                                                    :color                   color
                                                    :currency                currency
                                                    :currency-symbol         currency-symbol
                                                    :prices-per-token        prices-per-token
                                                    :market-values-per-token market-values-per-token}))
        calculated-tokens (map calculate-token tokens)]
    (sort-by (fn [token]
               (let [fiat-value (get-in token [:values :fiat-unformatted-value])
                     priority   (get constants/token-sort-priority (:token token) ##Inf)]
                 [(- fiat-value) priority]))
             calculated-tokens)))

(defn sort-tokens
  [tokens]
  (sort-by (comp - :balance) tokens))

(defn sort-tokens-by-fiat-value
  [tokens]
  (sort-by (comp - :fiat-value) tokens))

(defn sort-tokens-by-name
  [tokens]
  (sort-by :symbol tokens))

(defn token-with-balance
  ([token networks]
   (token-with-balance token networks nil))
  ([token networks chain-ids]
   (assoc token
          :networks           (network-utils/network-list-with-positive-balance token networks)
          :supported-networks (network-utils/network-list token networks)
          :available-balance  (calculate-total-token-balance token)
          :total-balance      (calculate-total-token-balance token chain-ids))))

(defn tokens-with-balance
  [tokens networks chain-ids]
  (map (fn [token]
         (token-with-balance token networks chain-ids))
       tokens))

(defn estimated-time-format
  "Formats the estimated time for a transaction"
  [estimated-time]
  (condp = estimated-time
    (:unknown constants/wallet-transaction-estimation)                 ">5"
    (:less-than-one-minute constants/wallet-transaction-estimation)    "<1"
    (:less-than-three-minutes constants/wallet-transaction-estimation) "1-3"
    (:less-than-five-minutes constants/wallet-transaction-estimation)  "3-5"
    ">5"))

(defn transactions->hash-to-transaction-map
  [transactions]
  (reduce
   (fn [acc {to-chain :toChain tx-hash :hash}]
     (assoc acc
            tx-hash
            {:status   :pending
             :id       tx-hash
             :chain-id to-chain}))
   {}
   transactions))

(defn get-accounts-with-token-balance
  [accounts token]
  (let [operable-account                       (filter :operable? (vals accounts))
        positive-balance-in-any-chain?         (fn [{:keys [balances-per-chain]}]
                                                 (->> balances-per-chain
                                                      (map (comp :raw-balance val))
                                                      (some pos?)))
        addresses-tokens-with-positive-balance (as-> operable-account $
                                                 (group-by :address $)
                                                 (update-vals $
                                                              #(filter positive-balance-in-any-chain?
                                                                       (:tokens (first %)))))]
    (if-let [asset-symbol (:symbol token)]
      (let [addresses-with-asset (as-> addresses-tokens-with-positive-balance $
                                   (update-vals $ #(set (map :symbol %)))
                                   (keep (fn [[address token-symbols]]
                                           (when (token-symbols asset-symbol) address))
                                         $)
                                   (set $))]
        (filter #(addresses-with-asset (:address %)) operable-account))
      (filter (fn [{:keys [tokens]}]
                (some positive-balance-in-any-chain? tokens))
              operable-account))))

(defn on-paste-address-or-ens
  "Check if the clipboard has any valid address and extract the address without any chain info.
  If it does not contain an valid address or it is ENS, return the clipboard text as it is"
  [clipboard-text]
  (if (utils.address/supported-address? clipboard-text)
    (utils.address/extract-address-without-chains-info clipboard-text)
    clipboard-text))

(defn token-exchange-rate
  "Returns the exchange rate based on the token prices.
   i.e. how many 'divisor' tokens is one 'divident' token."
  ([divident-price divisor-price]
   (token-exchange-rate divident-price divisor-price constants/min-token-decimals-to-display))
  ([divident-price divisor-price divisor-token-decimals]
   (-> (money/bignumber divident-price)
       (money/div (money/bignumber divisor-price))
       (number/to-fixed (min divisor-token-decimals constants/min-token-decimals-to-display)))))
