ENV["RAILS_ENV"] ||= "test"
require_relative "../config/environment"
require "rails/test_help"
require "turbo/broadcastable/test_helper"

module ActiveSupport
  class TestCase
    # Run tests in parallel with specified workers
    parallelize(workers: :number_of_processors)

    # Setup all fixtures in test/fixtures/*.yml for all tests in alphabetical order.
    fixtures :all

    # turbo-rails only mixes this in once ActionCable loads, which depends on
    # test order; include it up front so every test can use it.
    include Turbo::Broadcastable::TestHelper

    # A list refresh is a single broadcast that replaces the summary and the
    # list together. Returns the captured streams for further checks.
    def assert_list_broadcast(household, &block)
      streams = capture_turbo_stream_broadcasts(household, &block)
      assert_equal [ %w[replace list_summary], %w[replace list_items] ],
        streams.map { |stream| [ stream["action"], stream["target"] ] }
      streams
    end
  end
end
