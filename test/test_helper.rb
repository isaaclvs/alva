ENV["RAILS_ENV"] ||= "test"

# COVERAGE=1 bin/rails test measures coverage and fails under the minimum.
# Only for the unit suite: system tests alone cover less on purpose.
if ENV["COVERAGE"]
  require "simplecov"
  SimpleCov.start "rails" do
    enable_coverage :branch
    add_filter "/vendor/"
    minimum_coverage line: 97, branch: 95
  end
end

require_relative "../config/environment"
require "rails/test_help"
require "turbo/broadcastable/test_helper"

module ActiveSupport
  class TestCase
    # Run tests in parallel with specified workers
    parallelize(workers: :number_of_processors)

    if ENV["COVERAGE"]
      # Each worker writes its own result; SimpleCov merges them at the end.
      parallelize_setup { |worker| SimpleCov.command_name "#{SimpleCov.command_name}-#{worker}" }
      parallelize_teardown { SimpleCov.result }
    end

    # Setup all fixtures in test/fixtures/*.yml for all tests in alphabetical order.
    fixtures :all

    # turbo-rails only mixes this in once ActionCable loads, which depends on
    # test order; include it up front so every test can use it.
    include Turbo::Broadcastable::TestHelper

    # A list refresh is a single broadcast that replaces the summary, the list
    # and the "buy again" chips together. Returns the captured streams for further checks.
    def assert_list_broadcast(household, &block)
      streams = capture_turbo_stream_broadcasts(household, &block)
      assert_equal [ %w[replace list_summary], %w[replace list_items], %w[replace buy_again] ],
        streams.map { |stream| [ stream["action"], stream["target"] ] }
      streams
    end
  end
end
