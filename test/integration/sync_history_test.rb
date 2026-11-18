require "test_helper"

class SyncHistoryTest < ActionDispatch::IntegrationTest
  setup do
    @old_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("teste", cost: 4).to_s
    _, @token = AndroidDevice.issue(household: households(:home), name: "Native", scope: AndroidDevice::SYNC)
    @headers = { "Authorization" => "Bearer #{@token}" }
    @household = households(:home)
  end

  teardown { ENV["ALVA_PASSWORD_DIGEST"] = @old_digest }

  def finished_trip(at, items)
    trip = @household.shopping_trips.create!(finished_at: at)
    items.each do |name, category|
      @household.list_items.create!(name:, category:, status: :purchased, purchased_at: at, shopping_trip: trip)
    end
    trip
  end

  def get_history(params = {})
    get "/api/v1/history", params:, headers: @headers
  end

  test "returns the newest month of trips, with their items, references by public_id" do
    trip = finished_trip(Time.zone.local(2026, 11, 12, 18, 30), { "Banana" => categories(:produce), "Sabão" => categories(:other) })

    get_history

    assert_response :success
    body = response.parsed_body
    assert_equal "2026-11", body["month"]
    assert_equal [ { "key" => "2026-11", "trip_count" => 1 } ], body["months"]
    assert_nil body["older_month"]
    assert_nil body["newer_month"]
    entry = body["trips"].sole
    assert_equal trip.public_id, entry["public_id"]
    assert_equal "2026-11-12T21:30:00.000000Z", entry["finished_at"].then { Time.zone.parse(_1).utc.iso8601(6) }
    assert_equal 2, entry["items_count"]
    # Category position, then name without accents: Hortifruti before Outros.
    assert_equal %w[Banana Sabão], entry["items"].map { |item| item["name"] }
    assert_equal [ categories(:produce).public_id, categories(:other).public_id ], entry["items"].map { |item| item["category_public_id"] }
    entry["items"].each { |item| assert_match SyncCommand::UUID, item["public_id"] }
  end

  test "pages by month and names the months beside it" do
    finished_trip(Time.zone.local(2026, 9, 20, 10), { "Café" => categories(:other) })
    october = finished_trip(Time.zone.local(2026, 10, 3, 10), { "Arroz" => categories(:other) })
    finished_trip(Time.zone.local(2026, 11, 12, 18), { "Sabão" => categories(:other) })

    get_history(month: "2026-10")

    body = response.parsed_body
    assert_equal "2026-10", body["month"]
    assert_equal %w[2026-11 2026-10 2026-09], body["months"].map { |month| month["key"] }
    assert_equal [ 1, 1, 1 ], body["months"].map { |month| month["trip_count"] }
    assert_equal "2026-09", body["older_month"]
    assert_equal "2026-11", body["newer_month"]
    assert_equal [ october.public_id ], body["trips"].map { |trip| trip["public_id"] }
  end

  test "empty history is an empty page" do
    get_history

    assert_response :success
    assert_equal({ "month" => nil, "months" => [], "older_month" => nil, "newer_month" => nil, "trips" => [] }, response.parsed_body)
  end

  test "a month without trips falls back to the newest one" do
    finished_trip(Time.zone.local(2026, 11, 12, 18), { "Sabão" => categories(:other) })

    get_history(month: "2020-01")

    assert_equal "2026-11", response.parsed_body["month"]
  end

  test "writes nothing: the revision does not move" do
    finished_trip(Time.zone.local(2026, 11, 12, 18), { "Sabão" => categories(:other) })
    revision = @household.reload.sync_revision

    get_history

    assert_equal revision, @household.reload.sync_revision
    assert_equal 0, SyncCommand.count
  end

  test "requires the sync credential from the bearer, never from the request" do
    finished_trip(Time.zone.local(2026, 11, 12, 18), { "Sabão" => categories(:other) })
    other = Household.create!(name: "Outra casa")
    other_trip = other.shopping_trips.create!(finished_at: Time.zone.local(2026, 11, 12, 18))
    other.list_items.create!(name: "Cimento", status: :purchased, purchased_at: other_trip.finished_at, shopping_trip: other_trip)

    get "/api/v1/history", params: { household_id: other.id }, headers: @headers
    assert_response :success
    assert_equal [ "Sabão" ], response.parsed_body["trips"].flat_map { |trip| trip["items"].map { |item| item["name"] } }

    get "/api/v1/history"
    assert_response :unauthorized
    assert_equal({ "error" => "unauthorized" }, response.parsed_body)

    _, add_only = AndroidDevice.issue(household: @household, name: "Widget", scope: AndroidDevice::ADD_ONLY)
    get "/api/v1/history", headers: { "Authorization" => "Bearer #{add_only}" }
    assert_response :forbidden
    assert_equal({ "error" => "forbidden_scope" }, response.parsed_body)
  end

  test "has no format suffix and no other verb" do
    get "/api/v1/history.json", headers: @headers
    assert_response :not_found

    post "/api/v1/history", headers: @headers
    assert_response :not_found
  end

  test "a trip's names are the ones the PWA adds again" do
    trip = finished_trip(Time.zone.local(2026, 11, 12, 18), { "Banana" => categories(:produce), "Sabão" => categories(:other) })

    get_history
    names = response.parsed_body["trips"].sole["items"].map { |item| item["name"] }

    # The existing add_items command takes exactly these names, so no new
    # command is needed to repeat a purchase.
    assert_equal [ "Banana", "Sabão" ], names
    command = { id: SecureRandom.uuid, type: "add_items",
      payload: { items: names.map { |name| { public_id: SecureRandom.uuid, name: } } } }
    post "/api/v1/commands", params: { commands: [ command ] }, as: :json, headers: @headers
    assert_response :success
    assert_equal "applied", response.parsed_body.dig("results", 0, "status")
    assert_equal names.sort, @household.list_items.on_list.where(name: names).order(:name).pluck(:name)
    assert_equal trip.public_id, @household.shopping_trips.sole.public_id
  end
end
