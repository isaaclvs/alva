require "test_helper"

class NavigationTest < ActionDispatch::IntegrationTest
  test "opens on Adicionar with the field focused and the list rendered but hidden" do
    get root_path

    assert_select "#add_screen:not([hidden])" do
      assert_select "h1", "O que está faltando em casa?"
      assert_select "input#list_item_name[autofocus]"
      assert_select "#add_feedback[role=status][aria-live=polite]"
      assert_select "#list_items", count: 0
    end
    assert_select "#list_screen[hidden] #list_items"
    assert_select "nav[aria-label=Telas] button", count: 2
    assert_select "#nav_add[aria-current=page]", text: /Adicionar/
    assert_select "#nav_list:not([aria-current])" do
      assert_select "#list_counter", text: /\A1/
    end
  end

  test "the saved screen cookie opens the list without autofocus" do
    cookies[:screen] = "list"
    get root_path

    assert_select "#list_screen:not([hidden])"
    assert_select "#add_screen[hidden]"
    assert_select "#nav_list[aria-current=page]"
    assert_select "input#list_item_name"
    assert_select "input#list_item_name[autofocus]", count: 0
  end

  test "the old market mode cookie opens the list until a screen is picked" do
    cookies[:market_mode] = "true"
    get root_path
    assert_select "#list_screen:not([hidden])"

    cookies[:screen] = "add"
    get root_path
    assert_select "#add_screen:not([hidden])"
  end

  test "unknown screen values fall back to Adicionar" do
    cookies[:screen] = "<script>"
    get root_path
    assert_select "#add_screen:not([hidden])"
    assert_select "input#list_item_name[autofocus]"
  end

  test "the counter shows pending items and is refreshed with the list, without the navigation" do
    get root_path, as: :turbo_stream

    assert_select "turbo-stream[action=replace][target=list_counter] #list_counter", text: /\A1 pendente\z/
    assert_select "turbo-stream nav", count: 0
    assert_select "turbo-stream [data-screen-target]", count: 0
    assert_select "turbo-stream [data-controller~=theme]", count: 0
  end

  test "the list screen has its title, summary and the finish button only with purchased items" do
    get root_path
    assert_select "#list_screen h1", "Lista de compras"
    assert_select "#list_summary", text: /1 pendente · 1 comprado/
    assert_select "#list_items form[action='#{shopping_trips_path}'] button", text: "Compra feita"

    list_items(:detergent).update!(status: :pending, purchased_at: nil)
    get root_path
    assert_select "#list_items form[action='#{shopping_trips_path}']", count: 0
  end
end
