require "test_helper"

class ListExportsControllerTest < ActionDispatch::IntegrationTest
  test "downloads the pending list as a dated PDF attachment" do
    travel_to Time.zone.local(2026, 3, 7, 12) do
      get list_export_path

      assert_response :success
      assert_equal "application/pdf", response.media_type
      assert_match(/\Aattachment;.*filename="lista-2026-03-07\.pdf"/, response.headers["Content-Disposition"])
    end
    assert response.body.start_with?("%PDF")

    text = PDF::Reader.new(StringIO.new(response.body)).pages.map(&:text).join("\n")
    assert_includes text, "Leite"
    assert_not_includes text, "Detergente"
  end

  test "dates the PDF by the household's clock, not UTC" do
    # 23:30 in São Paulo is already the next day in UTC.
    travel_to Time.utc(2026, 3, 8, 2, 30) do
      get list_export_path

      assert_match(/filename="lista-2026-03-07\.pdf"/, response.headers["Content-Disposition"])
      text = PDF::Reader.new(StringIO.new(response.body)).pages.map(&:text).join("\n")
      assert_includes text, "07/03/2026"
    end
  end

  test "is served at /lista.pdf" do
    assert_equal "/lista.pdf", list_export_path
    get "/lista.pdf"
    assert_response :success
  end
end
