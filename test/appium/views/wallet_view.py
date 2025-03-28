import time

import pytest
from selenium.common import NoSuchElementException

from tests import common_password
from views.base_element import Button, EditBox, Text, BaseElement
from views.base_view import BaseView
from views.home_view import HomeView
from views.sign_in_view import SignInView
from typing import Literal
import datetime


class AssetElement(Button):

    def __init__(self, driver, asset_name):
        self.asset_name = asset_name
        self.locator = "//android.view.ViewGroup[@content-desc='container']/android.widget.TextView[@text='%s']" % \
                       self.asset_name
        super().__init__(driver=driver, xpath=self.locator)

    def get_amount(self):
        element = Text(self.driver, xpath=self.locator + "/../android.widget.TextView[3]")
        element.scroll_to_element()
        try:
            amount = element.text.split()[0]
            if '<' in amount:
                return 0
            else:
                return float(amount)
        except ValueError:
            pytest.fail("Cannot get %s amount" % self.asset_name)


class CollectibleItemElement(Button):

    def __init__(self, driver, collectible_name):
        self.collectible_name = collectible_name
        self.locator = "//*[@content-desc='collectible-list-item']//*[contains(@text,'%s')]/../.." % collectible_name
        super().__init__(driver=driver, xpath=self.locator)
        self.image_element = BaseElement(self.driver, xpath=self.locator + "//android.widget.ImageView")

    @property
    def quantity(self):
        counter_element = BaseElement(
            self.driver, xpath=self.locator + "//*[@content-desc='collectible-counter']/android.widget.TextView")
        try:
            return int(counter_element.text.strip('x'))
        except NoSuchElementException:
            return 1


class ActivityElement(BaseElement):
    def __init__(self, driver, index: int):
        self.locator = "(//*[@content-desc='wallet-activity'])[%s]" % index
        super().__init__(driver=driver, xpath=self.locator)

    @property
    def header(self):
        return Text(self.driver, prefix=self.locator, xpath="//*[@content-desc='transaction-header']").text

    @property
    def timestamp(self):
        return Text(self.driver, prefix=self.locator, xpath="//*[@content-desc='transaction-timestamp']").text

    @property
    def amount(self):
        return Text(self.driver, prefix=self.locator,
                    xpath="//*[@content-desc='context-tag'][1]/android.widget.TextView").text

    @property
    def from_text(self):
        return Text(self.driver, prefix=self.locator,
                    xpath="//*[@content-desc='context-tag'][2]/android.widget.TextView").text

    @property
    def to_text(self):
        return Text(self.driver, prefix=self.locator,
                    xpath="//*[@content-desc='context-tag'][3]/android.widget.TextView").text

    @property
    def on_network(self):
        return Text(self.driver, prefix=self.locator,
                    xpath="//*[@content-desc='context-tag'][4]/android.widget.TextView").text


class ConfirmationViewInfoContainer(BaseElement):

    def __init__(self, driver, label_name: str):
        self.locator = "//*[@text='%s']/following-sibling::android.view.ViewGroup[1]" % label_name
        super().__init__(driver, xpath=self.locator)

    @property
    def amount_text(self):
        return Text(self.driver, xpath=self.locator + "/*[@content-desc='networks']/android.widget.TextView").text


class WalletView(BaseView):
    def __init__(self, driver):
        super().__init__(driver)
        # Wallet view
        self.total_balance_text = Text(
            self.driver, xpath="//*[@content-desc='network-dropdown']/preceding-sibling::android.widget.TextView")
        self.network_drop_down = Button(self.driver, accessibility_id='network-dropdown')
        self.collectibles_tab = Button(self.driver, accessibility_id='collectibles-tab')
        self.add_account_button = Button(self.driver, accessibility_id='add-account')

        # Account adding
        # ToDo: add unique accessibility ids for the next 2 elements:
        self.create_account_button = HomeView(self.driver).start_a_new_chat_bottom_sheet_button
        self.add_account_to_watch = HomeView(self.driver).add_a_contact_chat_bottom_sheet_button
        self.address_to_watch_input = EditBox(self.driver, accessibility_id='add-address-to-watch')
        self.account_has_activity_label = Text(self.driver, accessibility_id='account-has-activity')
        self.add_account_continue_button = Button(self.driver, accessibility_id='Continue')
        self.add_watched_address_button = Button(self.driver, accessibility_id='confirm-button-label')
        self.add_account_derivation_path_text = Text(
            self.driver, xpath="//*[contains(@content-desc,'icon, Derivation path')]/android.widget.TextView[2]")

        # Account view
        self.close_account_button = Button(self.driver, accessibility_id='top-bar')
        self.account_name_text = Text(
            self.driver, xpath="//*[@content-desc='account-avatar']/../following-sibling::android.widget.TextView[1]")
        self.account_emoji_button = Button(self.driver, accessibility_id='account-emoji')
        self.send_button = Button(self.driver, accessibility_id='send')
        self.swap_button = Button(self.driver, accessibility_id='swap')
        self.bridge_button = Button(self.driver, accessibility_id='bridge')
        self.send_from_drawer_button = Button(
            self.driver, xpath="//*[@content-desc='send']/*[@content-desc='left-icon-for-action']")
        self.copy_address_button = Button(self.driver, accessibility_id='copy-address')
        self.share_address_button = Button(self.driver, accessibility_id='share-account')
        self.remove_account_button = Button(self.driver, accessibility_id='remove-account')
        self.derivation_path_note_checkbox = Button(self.driver, accessibility_id='checkbox-off')
        self.account_about_derivation_path_text = Text(
            self.driver, xpath="//*[@content-desc='derivation-path-icon']/following-sibling::*[2]")

        self.activity_tab = Button(self.driver, accessibility_id='activity-tab')
        self.about_tab = Button(self.driver, accessibility_id='about')

        # Sending transaction
        self.address_text_input = EditBox(self.driver, accessibility_id='address-text-input')
        self.collectibles_tab_on_select_token_view = Button(self.driver, accessibility_id='Collectibles')
        self.amount_input = EditBox(self.driver, xpath="//android.widget.EditText")
        self.from_network_text = Text(
            self.driver, xpath="(//*[@content-desc='loading']/following-sibling::android.widget.TextView)[1]")
        self.confirm_button = Button(self.driver, accessibility_id='button-one')
        self.done_button = Button(self.driver, accessibility_id='done')
        self.amount_input_increase_button = Button(self.driver, accessibility_id='amount-input-inc-button')

        # Review Send and Review Bridge screens
        self.from_data_container = ConfirmationViewInfoContainer(self.driver, label_name='From')
        self.to_data_container = ConfirmationViewInfoContainer(self.driver, label_name='To')
        self.on_data_container = ConfirmationViewInfoContainer(self.driver, label_name='On')

        # Swap flow
        self.approve_swap_button = Button(self.driver, accessibility_id='Approve')
        self.spending_cap_approval_info_container = BaseElement(
            self.driver,
            xpath="//*[@content-desc='spending-cap-label']/following-sibling::*[@content-desc='approval-info'][1]")
        self.account_approval_info_container = BaseElement(
            self.driver,
            xpath="//*[@content-desc='account-label']/following-sibling::*[@content-desc='approval-info'][1]")
        self.token_approval_info_container = BaseElement(
            self.driver,
            xpath="//*[@content-desc='token-label']/following-sibling::*[@content-desc='approval-info'][1]")
        self.spender_contract_approval_info_container = BaseElement(
            self.driver,
            xpath="//*[@content-desc='spender-contract-label']/following-sibling::*[@content-desc='approval-info'][1]")

        # Edit key pair
        self.edit_key_pair_button = Button(self.driver, accessibility_id="Edit")
        self.key_pairs_plus_button = Button(self.driver, accessibility_id="standard-title-action")
        self.default_key_pair_container = BaseElement(self.driver,
                                                      xpath="//*[@content-desc='user-avatar, title, details']")
        self.added_key_pair_container = BaseElement(self.driver,
                                                    xpath="//*[@content-desc='icon, title, details']")
        self.generate_new_keypair_button = Button(self.driver, accessibility_id="generate-new-keypair")
        self.import_using_recovery_phrase_button = Button(self.driver, accessibility_id="import-using-phrase")
        self.key_pair_continue_button = Button(self.driver, accessibility_id="Continue")
        self.key_pair_name_input = EditBox(
            self.driver, xpath="//*[@text='Key pair name']/..//following-sibling::*/*[@content-desc='input']")
        self.passphrase_text_element = Text(
            self.driver, xpath="//*[@resource-id='counter-component']/following-sibling::android.widget.TextView")
        self.passphrase_word_number_container = Text(
            self.driver, xpath="//*[@content-desc='number-container']/android.widget.TextView")

        # Collectible view
        self.expanded_collectible_image = BaseElement(
            self.driver, xpath="//*[@content-desc='expanded-collectible']//android.widget.ImageView")
        self.send_from_collectible_info_button = Button(self.driver, accessibility_id="icon, Send")

    def set_network_in_wallet(self, network_name: str):
        self.network_drop_down.click()
        Button(self.driver, accessibility_id="%s, label-component" % network_name).click()
        self.network_drop_down.click()

    def get_account_element(self, account_name: str = 'Account 1'):
        return Button(self.driver, xpath="//android.view.ViewGroup[contains(@content-desc,'%s')]" % account_name)

    def get_asset(self, asset_name: str):
        element = AssetElement(driver=self.driver, asset_name=asset_name)
        element.scroll_to_element(down_start_y=0.89, down_end_y=0.8)
        return element

    def get_collectible_element(self, collectible_name: str):
        return CollectibleItemElement(driver=self.driver, collectible_name=collectible_name)

    def select_asset(self, asset_name: str):
        Button(driver=self.driver,
               xpath="//*[@content-desc='token-network']/android.widget.TextView[@text='%s']" % asset_name).click()

    def select_network(self, network_name: str):
        Button(driver=self.driver,
               xpath="//*[@content-desc='network-list']/*[@text='%s']" % network_name).click()

    def slide_and_confirm_with_password(self):
        self.slide_button_track.slide()
        self.password_input.send_keys(common_password)
        self.login_button.click()

    def confirm_transaction(self):
        self.confirm_button.click()
        for _ in range(3):
            if self.slide_button_track.is_element_displayed():
                break
            time.sleep(5)
            self.confirm_button.click()
        self.slide_and_confirm_with_password()

    def set_amount(self, amount: str):
        for i in amount:
            Button(self.driver, accessibility_id='keyboard-key-%s' % i).click()

    def send_asset(self, address: str, asset_name: str, amount, network_name: str, account='Account 1'):
        self.send_button.click()
        self.address_text_input.send_keys(address)
        self.continue_button.click()
        self.select_asset(asset_name)
        self.select_network(network_name)
        self.set_amount(str(amount))
        self.confirm_transaction()

    def send_asset_from_drawer(self, address: str, asset_name: str, amount, network_name: str):
        asset_element = self.get_asset(asset_name)
        asset_element.long_press_without_release()
        self.send_from_drawer_button.double_click()
        self.select_network(network_name)
        self.address_text_input.send_keys(address)
        self.continue_button.click()
        self.set_amount(str(amount))
        self.confirm_transaction()

    def add_regular_account(self, account_name: str):
        self.add_account_button.click()
        self.create_account_button.click()
        derivation_path = self.add_account_derivation_path_text.text
        SignInView(self.driver).profile_title_input.send_keys(account_name)
        self.slide_and_confirm_with_password()
        return derivation_path.replace(' ', '')

    def add_watch_only_account(self, address: str, account_name: str):
        self.add_account_button.click()
        self.add_account_to_watch.click()
        self.address_to_watch_input.send_keys(address)
        self.add_account_continue_button.click()
        SignInView(self.driver).profile_title_input.send_keys(account_name)
        self.add_watched_address_button.click()

    def remove_account(self, account_name: str, watch_only: bool = False):
        self.get_account_element(account_name=account_name).click()
        self.account_emoji_button.click()
        self.remove_account_button.click()
        if not watch_only:
            self.derivation_path_note_checkbox.click()
        self.confirm_button.click()

    def get_activity_element(self, index=1):
        return ActivityElement(self.driver, index=index)

    def generate_new_key_pair(self, account_name: str, key_pair_name: str):
        self.key_pairs_plus_button.click()
        self.generate_new_keypair_button.click()
        for checkbox in self.checkbox_button.find_elements():
            checkbox.click()
        self.element_by_translation_id("reveal-phrase").click()
        passphrase = [i.text for i in self.passphrase_text_element.find_elements()]
        self.element_by_translation_id("i-have-written").click()
        for _ in range(4):
            element = self.passphrase_word_number_container.find_element()
            number = int(element.text)
            Button(self.driver, accessibility_id=passphrase[number - 1]).click()
            self.wait_for_staleness_of_element(element)
        self.checkbox_button.click()
        self.element_by_translation_id("done").click()
        self.key_pair_name_input.send_keys(key_pair_name)
        self.key_pair_continue_button.click()
        derivation_path = self.add_account_derivation_path_text.text
        SignInView(self.driver).profile_title_input.send_keys(account_name)
        self.slide_and_confirm_with_password()
        return derivation_path.replace(' ', ''), ' '.join(passphrase)

    def import_key_pair_using_recovery_phrase(self, account_name: str, passphrase: str, key_pair_name: str):
        self.add_account_button.click()
        self.create_account_button.click()
        signin_view = SignInView(self.driver)
        signin_view.profile_title_input.send_keys(account_name)
        self.edit_key_pair_button.click()
        self.key_pairs_plus_button.click()
        self.import_using_recovery_phrase_button.click()
        signin_view.passphrase_edit_box.send_keys(passphrase)
        self.key_pair_continue_button.click()
        self.key_pair_name_input.send_keys(key_pair_name)
        self.key_pair_continue_button.click()
        derivation_path = self.add_account_derivation_path_text.text
        self.slide_and_confirm_with_password()
        return derivation_path.replace(' ', '')

    def get_account_address(self):
        self.account_emoji_button.click_until_presence_of_element(self.copy_address_button)
        self.share_address_button.click()
        wallet_address = self.sharing_text_native.text
        self.click_system_back_button()
        return wallet_address

    def get_data_item_element_text(self, data_item_name: str):
        element = Text(self.driver, xpath="//*[@text='%s']/following-sibling::android.widget.TextView" % data_item_name)
        element.wait_for_element(15)
        return element.text

    def wait_for_swap_input_to_be_shown(self):
        locator = "//*[@content-desc='swap-input'][2]//*[@content-desc='token-avatar']" \
                  "/following-sibling::*//*[starts-with(@text,'0.000')]"
        BaseElement(self.driver, xpath=locator).wait_for_visibility_of_element()

    def get_route_element(self, route_name: str):
        class RouteElement(BaseElement):
            def __init__(self, driver, route_name):
                self.locator = "//*[@text='%s']/following-sibling::*[@content-desc='container'][%s]" % (
                    route_name.capitalize(), 1 if route_name == 'from' else 2)
                super().__init__(driver, xpath=self.locator)

            @property
            def amount_text(self):
                return Text(self.driver, xpath="(%s//android.widget.TextView)[1]" % self.locator).text

            @property
            def network_text(self):
                return Text(self.driver, xpath="(%s//android.widget.TextView)[2]" % self.locator).text

        return RouteElement(self.driver, route_name)

    @staticmethod
    def round_amount_float(amount, decimals=4):
        return round(float(amount), decimals)


    def wait_for_wallet_balance_to_update(self, expected_amount, asset='Ether', decimals=4):
        self.just_fyi(f"Checking {asset} balance, expected value: {expected_amount}")

        start_time = time.time()

        while time.time() - start_time < 120:
            self.pull_to_refresh()
            new_balance = self.round_amount_float(self.get_asset(asset_name=asset).get_amount(), decimals)

            # Exit early if the balance is updated
            if new_balance == expected_amount:
                return

            time.sleep(10)  # Wait before retrying

        # If the balance is not updated within the timeout, log an error and raise an exception
        error_message = f"{asset} balance is {new_balance} but expected {expected_amount}"
        raise TimeoutError(error_message)

    def check_last_transaction_in_activity(self, device_time, amount,
                                            asset='ETH',
                                            to_account='',
                                            tx_type: Literal['Send', 'Receive'] = 'Send',
                                            from_account_name='Account 1',
                                            network='Status Network'):
        errors = list()

        self.just_fyi("Checking the last transaction in the activity tab")
        current_time = datetime.datetime.strptime(device_time, "%Y-%m-%dT%H:%M:%S%z")
        expected_time = "Today %s" % current_time.strftime('%-I:%M %p')
        possible_times = [expected_time,
                          "Today %s" % (current_time + datetime.timedelta(minutes=1)).strftime('%-I:%M %p')]
        receiver_variants = [to_account.replace(to_account[5:-3], '...').lower(), 'receiver eth2']
        activity_element = self.get_activity_element()
        try:
            checks = {
                "header": (activity_element.header, tx_type),
                "timestamp": (activity_element.timestamp, possible_times),
                "amount": (activity_element.amount, f"{amount} {asset}"),
                "from_text": (activity_element.from_text, from_account_name),
                "to_text": (activity_element.to_text, receiver_variants),
                "on_network": (activity_element.on_network, network),
            }

            for name, (left, right) in checks.items():
                if isinstance(right, list):
                    if left not in right:
                        errors.append(
                            f"Verification of last transaction failed."
                            f"The failed check: {name} (Expected: {right} to contain: {left})")
                else:
                    if left != right:
                        errors.append(
                            f"Verification of last transaction failed."
                            f"The failed check: {name} (Expected: {right}, Found: {left})")



        except NoSuchElementException:
            errors.append("Can't find the last transaction")
        finally:
            self.close_account_button.click_until_presence_of_element(self.show_qr_code_button)
        return errors

    def get_balance(self, asset='Ether'):
        self.just_fyi("Getting %s amount of the wallet of the sender before transaction" % asset)
        return self.get_asset(asset_name=asset).get_amount()


