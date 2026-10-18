require "test_helper"

# What Chrome on Android needs to offer "Instalar app" instead of only a home
# screen shortcut (besides HTTPS, which comes from `tailscale serve`).
class PwaTest < ActionDispatch::IntegrationTest
  test "the page links the manifest and loads the service worker registration" do
    get root_path

    assert_select "link[rel=manifest][href=?]", "/manifest.json"
    assert_select "meta[name=theme-color]"
    assert_select "script[type=importmap]", text: /"pwa"/
  end

  test "the manifest has what installability requires" do
    get "/manifest.json"
    assert_response :success

    manifest = JSON.parse(response.body)
    assert_equal "/", manifest["start_url"]
    assert_equal "/", manifest["scope"]
    assert_equal "standalone", manifest["display"]
    assert manifest["name"].present?
    assert manifest["short_name"].present?
  end

  test "the manifest offers 192 and 512 icons plus a maskable one, all real files of that size" do
    get "/manifest.json"
    icons = JSON.parse(response.body)["icons"]

    any_sizes = icons.select { |icon| icon["purpose"] == "any" }.map { |icon| icon["sizes"] }
    assert_includes any_sizes, "192x192"
    assert_includes any_sizes, "512x512"
    assert icons.any? { |icon| icon["purpose"] == "maskable" }

    icons.each do |icon|
      path = Rails.public_path.join(icon["src"].delete_prefix("/"))
      assert path.exist?, "#{icon['src']} is missing"
      assert_equal icon["sizes"], png_size(path), "#{icon['src']} has the wrong size"
    end
  end

  test "serves the service worker" do
    get "/service-worker.js"

    assert_response :success
    assert_equal "text/javascript", response.media_type
  end

  private

  # Width and height from the PNG header (IHDR chunk).
  def png_size(path)
    width, height = File.binread(path, 8, 16).unpack("NN")
    "#{width}x#{height}"
  end
end
