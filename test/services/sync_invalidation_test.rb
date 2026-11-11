require "test_helper"

class SyncInvalidationTest < ActiveSupport::TestCase
  include ActionCable::TestHelper
  self.use_transactional_tests = false

  setup { @household = Household.create!(name: "Invalidation test") }
  teardown do
    SyncCommand.where(household: @household).delete_all
    ListItem.where(household: @household).delete_all
    SyncTombstone.where(household: @household).delete_all
    ShoppingTrip.where(household: @household).delete_all
    @household.delete if @household
  end

  test "web mutations broadcast revisions only after commit and duplicate additions do not" do
    stream = SyncInvalidation.stream_for(@household)
    assert_broadcasts(stream, 1) do
      Household.transaction do
        @household.add_items("Arroz, Feijão")
        assert_broadcasts(stream, 0)
      end
    end
    assert_broadcast_on stream, SyncInvalidation.payload(@household)
    assert_equal %i[dictionary_revision household_revision type], SyncInvalidation.payload(@household).keys.sort
    assert_broadcasts(stream, 0) { @household.add_item("Arroz") }
    assert_broadcasts(stream, 1) { @household.list_items.first.toggle_purchased! }
    assert_broadcasts(stream, 1) { @household.finish_shopping! }
    assert_broadcasts(stream, 1) { @household.remove_item(@household.list_items.on_list.first) }
  end

  test "rollback publishes nothing" do
    stream = SyncInvalidation.stream_for(@household)
    assert_broadcasts(stream, 0) do
      Household.transaction do
        @household.add_item("Arroz")
        raise ActiveRecord::Rollback
      end
    end
    assert_equal 0, @household.reload.sync_revision
  end

  test "dictionary changes notify every household including households with no list change" do
    old_revision = SyncScope.revision_for(SyncScope::DICTIONARY)
    stream = SyncInvalidation.stream_for(@household)
    assert_broadcasts(stream, 1) do
      Household.transaction do
        DictionaryChange.bump_revision!
        assert_broadcasts(stream, 0)
      end
    end
    assert_equal old_revision + 1, SyncInvalidation.payload(@household)[:dictionary_revision]
  ensure
    SyncScope.where(name: SyncScope::DICTIONARY).update_all(revision: old_revision) if old_revision
  end
end
