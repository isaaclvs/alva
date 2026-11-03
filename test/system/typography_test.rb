require "application_system_test_case"

class TypographyTest < ApplicationSystemTestCase
  test "local font files load all weights with Portuguese text" do
    visit root_path
    assert_includes page.evaluate_script("getComputedStyle(document.body).fontFamily"), "Nunito Sans"
    loaded = page.evaluate_async_script(<<~JS)
      const done = arguments[0]
      Promise.all([400, 600, 700].map(weight =>
        document.fonts.load(weight + ' 16px "Nunito Sans"', 'Maçã, pão, café e limão')
      )).then(faces => done(faces.every(group => group.length > 0)), () => done(false))
    JS
    assert loaded, "The locally served Nunito Sans files must load successfully"
  end
end
