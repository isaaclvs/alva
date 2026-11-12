require "test_helper"

module Api
  module V1
    class MissingRecordProbeController < BaseController
      def show
        raise ActiveRecord::RecordNotFound
      end
    end
  end
end

class Api::V1::SnapshotsControllerTest < ActionDispatch::IntegrationTest
  setup do
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha-da-casa", cost: 4).to_s
    @home = households(:home)
    @sync_device, @sync_token = AndroidDevice.issue(household: @home, name: "Celular da Ana", scope: AndroidDevice::SYNC)
    @beach = Household.create!(name: "Praia")
  end

  teardown do
    ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest
  end

  test "a sync credential receives the versioned snapshot of its own household" do
    get "/api/v1/snapshot", headers: bearer(@sync_token)

    assert_response :ok
    assert_equal "no-store", response.headers["Cache-Control"]
    assert_equal "application/json", response.media_type
    assert_equal %w[archived_items buy_again catalog_items categories dictionary_revision habitual_items household
      list_items revision schema_version shopping_trips tombstones], body.keys.sort
    assert_equal 1, body["schema_version"]
    assert_equal @home.sync_revision, body["revision"]
    assert_equal({ "public_id" => @home.public_id, "name" => "Casa" }, body["household"])
    assert_equal %w[Detergente Leite], body["list_items"].map { |item| item["name"] }.sort
    assert_equal %w[Hortifruti Laticínios Outros], body["categories"].map { |category| category["name"] }
    assert_equal %w[Banana Leite Maçã], body["catalog_items"].map { |entry| entry["name"] }.sort
  end

  test "the document is compressed for clients that accept gzip" do
    get "/api/v1/snapshot", headers: bearer(@sync_token).merge("Accept-Encoding" => "gzip")

    assert_response :ok
    assert_equal "gzip", response.headers["Content-Encoding"]
    document = JSON.parse(Zlib.gunzip(response.body))
    assert_equal @home.public_id, document["household"]["public_id"]
  end

  test "unexpected failures return a stable JSON error and are reported without exposing internals" do
    reported = []
    subscriber = Object.new
    subscriber.define_singleton_method(:report) { |error, **| reported << error }
    Rails.error.subscribe(subscriber)
    # A real database error; the fixture transaction restores the table.
    ActiveRecord::Base.connection.execute("DROP TABLE sync_tombstones")

    get "/api/v1/snapshot", headers: bearer(@sync_token)

    assert_response :internal_server_error
    assert_equal "application/json", response.media_type
    assert_equal "no-store", response.headers["Cache-Control"]
    assert_equal({ "error" => "internal_error" }, body)
    assert_not_includes response.body, "sync_tombstones"
    assert_equal 1, reported.size
    assert_instance_of ActiveRecord::StatementInvalid, reported.first
  ensure
    Rails.error.unsubscribe(subscriber) if subscriber
  end

  test "framework HTTP errors retain their status instead of becoming internal errors" do
    with_routing do |set|
      set.draw { get "/api/v1/snapshot", to: "api/v1/missing_record_probe#show" }
      get "/api/v1/snapshot", headers: bearer(@sync_token)
      assert_response :not_found
    end
  end

  test "reading changes nothing" do
    @home.add_item("Arroz")
    before = [ @home.reload.sync_revision, ListItem.count, SyncTombstone.count, AndroidDevice.pluck(:id, :revoked_at) ]

    2.times { get "/api/v1/snapshot", headers: bearer(@sync_token) }

    assert_response :ok
    assert_equal before, [ @home.reload.sync_revision, ListItem.count, SyncTombstone.count, AndroidDevice.pluck(:id, :revoked_at) ]
  end

  test "the same revision always yields the same document" do
    @home.add_item("Arroz")

    get "/api/v1/snapshot", headers: bearer(@sync_token)
    first = response.body
    get "/api/v1/snapshot", headers: bearer(@sync_token)

    assert_equal first, response.body
  end

  test "only the credential's household is served, whatever the request says" do
    leak = seed_other_household(@beach)
    removed = @home.list_items.create!(name: "Tampa de casa")
    @home.remove_item(removed)

    get "/api/v1/snapshot", params: { household_id: @beach.id, household: @beach.public_id, scope: "sync" },
      headers: bearer(@sync_token)

    assert_response :ok
    assert_equal @home.public_id, body["household"]["public_id"]
    leak.each { |value| assert_not_includes response.body, value }
    assert_not_includes body["list_items"].map { |item| item["name"] }, "Pão da praia"
    assert_equal [ [ "list_item", removed.public_id ] ], body["tombstones"].map { |t| t.values_at("record_type", "public_id") }
  end

  test "a device of the other household receives that household instead" do
    seed_other_household(@beach)
    _, beach_token = AndroidDevice.issue(household: @beach, name: "Celular da praia", scope: AndroidDevice::SYNC)

    get "/api/v1/snapshot", headers: bearer(beach_token)

    assert_response :ok
    assert_equal @beach.public_id, body["household"]["public_id"]
    assert_equal [ "Pão da praia" ], body["list_items"].map { |item| item["name"] }
    assert_not_includes body["list_items"].map { |item| item["name"] }, "Leite"
  end

  test "no credential, secret or digest is serialized" do
    @home.add_item("Arroz")
    other_device, other_token = AndroidDevice.issue(household: @home, name: "Celular do Isaac", scope: AndroidDevice::SYNC)

    get "/api/v1/snapshot", headers: bearer(@sync_token)

    secrets = [ @sync_token, other_token, @sync_device.token_digest, other_device.token_digest,
      @sync_device.access_digest, ENV["ALVA_PASSWORD_DIGEST"], "token_digest", "access_digest", "Celular da Ana",
      "Celular do Isaac", "password", "secret" ]
    secrets.each { |secret| assert_not_includes response.body, secret }
  end

  test "missing, malformed, unknown, revoked and rotated credentials are unauthorized" do
    [
      {},
      { "Authorization" => "Bearer " },
      { "Authorization" => "bearer #{@sync_token}" },
      { "Authorization" => "Bearer  #{@sync_token}" },
      { "Authorization" => "Basic #{@sync_token}" },
      { "Authorization" => "Bearer nao-existe" }
    ].each do |headers|
      get "/api/v1/snapshot", headers: headers
      assert_response :unauthorized, headers.inspect
      assert_equal({ "error" => "unauthorized" }, body)
      assert_equal "no-store", response.headers["Cache-Control"]
    end

    @sync_device.revoke!
    get "/api/v1/snapshot", headers: bearer(@sync_token)
    assert_response :unauthorized

    _, rotated_token = AndroidDevice.issue(household: @home, name: "Antigo", scope: AndroidDevice::SYNC)
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("outra-senha", cost: 4).to_s
    get "/api/v1/snapshot", headers: bearer(rotated_token)
    assert_response :unauthorized
    assert_equal({ "error" => "unauthorized" }, body)
  end

  test "the widget's add_only credential is forbidden" do
    _, add_only_token = AndroidDevice.issue(household: @home, name: "Widget")

    get "/api/v1/snapshot", headers: bearer(add_only_token)

    assert_response :forbidden
    assert_equal({ "error" => "forbidden_scope" }, body)
    assert_equal "no-store", response.headers["Cache-Control"]
    assert_not_includes response.body, "Leite"
  end

  test "a PWA cookie does not authorize a snapshot" do
    post login_path, params: { password: "senha-da-casa" }
    assert_response :redirect

    get "/api/v1/snapshot"

    assert_response :unauthorized
    assert_not_includes response.body, "Leite"
  end

  test "without a configured household password every credential is refused" do
    ENV["ALVA_PASSWORD_DIGEST"] = nil

    get "/api/v1/snapshot", headers: bearer(@sync_token)

    assert_response :unauthorized
    assert_equal({ "error" => "unauthorized" }, body)
  end

  test "only GET with the default JSON format exists" do
    get "/api/v1/snapshot.xml", headers: bearer(@sync_token)
    assert_response :not_found
    post "/api/v1/snapshot", headers: bearer(@sync_token)
    assert_response :not_found
  end

  test "buy_again matches the suggestions the PWA renders, in the same order" do
    trip = @home.shopping_trips.create!(finished_at: 3.days.ago)
    other = @home.shopping_trips.create!(finished_at: 2.days.ago)
    [ [ "Café", trip ], [ "Café", other ], [ "Arroz", trip ], [ "Feijão", other ] ].each_with_index do |(name, t), index|
      @home.list_items.create!(name:, status: :purchased, purchased_at: t.finished_at + index.minutes, shopping_trip: t)
    end

    get "/api/v1/snapshot", headers: bearer(@sync_token)
    names = body["buy_again"].map { _1["name"] }
    assert_equal %w[Café Feijão Arroz], names

    post login_path, params: { password: "senha-da-casa" }
    get root_path
    assert_equal names, css_select("#buy_again button").map { _1.text.squish.delete_prefix("+ ") }
  end

  test "reading costs the same number of queries however large the household is" do
    # One archived item already, so the "buy again" suggestions load their category in both reads.
    trip = @home.shopping_trips.create!(finished_at: 1.day.ago)
    @home.list_items.create!(name: "Histórico", status: :purchased, purchased_at: 3.days.ago, shopping_trip: trip,
      category: categories(:dairy))
    small = count_queries { get "/api/v1/snapshot", headers: bearer(@sync_token) }
    assert_equal [ "Histórico" ], body["buy_again"].map { _1["name"] }

    30.times do |index|
      @home.list_items.create!(name: "Item #{index}", category: categories(:dairy))
      @home.list_items.create!(name: "Arquivado #{index}", status: :purchased, purchased_at: 2.days.ago, shopping_trip: trip)
      @home.habitual_items.create!(name: "Habitual #{index}")
    end
    large = count_queries { get "/api/v1/snapshot", headers: bearer(@sync_token) }

    assert_equal [ 32, 31, 30 ], body.values_at("list_items", "archived_items", "habitual_items").map(&:size)
    assert_equal small, large
  end

  private

  def bearer(token)
    { "Authorization" => "Bearer #{token}" }
  end

  def body
    JSON.parse(response.body)
  end

  def count_queries
    queries = 0
    counter = ->(*, payload) { queries += 1 unless payload[:name] == "SCHEMA" || payload[:cached] }
    # Broadcasts of the writes above may have cached the very same ranking query.
    ActiveRecord::Base.connection_pool.clear_query_cache
    ActiveSupport::Notifications.subscribed(counter, "sql.active_record") { yield }
    queries
  end

  # Data another household owns, which must never show up for the home. Returns
  # values (public ids, names) whose presence in a response would be a leak.
  def seed_other_household(household)
    item = household.list_items.create!(name: "Pão da praia")
    archived = household.list_items.create!(name: "Sal da praia", status: :purchased, purchased_at: 1.day.ago)
    trip = household.finish_shopping!
    habitual = household.add_habitual_item("Protetor da praia")
    household.remove_habitual_item(household.add_habitual_item("Areia da praia"))
    removed = household.list_items.create!(name: "Bola da praia")
    household.remove_item(removed)

    [ household.public_id, item.public_id, archived.public_id, trip.public_id, habitual.public_id, removed.public_id,
      "Pão da praia", "Sal da praia", "Protetor da praia", "Areia da praia", "Bola da praia", "Praia" ]
  end
end
