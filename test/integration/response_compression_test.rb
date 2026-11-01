require "test_helper"
require "zlib"

class ResponseCompressionTest < ActionDispatch::IntegrationTest
  test "HTML and Turbo responses negotiate gzip and keep working without it" do
    get root_path, headers: { "Accept-Encoding" => "gzip" }
    assert_response :success
    assert_equal "gzip", response.headers["Content-Encoding"]
    assert_includes response.headers["Vary"], "Accept-Encoding"
    assert_includes Zlib.gunzip(response.body), "add_screen"

    patch toggle_list_item_path(list_items(:milk)), as: :turbo_stream, headers: { "Accept-Encoding" => "gzip" }
    assert_equal "gzip", response.headers["Content-Encoding"]
    stream = Zlib.gunzip(response.body)
    assert_includes stream, 'target="list_counter"'
    assert_not_includes stream, 'target="buy_again"'

    get root_path, headers: { "Accept-Encoding" => "identity" }
    assert_nil response.headers["Content-Encoding"]
    assert_select "#add_screen"
  end
end
