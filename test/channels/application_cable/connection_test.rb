require "test_helper"

class ApplicationCable::ConnectionTest < ActionCable::Connection::TestCase
  tests ApplicationCable::Connection

  setup do
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha-de-teste", cost: 4).to_s
  end

  teardown { ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest }

  test "anonymous clients cannot connect even with a known stream name" do
    assert_reject_connection { connect }
  end

  test "an authenticated device can connect" do
    cookies.encrypted[HouseholdAccess::COOKIE] = HouseholdAccess.token
    connect
    assert connection
  end

  test "forged cookies cannot connect" do
    cookies[HouseholdAccess::COOKIE] = HouseholdAccess.token
    assert_reject_connection { connect }
  end

  test "a rotated password revokes previous cookies" do
    cookies.encrypted[HouseholdAccess::COOKIE] = HouseholdAccess.token
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("outra", cost: 4).to_s
    assert_reject_connection { connect }
  end

  test "private installations without a password continue to work" do
    ENV.delete("ALVA_PASSWORD_DIGEST")
    connect
    assert connection
  end
end
