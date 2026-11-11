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

  test "stale dictionary keeps the manual item correction without overwriting learned data" do
    item = list_items(:milk)
    ItemCategorizer.learn("Leite especial", categories(:produce))
    DictionaryChange.bump_revision!
    before = SyncScope.revision_for(SyncScope::DICTIONARY)
    result = submit(correct_item_command(item.public_id, "Leite especial", lock_version: item.lock_version,
      category_id: categories(:other).public_id, dictionary_revision: before - 1)).first
    assert_equal "applied", result[:status]
    assert_equal categories(:other).id, item.reload.category_id
    assert_equal categories(:other).public_id, result[:data]["item"]["category_public_id"]
    assert_equal categories(:produce).id, CatalogItem.find_by!(normalized_name: TextNormalizer.call("Leite especial")).category_id
    assert_equal before, SyncScope.revision_for(SyncScope::DICTIONARY)
    assert_equal false, result[:data]["learned"]
    assert_equal categories(:other).public_id, result[:data]["learning_pending"]["category_id"]
  end

  test "explicit same category is manual even when renaming and dictionary-only learning advances revision" do
    item = list_items(:milk)
    revision = SyncScope.revision_for(SyncScope::DICTIONARY)
    result = submit(correct_item_command(item.public_id, item.name, lock_version: item.lock_version,
      category_id: item.category.public_id, dictionary_revision: revision)).first
    assert_equal "applied", result[:status]
    assert_equal 1, @home.reload.sync_revision
    assert_equal revision + 1, SyncScope.revision_for(SyncScope::DICTIONARY)
    assert_equal true, result[:data]["learned"]
    assert_equal 0, item.reload.lock_version
    again = submit(correct_item_command(item.public_id, item.name, lock_version: item.lock_version,
      category_id: item.category.public_id, dictionary_revision: revision + 1)).first
    assert_equal "noop", again[:status]
    assert_equal 1, @home.reload.sync_revision
    renamed = submit(correct_item_command(item.public_id, "Nome desconhecido", lock_version: 0,
      category_id: item.category.public_id, dictionary_revision: revision + 1)).first
    assert_equal "applied", renamed[:status]
    assert_equal categories(:dairy).id, item.reload.category_id
  end

  test "rename without manual category uses the dictionary and does not teach it" do
    item = list_items(:milk)
    result = submit(correct_item_command(item.public_id, "Produto desconhecido", lock_version: 0)).first
    assert_equal "applied", result[:status]
    assert_equal categories(:other).id, item.reload.category_id
    assert_equal 0, SyncScope.revision_for(SyncScope::DICTIONARY)
    assert_equal false, result[:data]["learned"]
  end

  test "malformed preconditions are rejected even with a valid dependency" do
    add = add_items_command([ uuid, "Produto" ])
    item_id = submit(add).first[:data]["items"].first["canonical_public_id"]
    [ "0", [], {}, -1, 0.5, true ].each do |value|
      command = correct_item_command(item_id, "Novo", depends_on: add["id"], lock_version: value)
      result = submit(command).first
      assert_equal "rejected", result[:status]
      assert_equal "preconditions.lock_version", result[:data]["field"]
    end
    missing_dictionary = correct_item_command(item_id, "Novo", lock_version: 0, category_id: categories(:other).public_id)
    assert_equal "preconditions.dictionary_revision", submit(missing_dictionary).first[:data]["field"]
  end

  test "foreign and unknown ids are indistinguishable and archived or removed items are gone" do
    foreign = Household.create!(name: "Outra").list_items.create!(name: "Estrangeiro")
    [ foreign.public_id, uuid ].each do |public_id|
      result = submit(correct_item_command(public_id, "Novo", lock_version: 0)).first
      assert_equal "not_found", result[:error]
      assert_nil result[:data]
    end
    archived = list_items(:detergent)
    @home.finish_shopping!
    assert_equal "gone", submit(correct_item_command(archived.public_id, "Novo", lock_version: 0)).first[:error]
    removed = list_items(:milk)
    @home.remove_item(removed)
    assert_equal "gone", submit(correct_item_command(removed.public_id, "Novo", lock_version: 0)).first[:error]
  end

  test "alias correction and replay preserve original receipt after a later remote change" do
    alias_id = uuid
    milk = list_items(:milk)
    add = add_items_command([ alias_id, "Leite" ])
    submit(add)
    command = correct_item_command(alias_id, "Leite especial", depends_on: add["id"])
    original = submit(command).first
    assert_equal "applied", original[:status]
    assert_equal milk.public_id, original[:data]["item"]["canonical_public_id"]
    assert milk.reload.correct(name: "Leite remoto", category_id: nil)
    replay = submit(command).first
    assert_equal "duplicate", replay[:status]
    assert_equal original[:data], replay[:data]
    assert_equal "Leite remoto", milk.reload.name
  end

  test "a no-op correction does not advance revisions" do
    item = list_items(:milk)
    assert_no_difference -> { @home.reload.sync_revision } do
      assert_equal "noop", submit(correct_item_command(item.public_id, item.name, lock_version: 0)).first[:status]
    end
  end

  private
  def submit(*commands) = SyncCommandProcessor.new(device: @ana).call(commands)
end
