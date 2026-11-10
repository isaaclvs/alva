require "test_helper"
require "support/sync_command_builders"

# The real add_items executor behind the real processor and credentials.
class SyncCommands::AddItemsTest < ActiveSupport::TestCase
  include SyncCommandBuilders

  setup do
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha-da-casa", cost: 4).to_s
    @home = households(:home)
    @ana, = AndroidDevice.issue(household: @home, name: "Ana", scope: AndroidDevice::SYNC)
    @isaac, = AndroidDevice.issue(household: @home, name: "Isaac", scope: AndroidDevice::SYNC)
    @beach = Household.create!(name: "Praia")
    @beach_device, = AndroidDevice.issue(household: @beach, name: "Praia", scope: AndroidDevice::SYNC)
  end

  teardown do
    ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest
  end

  test "creates each item with the UUID the phone generated, categorized like the app does" do
    milk_id, tapioca_id = uuid, uuid
    before = @home.reload.sync_revision

    result = submit(@ana, add_items_command([ milk_id, "Banana" ], [ tapioca_id, "Tapioca" ])).first

    assert_equal "applied", result[:status]
    assert_equal before + 1, result[:revision], "one revision for the whole command"
    items = result[:data]["items"]
    assert_equal [ milk_id, tapioca_id ], items.map { |item| item["public_id"] }
    assert_equal [ milk_id, tapioca_id ], items.map { |item| item["canonical_public_id"] }
    assert_equal %w[created created], items.map { |item| item["outcome"] }
    assert_equal [ "pending", 0 ], items.first.values_at("status", "lock_version")
    banana = @home.list_items.find_by!(public_id: milk_id)
    assert_equal "Banana", banana.name
    assert_equal categories(:produce), banana.category, "the dictionary decides the category"
    assert_equal categories(:other), @home.list_items.find_by!(public_id: tapioca_id).category
    assert_equal 0, SyncAlias.count
  end

  test "a name already pending is not duplicated and the phone's UUID becomes an alias" do
    canonical = list_items(:milk)
    mine = uuid
    before = @home.reload.sync_revision

    result = submit(@ana, add_items_command([ mine, "leite" ])).first

    assert_equal "noop", result[:status]
    entry = result[:data]["items"].first
    assert_equal [ mine, canonical.public_id, "duplicate", "Leite" ], entry.values_at("public_id", "canonical_public_id", "outcome", "name")
    assert_equal 1, @home.list_items.where(normalized_name: "leite").count
    assert_equal before, @home.reload.sync_revision, "unifying changes nothing shared"
    stored = SyncAlias.find_by!(public_id: mine)
    assert_equal [ @home, canonical.public_id ], [ stored.household, stored.canonical_public_id ]
  end

  test "two phones adding the same name end with one item that both UUIDs reach" do
    ana_id, isaac_id = uuid, uuid

    first = submit(@ana, add_items_command([ ana_id, "Tapioca" ])).first
    second = submit(@isaac, add_items_command([ isaac_id, "tapioca" ])).first

    assert_equal %w[applied noop], [ first[:status], second[:status] ]
    assert_equal ana_id, second[:data]["items"].first["canonical_public_id"]
    assert_equal 1, @home.list_items.where(normalized_name: "tapioca").count

    submit(@isaac, set_item_state_command(isaac_id, "purchased", lock_version: 0))
    assert_equal "purchased", @home.list_items.find_by!(public_id: ana_id).status
  end

  test "a name that comes back as a purchased item needs the purchase the phone saw" do
    detergent = list_items(:detergent)
    mine = uuid

    unseen = submit(@ana, add_items_command([ mine, "Detergente" ])).first

    assert_equal "conflict", unseen[:status]
    assert_equal "precondition_failed", unseen[:error]
    assert_equal "purchase_not_observed", unseen[:data]["reason"]
    assert_equal "purchased", unseen[:data]["current"]["status"]
    assert_equal detergent.lock_version, unseen[:data]["current"]["lock_version"]
    assert detergent.reload.purchased?, "an old request must not bring a recent purchase back"
    assert_equal 0, SyncCommand.where(command_type: "add_items").count
    assert_equal 0, SyncAlias.count

    seen = submit(@ana, add_items_command(
      { "public_id" => mine, "name" => "Detergente", "revives" => { "public_id" => detergent.public_id, "lock_version" => detergent.lock_version } }
    )).first

    assert_equal "applied", seen[:status]
    assert_equal [ "revived", detergent.public_id ], seen[:data]["items"].first.values_at("outcome", "canonical_public_id")
    assert detergent.reload.pending?
    assert_equal detergent.public_id, SyncAlias.find_by!(public_id: mine).canonical_public_id
  end

  test "a purchase that changed after the phone saw it is a conflict, not a revival" do
    detergent = list_items(:detergent)
    saw = detergent.lock_version
    detergent.toggle_purchased!
    detergent.toggle_purchased!

    result = submit(@ana, add_items_command(
      { "public_id" => uuid, "name" => "Detergente", "revives" => { "public_id" => detergent.public_id, "lock_version" => saw } }
    )).first

    assert_equal [ "conflict", "purchase_changed", "purchased" ],
      [ result[:status], result[:data]["reason"], result[:data]["current"]["status"] ]
    assert_equal detergent.reload.lock_version, result[:data]["current"]["lock_version"]
    assert_equal 1, @home.list_items.where(normalized_name: "detergente").count
  end

  test "revives pointing at a purchase that was archived or never existed is refused" do
    detergent = list_items(:detergent)
    saw = detergent.lock_version
    @home.finish_shopping!

    gone = submit(@ana, add_items_command(
      { "public_id" => uuid, "name" => "Detergente", "revives" => { "public_id" => detergent.public_id, "lock_version" => saw } }
    )).first
    missing = submit(@ana, add_items_command(
      { "public_id" => uuid, "name" => "Detergente", "revives" => { "public_id" => uuid, "lock_version" => 0 } }
    )).first

    assert_equal [ "rejected", "gone", "archived" ], gone.values_at(:status, :error).push(gone[:data]["reason"])
    assert_equal [ "rejected", "not_found" ], missing.values_at(:status, :error)
    assert_equal 0, @home.list_items.on_list.where(normalized_name: "detergente").count, "nothing was created"
  end

  test "typing an archived name again creates a new item and the archived UUID stays gone" do
    detergent = list_items(:detergent)
    @home.finish_shopping!
    fresh = uuid

    created = submit(@ana, add_items_command([ fresh, "Detergente" ])).first
    reused = submit(@ana, add_items_command([ detergent.public_id, "Detergente" ])).first

    assert_equal "applied", created[:status]
    assert_not_equal detergent.id, @home.list_items.on_list.find_by!(public_id: fresh).id
    assert_equal [ "rejected", "gone" ], reused.values_at(:status, :error)
    assert_equal({ "reason" => "archived", "canonical_public_id" => detergent.public_id, "index" => 0, "public_id" => detergent.public_id },
      reused[:data])
  end

  test "a removed item's UUID cannot be used to add again" do
    milk = list_items(:milk)
    @home.remove_item(milk)

    result = submit(@ana, add_items_command([ milk.public_id, "Leite" ])).first

    assert_equal [ "rejected", "gone" ], result.values_at(:status, :error)
    assert_equal "removed", result[:data]["reason"]
    assert_nil @home.pending_item_named("Leite")
  end

  test "a UUID already identifying an item replays as that item, never as a different one" do
    milk = list_items(:milk)

    same = submit(@ana, add_items_command([ milk.public_id, "Leite" ])).first
    other = submit(@ana, add_items_command([ milk.public_id, "Pão" ])).first

    assert_equal [ "noop", "duplicate" ], [ same[:status], same[:data]["items"].first["outcome"] ]
    assert_equal [ "rejected", "public_id_conflict" ], other.values_at(:status, :error)
    assert_equal "Leite", milk.reload.name
    assert_nil @home.pending_item_named("Pão")
  end

  test "a UUID that belongs to another household is refused without revealing whose it is" do
    beach_item = @beach.list_items.create!(name: "Coco")
    beach_alias = SyncAlias.create!(household: @beach, public_id: uuid, canonical_public_id: beach_item.public_id, command_id: uuid)
    @beach.remove_item(@beach.list_items.create!(name: "Sorvete")) # leaves a tombstone
    removed = SyncTombstone.find_by!(household_id: @beach.id).public_id

    [ beach_item.public_id, beach_alias.public_id, removed ].each do |claimed|
      result = submit(@ana, add_items_command([ claimed, "Tapioca" ])).first

      assert_equal({ id: result[:id], status: "rejected", error: "public_id_conflict", data: { "index" => 0, "public_id" => claimed } }, result)
    end
    assert_nil @home.pending_item_named("Tapioca")
    assert_equal "Coco", beach_item.reload.name
  end

  test "households are separate namespaces for names and for lookups" do
    submit(@beach_device.reload, add_items_command([ uuid, "Tapioca" ]))
    mine = uuid

    result = submit(@ana, add_items_command([ mine, "Tapioca" ])).first

    assert_equal "applied", result[:status], "the other household's item does not unify with this one"
    assert_equal 1, @home.list_items.where(name: "Tapioca").count
    assert_equal 1, @beach.list_items.where(name: "Tapioca").count
    assert_equal 0, SyncAlias.count
  end

  test "an entry that conflicts undoes the whole command" do
    detergent = list_items(:detergent)
    ok = uuid

    result = submit(@ana, add_items_command([ ok, "Tapioca" ], [ uuid, "Detergente" ])).first

    assert_equal "conflict", result[:status]
    assert_equal 1, result[:data]["index"], "says which entry stopped the command"
    assert_nil @home.list_items.find_by(public_id: ok), "the first entry is rolled back with the rest"
    assert detergent.reload.purchased?
    assert_equal 0, SyncCommand.where(command_type: "add_items").count
  end

  test "names repeated inside one command become aliases of the first" do
    first, second = uuid, uuid

    result = submit(@ana, add_items_command([ first, "Tapioca" ], [ second, "tapioca " ])).first

    assert_equal "applied", result[:status]
    assert_equal %w[created duplicate], result[:data]["items"].map { |item| item["outcome"] }
    assert_equal first, SyncAlias.find_by!(public_id: second).canonical_public_id
    assert_equal 1, @home.list_items.where(normalized_name: "tapioca").count
  end

  test "resending a command returns the original result and the same UUIDs keep working" do
    command = add_items_command([ uuid, "Tapioca" ])
    original = submit(@ana, command).first
    revision = @home.reload.sync_revision

    replay = submit(@ana, command).first

    assert_equal [ "duplicate", "applied", original[:data], original[:revision] ],
      replay.values_at(:status, :original_status, :data, :revision)
    assert_equal revision, @home.reload.sync_revision
    assert_equal 1, @home.list_items.where(name: "Tapioca").count
  end

  test "the content of a command already sent stays immutable" do
    command = add_items_command([ uuid, "Tapioca" ])
    submit(@ana, command)
    changed = command.merge("payload" => { "items" => [ command["payload"]["items"].first.merge("name" => "Polvilho") ] })

    result = submit(@ana, changed).first

    assert_equal [ "rejected", "idempotency_conflict" ], result.values_at(:status, :error)
    assert_nil @home.pending_item_named("Polvilho")
    assert_equal "Tapioca", @home.list_items.find_by!(public_id: command["payload"]["items"].first["public_id"]).name
  end

  test "malformed payloads are rejected with the field and change nothing" do
    good = { "public_id" => uuid, "name" => "Tapioca" }
    [
      [ { "names" => [ "Tapioca" ] }, "payload.items" ],
      [ { "items" => [] }, "payload.items" ],
      [ { "items" => Array.new(31) { { "public_id" => uuid, "name" => "Item #{SecureRandom.hex(2)}" } } }, "payload.items" ],
      [ { "items" => [ "Tapioca" ] }, "payload.items[0]" ],
      [ { "items" => [ good, { "public_id" => "nao-e-uuid", "name" => "Pão" } ] }, "payload.items[1].public_id" ],
      [ { "items" => [ good, { "public_id" => uuid, "name" => 7 } ] }, "payload.items[1].name" ],
      [ { "items" => [ good, good ] }, "payload.items.public_id" ],
      [ { "items" => [ good.merge("revives" => "x") ] }, "payload.items[0].revives" ],
      [ { "items" => [ good.merge("revives" => { "public_id" => uuid, "lock_version" => -1 }) ] }, "payload.items[0].revives" ]
    ].each do |payload, field|
      result = submit(@ana, { "id" => uuid, "type" => "add_items", "payload" => payload }).first

      assert_equal [ "rejected", "malformed_command", field ], [ result[:status], result[:error], result[:data]["field"] ], payload.inspect
    end
    assert_nil @home.pending_item_named("Tapioca")
    assert_equal 0, SyncCommand.where(command_type: "add_items").count
  end

  test "blank and oversized names are invalid_name and keep the index" do
    [ "   ", "x" * (SyncCommands::AddItems::MAX_NAME_LENGTH + 1) ].each do |name|
      result = submit(@ana, add_items_command([ uuid, "Tapioca" ], [ uuid, name ])).first

      assert_equal [ "rejected", "invalid_name", 1 ], [ result[:status], result[:error], result[:data]["index"] ]
    end
    assert_nil @home.pending_item_named("Tapioca")
  end

  test "the list is broadcast once, after the commit, and not at all when the command fails" do
    streams = assert_list_broadcast(@home) { submit(@ana, add_items_command([ uuid, "Tapioca" ], [ uuid, "Polvilho" ])) }
    assert_includes streams[1].to_html, "Polvilho"

    failing = capture_turbo_stream_broadcasts(@home) do
      submit(@ana, add_items_command([ uuid, "Quiabo" ], [ uuid, "Detergente" ]))
    end
    assert_empty failing, "a conflict rolled the command back and announces nothing"
  end

  test "a failure after the effects leaves no item, alias, ledger row or broadcast" do
    @ana.household.define_singleton_method(:record_shared_change!) { |**| raise "falha depois do efeito" }

    streams = capture_turbo_stream_broadcasts(@home) do
      assert_raises(RuntimeError) { submit(@ana, add_items_command([ uuid, "Tapioca" ], [ uuid, "leite" ])) }
    end

    assert_empty streams
    assert_nil @home.pending_item_named("Tapioca")
    assert_equal 0, SyncAlias.count
    assert_equal 0, SyncCommand.where(command_type: "add_items").count
  end

  private

  def submit(device, *commands)
    SyncCommandProcessor.new(device: device).call(commands)
  end
end
