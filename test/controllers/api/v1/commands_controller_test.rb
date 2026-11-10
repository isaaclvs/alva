require "test_helper"
require "support/sync_command_builders"

class Api::V1::CommandsControllerTest < ActionDispatch::IntegrationTest
  include SyncCommandBuilders

  setup do
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha-da-casa", cost: 4).to_s
    @home = households(:home)
    @ana, @ana_token = AndroidDevice.issue(household: @home, name: "Celular da Ana", scope: AndroidDevice::SYNC)
    @isaac, @isaac_token = AndroidDevice.issue(household: @home, name: "Celular do Isaac", scope: AndroidDevice::SYNC)
  end

  teardown do
    ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest
  end

  test "applies a batch in order and answers the revision and one result per command" do
    item = uuid
    add = add_items_command([ item, "Tapioca" ])
    mark = set_item_state_command(item, "purchased", depends_on: add["id"])

    send_commands(@ana_token, [ add, mark ], observed_revision: @home.sync_revision)

    assert_response :ok
    assert_equal "no-store", response.headers["Cache-Control"]
    assert_equal "application/json", response.media_type
    assert_equal %w[results revision snapshot_required], body.keys.sort
    assert_equal [ add["id"], mark["id"] ], body["results"].map { |result| result["id"] }
    assert_equal %w[applied applied], body["results"].map { |result| result["status"] }
    assert_equal @home.reload.sync_revision, body["revision"]
    assert_equal body["revision"], body["results"].last["revision"]
    assert_equal false, body["snapshot_required"]
    assert_equal "purchased", @home.list_items.find_by!(public_id: item).status
  end

  test "the snapshot after a batch carries the UUIDs the phone generated" do
    item = uuid
    send_commands(@ana_token, [ add_items_command([ item, "Tapioca" ]) ])

    get "/api/v1/snapshot", headers: bearer(@ana_token)

    created = JSON.parse(response.body)["list_items"].find { |entry| entry["public_id"] == item }
    assert_equal [ "Tapioca", "pending", 0 ], created.values_at("name", "status", "lock_version")
  end

  test "two phones adding the same name offline converge on one item and both reach it" do
    ana_item, isaac_item = uuid, uuid
    ana_add = add_items_command([ ana_item, "Tapioca" ])
    isaac_add = add_items_command([ isaac_item, "tapioca" ])
    isaac_mark = set_item_state_command(isaac_item, "purchased", depends_on: isaac_add["id"])

    send_commands(@ana_token, [ ana_add ])
    ana_results = body["results"]
    send_commands(@isaac_token, [ isaac_add, isaac_mark ])
    isaac_results = body["results"]

    assert_equal "applied", ana_results[0]["status"]
    assert_equal %w[noop applied], isaac_results.map { |result| result["status"] }
    assert_equal ana_item, isaac_results[0]["data"]["items"][0]["canonical_public_id"]
    assert_equal isaac_item, isaac_results[0]["data"]["items"][0]["public_id"]
    assert_equal [ 1 ], [ @home.list_items.where(normalized_name: "tapioca").count ]
    assert_equal "purchased", @home.list_items.find_by!(public_id: ana_item).status

    # Ana, who never heard of Isaac's UUID, keeps working with her own.
    send_commands(@ana_token, [ set_item_state_command(ana_item, "pending", lock_version: 1) ])
    assert_equal "applied", body["results"][0]["status"]
    get "/api/v1/snapshot", headers: bearer(@isaac_token)
    assert_equal 1, JSON.parse(response.body)["list_items"].count { |entry| entry["normalized_name"] == "tapioca" }
  end

  test "resending the whole batch after a lost response repeats nothing" do
    item = uuid
    add = add_items_command([ item, "Tapioca" ])
    mark = set_item_state_command(item, "purchased", depends_on: add["id"])
    unmark = set_item_state_command(item, "pending", depends_on: mark["id"])
    batch = [ add, mark, unmark ]

    send_commands(@ana_token, batch)
    first = body
    revision = @home.reload.sync_revision
    send_commands(@ana_token, batch)

    assert_equal %w[duplicate duplicate duplicate], body["results"].map { |result| result["status"] }
    assert_equal %w[applied applied applied], body["results"].map { |result| result["original_status"] }
    assert_equal first["results"].map { |result| result["data"] }, body["results"].map { |result| result["data"] }
    assert_equal revision, body["revision"]
    assert_equal [ "pending", 2 ], @home.list_items.find_by!(public_id: item).values_at(:status, :lock_version)
  end

  test "conflicts are results, ask for a new snapshot and block only what depends on them" do
    milk = list_items(:milk)
    stale = milk.lock_version
    milk.toggle_purchased!
    milk.toggle_purchased!
    blocked_target = set_item_state_command(milk.public_id, "purchased", lock_version: stale)
    follow_up = set_item_state_command(milk.public_id, "pending", depends_on: blocked_target["id"])
    independent = add_items_command([ uuid, "Tapioca" ])

    send_commands(@ana_token, [ blocked_target, follow_up, independent ])

    assert_response :ok
    assert_equal %w[conflict blocked applied], body["results"].map { |result| result["status"] }
    assert_equal "precondition_failed", body["results"][0]["error"]
    assert_equal "dependency_not_applied", body["results"][1]["error"]
    assert_equal true, body["snapshot_required"]
  end

  test "an old request cannot revive a recent purchase through the endpoint" do
    detergent = list_items(:detergent)

    send_commands(@ana_token, [ add_items_command([ uuid, "Detergente" ]) ])

    assert_equal "conflict", body["results"][0]["status"]
    assert_equal true, body["snapshot_required"]
    assert detergent.reload.purchased?
  end

  test "ids in the body never choose the household, and another household's UUID is not found" do
    beach = Household.create!(name: "Praia")
    beach_device, = AndroidDevice.issue(household: beach, name: "Praia", scope: AndroidDevice::SYNC)
    foreign = beach.list_items.create!(name: "Coco")
    mine = uuid

    post "/api/v1/commands", headers: bearer(@ana_token), as: :json,
      params: { household_id: beach.id, device_id: beach_device.id,
                commands: [ add_items_command([ mine, "Tapioca" ]).merge("household_id" => beach.id),
                            set_item_state_command(foreign.public_id, "purchased", lock_version: 0) ] }

    assert_response :ok
    assert_equal [ "applied", "rejected" ], body["results"].map { |result| result["status"] }
    assert_equal "not_found", body["results"][1]["error"]
    assert_equal [ 1, 0 ], [ @home.list_items.where(public_id: mine).count, beach.list_items.where(public_id: mine).count ]
    assert foreign.reload.pending?
    assert_not_includes response.body, beach.public_id
  end

  test "only a sync credential reaches the endpoint" do
    _, widget_token = AndroidDevice.issue(household: @home, name: "Widget")
    command = [ add_items_command([ uuid, "Tapioca" ]) ]

    send_commands(widget_token, command)
    assert_response :forbidden
    assert_equal({ "error" => "forbidden_scope" }, body)

    send_commands("nao-existe", command)
    assert_response :unauthorized

    post "/api/v1/commands", params: { commands: command }, as: :json
    assert_response :unauthorized
    @ana.revoke!
    send_commands(@ana_token, command)
    assert_response :unauthorized

    assert_nil @home.pending_item_named("Tapioca")
    assert_equal 0, SyncCommand.count
  end

  test "an envelope that is not one is rejected as a whole and changes nothing" do
    command = add_items_command([ uuid, "Tapioca" ])
    too_many = Array.new(Api::V1::CommandsController::MAX_COMMANDS + 1) { add_items_command([ uuid, "Item #{SecureRandom.hex(3)}" ]) }

    [ { commands: [] }, { commands: "x" }, { commands: too_many }, {}, { commands: [ command ], observed_revision: -1 },
      { commands: [ command ], observed_revision: "7" } ].each do |payload|
      post "/api/v1/commands", params: payload, headers: bearer(@ana_token), as: :json

      assert_response :unprocessable_content, payload.to_s[0, 60]
      assert_equal({ "error" => "malformed_command" }, body)
    end
    post "/api/v1/commands", params: "{nao é json", headers: bearer(@ana_token).merge("Content-Type" => "application/json")
    assert_response :unprocessable_content
    assert_equal({ "error" => "malformed_command" }, body)
    assert_nil @home.pending_item_named("Tapioca")
  end

  test "a malformed command inside a good envelope fails alone" do
    good = add_items_command([ uuid, "Tapioca" ])

    send_commands(@ana_token, [ { "id" => "x", "type" => "add_items" }, good, { "id" => uuid, "type" => "desconhecido" } ])

    assert_response :ok
    assert_equal [ [ "rejected", "malformed_command" ], [ "applied", nil ], [ "rejected", "unsupported_command" ] ],
      body["results"].map { |result| result.values_at("status", "error") }
  end

  test "only POST with the default JSON format exists" do
    get "/api/v1/commands", headers: bearer(@ana_token)
    assert_response :not_found
    post "/api/v1/commands.xml", headers: bearer(@ana_token)
    assert_response :not_found
  end

  test "an unexpected failure is a stable 500 and a resend finishes the job" do
    reported = []
    subscriber = Object.new
    subscriber.define_singleton_method(:report) { |error, **| reported << error }
    Rails.error.subscribe(subscriber)
    command = add_items_command([ uuid, "Tapioca" ])
    ActiveRecord::Base.connection.execute("ALTER TABLE sync_aliases RENAME TO sync_aliases_off")

    begin
      send_commands(@ana_token, [ add_items_command([ uuid, "leite" ]) ]) # needs an alias: the table is gone
      assert_response :internal_server_error
      assert_equal({ "error" => "internal_error" }, body)
      assert_not_includes response.body, "sync_aliases"
    ensure
      ActiveRecord::Base.connection.execute("ALTER TABLE sync_aliases_off RENAME TO sync_aliases")
      Rails.error.unsubscribe(subscriber)
    end

    send_commands(@ana_token, [ command ])
    assert_equal "applied", body["results"][0]["status"]
    assert_equal 1, reported.size
  end

  private

  def send_commands(token, commands, **envelope)
    post "/api/v1/commands", params: { commands: }.merge(envelope), headers: bearer(token), as: :json
  end

  def bearer(token) = { "Authorization" => "Bearer #{token}" }

  def body = JSON.parse(response.body)
end
