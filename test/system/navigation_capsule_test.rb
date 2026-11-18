require "application_system_test_case"

# The phone navigation is the floating capsule the Android app already ships
# (`FloatingNavigation`): 16px from the canvas sides, 8px above the system
# gesture area, `--radius-capsule` corners, one `--radius-pill` pill for the
# current tab and a 44px touch target. Desktop keeps the side column.
class NavigationCapsuleTest < ApplicationSystemTestCase
  teardown do
    page.current_window.resize_to(390, 844)
  end

  test "the phone navigation floats as the capsule" do
    visit root_path
    geometry = capsule_geometry

    assert_in_delta 16, geometry["left"], 1
    assert_in_delta 16, geometry["right"], 1
    assert_in_delta 8, geometry["gapBelow"], 1
    assert_operator geometry["width"], :<=, 448
    assert_equal "26px", geometry["radius"]
    assert_equal "1px", geometry["border"]
    assert_equal "rgb(24, 24, 27)", geometry["background"]
    assert_not_equal "none", geometry["shadow"]
    assert_operator geometry["tabHeight"], :>=, 44
    assert_equal "21px", geometry["tabRadius"]
  end

  test "the add screen keeps its controls above the capsule" do
    visit root_path
    form_bottom = page.evaluate_script("document.querySelector('#list_item_form').getBoundingClientRect().bottom")

    assert_operator form_bottom, :<=, capsule_top, "the add field is under the capsule"
  end

  test "the list and the toast stop above the capsule" do
    visit_list
    list_bottom = page.evaluate_script("document.querySelector('#list_items').getBoundingClientRect().bottom")
    assert_operator list_bottom, :<=, capsule_top, "the list is under the capsule"

    remove_item "Leite"
    within("#toast") { assert_text "Leite removido" }
    toast_card_bottom = page.evaluate_script("document.querySelector('#toast [role=status]').getBoundingClientRect().bottom")
    assert_operator toast_card_bottom, :<=, capsule_top, "the toast is under the capsule"
  end

  test "only the current tab is a pill, over accent-soft" do
    visit_list
    assert_equal [ "rgba(0, 0, 0, 0)", "rgba(45, 212, 191, 0.14)" ], tab_backgrounds
    assert_equal [ "rgb(139, 139, 148)", "rgb(94, 234, 212)" ], tab_colors

    show_add
    assert_equal [ "rgba(45, 212, 191, 0.14)", "rgba(0, 0, 0, 0)" ], tab_backgrounds
    assert_equal [ "rgb(94, 234, 212)", "rgb(139, 139, 148)" ], tab_colors
  end

  test "desktop keeps the side column instead of the capsule" do
    page.current_window.resize_to(1440, 1000)
    visit root_path
    geometry = capsule_geometry

    assert_equal "none", geometry["shadow"]
    assert_operator geometry["width"], :<, 200
    assert_selector ".desktop-brand", text: "Alva"
  end

  private

  def capsule_geometry
    page.evaluate_script(<<~JS)
      (() => {
        const capsule = document.querySelector(".app-navigation")
        const tab = capsule.querySelector("button")
        const style = getComputedStyle(capsule)
        const box = capsule.getBoundingClientRect()
        const canvas = document.documentElement
        return {
          left: box.left,
          right: canvas.clientWidth - box.right,
          width: box.width,
          gapBelow: canvas.clientHeight - box.bottom,
          radius: style.borderTopLeftRadius,
          border: style.borderTopWidth,
          background: style.backgroundColor,
          shadow: style.boxShadow,
          tabHeight: tab.getBoundingClientRect().height,
          tabRadius: getComputedStyle(tab).borderTopLeftRadius
        }
      })()
    JS
  end

  # The two tabs' paint: the current one is the `accent-soft` pill over
  # `accent-strong`, the other one keeps `ink-faint` and paints nothing.
  def tab_backgrounds
    page.evaluate_script(<<~JS)
      Array.from(document.querySelectorAll(".navigation-items > button")).map((tab) => getComputedStyle(tab).backgroundColor)
    JS
  end

  def tab_colors
    page.evaluate_script(<<~JS)
      Array.from(document.querySelectorAll(".navigation-items > button")).map((tab) => getComputedStyle(tab).color)
    JS
  end

  def capsule_top
    page.evaluate_script("document.querySelector('.app-navigation').getBoundingClientRect().top")
  end

  def toast_bottom
    page.evaluate_script("document.querySelector('#toast').getBoundingClientRect().bottom")
  end
end
