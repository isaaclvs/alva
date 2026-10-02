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

    # Add more helper methods to be used by all tests here...
  end
end
