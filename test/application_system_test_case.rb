require "test_helper"

class ApplicationSystemTestCase < ActionDispatch::SystemTestCase
  # Browser sessions are heavier than model tests. Keep the full suite stable
  # when its size crosses Rails' automatic parallelization threshold.
  parallelize(workers: 1)

  driven_by :selenium, using: :headless_chrome, screen_size: [ 390, 844 ] do |options|
    # Lets machines without a system Chrome point at another binary.
    options.binary = ENV["CHROME_BIN"] if ENV["CHROME_BIN"]
  end
end
