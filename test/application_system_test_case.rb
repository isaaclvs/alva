require "test_helper"

class ApplicationSystemTestCase < ActionDispatch::SystemTestCase
  driven_by :selenium, using: :headless_chrome, screen_size: [ 390, 844 ] do |options|
    # Lets machines without a system Chrome point at another binary.
    options.binary = ENV["CHROME_BIN"] if ENV["CHROME_BIN"]
  end
end
