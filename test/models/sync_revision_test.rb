require "test_helper"

# A household follows its shared changes by a monotonic revision, and deletions
# leave tombstones (see docs/plans/android-sync-contract-v1.md). updated_at is
# never the cursor, and the revision only moves for an effective change.
class SyncRevisionTest < ActiveSupport::TestCase
  setup do
    @household = households(:home)
  end

  def revision
    @household.reload.sync_revision
  end

  def dictionary_revision
    SyncScope.revision_for(SyncScope::DICTIONARY)
  end

  test "a household starts at revision zero and the dictionary has none yet" do
    assert_equal 0, revision
    assert_equal 0, dictionary_revision
  end

  test "adding a name moves the revision once" do
    assert_difference -> { revision }, 1 do
      @household.add_item("Banana")
    end
  end

  test "a batch of names is one shared change" do
    assert_difference -> { revision }, 1 do
      @household.add_items("Banana, pão, arroz")
    end
  end

  test "a batch that revives and creates together is still one change" do
    assert_difference -> { revision }, 1 do
      assert_equal %i[revived created], @household.add_items("detergente, arroz").map(&:outcome)
    end
  end

  test "duplicates, invalid names and empty batches move nothing" do
    assert_no_difference -> { revision } do
      assert_equal :duplicate, @household.add_item("Leite").outcome
      assert_equal :invalid, @household.add_item("  ").outcome
      assert_equal [ :duplicate ], @household.add_items("leite, LEITE").map(&:outcome)
    end
  end

  test "marking and unmarking each move the revision once" do
    item = list_items(:milk)

    assert_difference -> { revision }, 1 do
      assert item.toggle_purchased!
    end
    assert_difference -> { revision }, 1 do
      assert item.toggle_purchased!
    end
  end

  test "a toggle refused for a duplicate pending name moves nothing" do
    purchased = list_items(:detergent)
    ListItem.insert!({ name: "Detergente", normalized_name: "detergente", household_id: @household.id,
      status: 0, public_id: SecureRandom.uuid })

    assert_no_difference -> { revision } do
      assert_not purchased.toggle_purchased!
    end
  end

  test "undoing a purchase moves the revision once" do
    item = list_items(:milk)
    previous_status = item.status
    previous_purchased_at = item.purchased_at
    item.toggle_purchased!
    token = PurchaseUndo.load(PurchaseUndo.dump(item, status: previous_status, purchased_at: previous_purchased_at))

    assert_difference -> { revision }, 1 do
      assert item.undo_purchase!(token)
    end
  end

  test "a name correction moves the household revision, not the dictionary's" do
    item = list_items(:milk)

    assert_no_difference -> { dictionary_revision } do
      assert_difference -> { revision }, 1 do
        assert item.correct(name: "Leite integral", category_id: nil)
      end
    end
  end

  test "picking a category by hand moves the household and the dictionary revisions" do
    item = @household.list_items.create!(name: "Tomate cereja")

    assert_difference -> { dictionary_revision }, 1 do
      assert_difference -> { revision }, 1 do
        assert item.correct(name: item.name, category_id: categories(:produce).id)
      end
    end
  end

  test "learning a category the dictionary already knows moves only the household" do
    ItemCategorizer.learn("Tomate", categories(:produce))
    item = @household.list_items.create!(name: "Tomate", category: categories(:other))

    assert_no_difference -> { dictionary_revision } do
      assert_difference -> { revision }, 1 do
        assert item.correct(name: "Tomate", category_id: categories(:produce).id)
      end
    end
  end

  test "removing an item records a tombstone carrying the new revision" do
    item = list_items(:milk)
    new_revision = nil

    assert_difference -> { revision }, 1 do
      @household.remove_item(item)
      new_revision = @household.sync_revision
    end

    tombstone = SyncTombstone.find_by!(public_id: item.public_id)
    assert_equal "ListItem", tombstone.record_type
    assert_equal SyncTombstone::HOUSEHOLD, tombstone.scope
    assert_equal @household.id, tombstone.household_id
    assert_equal new_revision, tombstone.revision
  end

  test "restoring an item forgets its tombstone and moves the revision" do
    item = list_items(:milk)
    snapshot = ListItemSnapshot.load(ListItemSnapshot.dump(item))
    @household.remove_item(item)

    assert_difference -> { revision }, 1 do
      assert_equal item.public_id, @household.restore_item(snapshot).public_id
    end

    assert_nil SyncTombstone.find_by(public_id: item.public_id)
  end

  test "restoring nothing moves nothing" do
    snapshot = ListItemSnapshot.load(ListItemSnapshot.dump(list_items(:milk)))

    assert_no_difference -> { revision } do
      assert_nil @household.restore_item(snapshot)
    end
  end

  test "finishing a trip moves the revision and leaves no tombstone for archived items" do
    assert_difference -> { revision }, 1 do
      assert @household.finish_shopping!
    end

    assert_equal 0, SyncTombstone.count
  end

  test "finishing with nothing purchased changes nothing" do
    list_items(:detergent).toggle_purchased!

    assert_no_difference -> { revision } do
      assert_nil @household.finish_shopping!
    end
    assert_equal 0, @household.shopping_trips.count
  end

  test "undoing a trip moves the revision and records the trip tombstone" do
    trip = @household.finish_shopping!

    assert_difference -> { revision }, 1 do
      assert @household.undo_shopping!(trip)
    end

    assert SyncTombstone.exists?(public_id: trip.public_id, record_type: "ShoppingTrip")
  end

  test "undoing a trip records a tombstone for the archived duplicate it drops" do
    archived = list_items(:detergent)
    trip = @household.finish_shopping!
    @household.add_item("detergente")

    assert_difference -> { revision }, 1 do
      assert @household.undo_shopping!(trip)
    end

    assert SyncTombstone.exists?(public_id: archived.public_id, record_type: "ListItem")
  end

  test "undoing an older trip moves nothing" do
    older = @household.finish_shopping!
    @household.list_items.create!(name: "Arroz", status: :purchased, purchased_at: Time.current)
    @household.finish_shopping!

    assert_no_difference -> { revision } do
      assert_not @household.undo_shopping!(older)
    end
  end

  test "saving and removing a habitual item are shared changes with a tombstone" do
    habit = nil

    assert_difference -> { revision }, 1 do
      habit = @household.add_habitual_item("Café")
    end
    assert habit.persisted?

    assert_difference -> { revision }, 1 do
      @household.remove_habitual_item(habit)
    end

    tombstone = SyncTombstone.find_by!(public_id: habit.public_id)
    assert_equal "HabitualItem", tombstone.record_type
  end

  test "an invalid habitual item moves nothing" do
    assert_no_difference -> { revision } do
      assert_not @household.add_habitual_item("").persisted?
    end
  end

  test "adding several names from the habits panel is one shared change" do
    assert_difference -> { revision }, 1 do
      @household.add_item_names([ "Café", "Açúcar" ])
    end
  end

  test "adding habits that are already on the list moves nothing" do
    @household.add_item_names([ "Café" ])

    assert_no_difference -> { revision } do
      @household.add_item_names([ "café" ])
    end
  end

  test "a dictionary change from outside an operation moves both revisions" do
    assert_difference -> { dictionary_revision }, 1 do
      assert_difference -> { revision }, 1 do
        DictionaryChange.record!
      end
    end
  end
end
