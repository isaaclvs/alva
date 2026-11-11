require "test_helper"

class SyncCable::ChannelTest < ActionCable::Channel::TestCase
  tests SyncCable::Channel

  setup do
    @device = Struct.new(:household).new(households(:home))
    stub_connection android_device: @device, authorized_device?: true
  end

  test "subscribes only to the authenticated household ignoring payload selectors" do
    subscribe household_id: "other", stream: "arbitrary"
    assert subscription.confirmed?
    assert_has_stream SyncInvalidation.stream_for(households(:home))
    assert_equal 1, subscription.streams.size
  end

  test "refuses expired credentials before streaming" do
    stub_connection android_device: @device, authorized_device?: false
    subscribe
    assert subscription.rejected?
    assert_no_streams
  end
end
