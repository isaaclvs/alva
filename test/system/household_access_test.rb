require "application_system_test_case"

class HouseholdAccessTest < ApplicationSystemTestCase
  setup do
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha-de-teste", cost: 4).to_s
  end

  teardown { ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest }

  test "enter once, use the list, reload and sign out" do
    using_session(:other_household_device) do
      visit root_path
      fill_in "Senha da casa", with: "senha-de-teste"
      click_button "Entrar"
      show_list
      connect_turbo_cable_stream_sources
    end

    visit root_path
    assert_text "Entre na lista de casa"
    fill_in "Senha da casa", with: "errada"
    click_button "Entrar"
    assert_text "Senha incorreta"
    fill_in "Senha da casa", with: "senha-de-teste"
    click_button "Entrar"
    connect_turbo_cable_stream_sources
    add_item "Banana"
    assert_text "Banana adicionado"
    using_session(:other_household_device) { assert_text "Banana" }
    visit root_path
    assert_selector "#add_screen"
    open_menu
    click_button "Sair", exact: true
    assert_text "Entre na lista de casa"
    visit root_path
    assert_text "Entre na lista de casa"
  end
end
