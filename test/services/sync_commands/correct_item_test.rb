require "test_helper"
require "support/sync_command_builders"

class SyncCommands::CorrectItemTest < ActiveSupport::TestCase
  include SyncCommandBuilders
  setup do
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha-da-casa", cost: 4).to_s
    @home = households(:home)
    @ana, = AndroidDevice.issue(household: @home, name: "Ana", scope: AndroidDevice::SYNC)
  end

  teardown do
    ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest
  end

  test "corrects an addition through dependency and ledger replay returns the original receipt" do
    add = add_items_command([ uuid, "Item novo" ]).merge("id" => uuid)
    added = submit(add).first
    item_id = added[:data]["items"].first["canonical_public_id"]
    correction = correct_item_command(item_id, "Item corrigido", depends_on: add["id"])
    result = submit(correction).first
    assert_equal "applied", result[:status]
    assert_equal "Item corrigido", result[:data]["item"]["name"]
    assert_equal "duplicate", submit(correction).first[:status]
    assert_equal "Item corrigido", @home.list_items.find_by!(public_id: item_id).name
  end

  test "stale version and duplicate pending item conflict without mutation" do
    item = list_items(:milk)
    stale = correct_item_command(item.public_id, "Leite novo", lock_version: 44)
    assert_equal "conflict", submit(stale).first[:status]
    @home.list_items.create!(name: "Leite novo")
    duplicate = correct_item_command(item.public_id, "Leite novo", lock_version: item.lock_version)
    result = submit(duplicate).first
    assert_equal "duplicate_pending", result[:data]["reason"]
    assert_equal "Leite", item.reload.name
  end

  test "manual category update learns only on matching dictionary revision" do
    item = list_items(:milk)
    category = categories(:other)
    revision = SyncScope.revision_for(SyncScope::DICTIONARY)
    cmd = correct_item_command(item.public_id, "Leite especial", lock_version: item.lock_version,
      category_id: category.public_id, dictionary_revision: revision)
    result = submit(cmd).first
    assert_equal "applied", result[:status]
    assert_equal category.id, item.reload.category_id
    learned = CatalogItem.find_by!(normalized_name: TextNormalizer.call("Leite especial"))
    assert learned.learned?
  end

  private
  def submit(*commands) = SyncCommandProcessor.new(device: @ana).call(commands)
end
