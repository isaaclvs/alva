require "application_system_test_case"

class ListExportTest < ApplicationSystemTestCase
  test "the list header has a download link to the PDF" do
    visit_list

    assert_selector "#list_screen header a[href='/lista.pdf'][title='Baixar PDF'][aria-label='Baixar PDF']"
  end

  test "download link survives list updates" do
    visit_list

    click_button "Leite"

    assert_selector "#list_screen header a[href='/lista.pdf']"
  end
end
