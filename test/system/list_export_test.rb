require "application_system_test_case"

class ListExportTest < ApplicationSystemTestCase
  test "the list header has a download link to the PDF" do
    visit_list

    within("#list_screen header") do
      open_menu { assert_selector "a[href='/lista.pdf'][title='Baixar PDF'][aria-label='Baixar PDF']" }
    end
  end

  test "download link survives list updates" do
    visit_list

    click_button "Leite"

    within("#list_screen header") { open_menu { assert_selector "a[href='/lista.pdf']" } }
  end
end
