require "application_system_test_case"

class ListExportTest < ApplicationSystemTestCase
  test "header has a download link to the PDF" do
    visit root_path

    assert_selector "header a[href='/lista.pdf'][title='Baixar PDF'][aria-label='Baixar PDF']"
  end

  test "download link survives list updates" do
    visit root_path

    click_button "Leite"

    assert_selector "header a[href='/lista.pdf']"
  end
end
