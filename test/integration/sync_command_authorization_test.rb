require "test_helper"
require "support/sync_command_executors"

module Api
  module V1
    # Test-only probe: there is no commands endpoint yet. It is the smallest
    # controller that wires the real authentication base to the processor the
    # way the endpoint of the first business command will.
    class CommandsProbeController < BaseController
      EXECUTORS = { "add_items" => SyncCommandExecutors::ADD_ITEMS }.freeze

      def create
        results = SyncCommandProcessor.new(device:, executors: EXECUTORS).call(request.request_parameters.fetch("commands"))
        render json: { revision: current_household.reload.sync_revision, results: }
      end
    end
  end
end

# Authorization established by the real v1 base and the real pairing route; the
# household and the identity of a command come only from the credential.
class SyncCommandAuthorizationTest < ActionDispatch::IntegrationTest
  setup do
    @password = "senha da casa"
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    @previous_host = ENV["ALVA_PUBLIC_HOST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create(@password, cost: 4).to_s
    ENV["ALVA_PUBLIC_HOST"] = "alva.example"
    ApiPairing::RATE_LIMIT_STORE.clear
    @home = Household.first
  end

  teardown do
    ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest
    ENV["ALVA_PUBLIC_HOST"] = @previous_host
    ApiPairing::RATE_LIMIT_STORE.clear
  end

  test "a paired phone applies a command once and gets the original result when it resends" do
    with_sync_routes do
      token = pair("Ana")["token"]
      id = SecureRandom.uuid

      send_commands(token, [ add_items(id, "Tapioca") ])
      assert_response :success
      assert_equal "no-store", response.headers["Cache-Control"]
      first = JSON.parse(response.body)
      assert_equal "applied", first["results"].first["status"]
      assert_equal @home.reload.sync_revision, first["revision"]

      send_commands(token, [ add_items(id, "Tapioca") ])
      replay = JSON.parse(response.body)["results"].first
      assert_equal "duplicate", replay["status"]
      assert_equal "applied", replay["original_status"]
      assert_equal first["results"].first["data"], replay["data"]
      assert_equal 1, @home.list_items.where(name: "Tapioca").count
      assert_not_includes response.body, token
    end
  end

  test "only a sync credential reaches the processor and nothing changes otherwise" do
    with_sync_routes do
      _, widget_token = AndroidDevice.issue(household: @home, name: "Widget")

      send_commands(widget_token, [ add_items(SecureRandom.uuid, "Tapioca") ])
      assert_response :forbidden
      assert_equal "forbidden_scope", JSON.parse(response.body)["error"]

      send_commands("nao-existe", [ add_items(SecureRandom.uuid, "Tapioca") ])
      assert_response :unauthorized

      post "/api/v1/commands", params: { commands: [ add_items(SecureRandom.uuid, "Tapioca") ] }, as: :json
      assert_response :unauthorized

      assert_equal 0, SyncCommand.count
      assert_equal 0, @home.list_items.where(name: "Tapioca").count
    end
  end

  test "a revoked or rotated credential cannot run a command" do
    with_sync_routes do
      revoked = pair("Ana")
      AndroidDevice.find(revoked["device_id"]).revoke!
      rotated = pair("Isaac")["token"]
      ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha nova", cost: 4).to_s

      [ revoked["token"], rotated ].each do |token|
        send_commands(token, [ add_items(SecureRandom.uuid, "Tapioca") ])
        assert_response :unauthorized
      end
      assert_equal 0, SyncCommand.count
    end
  end

  test "ids sent by the client never select the household or the identity" do
    with_sync_routes do
      beach = Household.create!(name: "Praia")
      beach_device, = AndroidDevice.issue(household: beach, name: "Praia", scope: AndroidDevice::SYNC)
      token = pair("Ana")["token"]
      id = SecureRandom.uuid
      body = { household_id: beach.id, device_id: beach_device.id, commands: [ add_items(id, "Tapioca").merge("household_id" => beach.id) ] }

      post "/api/v1/commands", params: body, headers: bearer(token), as: :json

      assert_response :success
      assert_equal 1, @home.list_items.where(name: "Tapioca").count
      assert_equal 0, beach.list_items.count
      record = SyncCommand.find_by!(command_id: id)
      assert_equal @home, record.household
      assert_not_equal beach_device.id, record.android_device_id
    end
  end

  test "authorizing the same phone again resolves its earlier command instead of repeating it" do
    with_sync_routes do
      first = pair("Ana")
      id = SecureRandom.uuid
      dependent = SecureRandom.uuid

      send_commands(first["token"], [ add_items(id, "Tapioca") ]) # the response never reaches the phone
      AndroidDevice.find(first["device_id"]).revoke!
      ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha nova", cost: 4).to_s
      @password = "senha nova"
      second = pair("Ana", previous_token: first["token"])

      send_commands(first["token"], [ add_items(id, "Tapioca") ])
      assert_response :unauthorized

      send_commands(second["token"], [ add_items(id, "Tapioca"), add_items(dependent, "Polvilho").merge("depends_on" => id) ])
      results = JSON.parse(response.body)["results"]
      assert_equal %w[duplicate applied], results.map { |r| r["status"] }
      assert_equal 1, @home.list_items.where(name: "Tapioca").count
      assert_equal 1, SyncCommand.where(command_id: id).count
      assert_equal first["device_id"], SyncCommand.find_by!(command_id: id).android_device_id
    end
  end

  test "another phone cannot appropriate a command, with a fresh pairing or with a guessed previous secret" do
    with_sync_routes do
      ana = pair("Ana")
      id = SecureRandom.uuid
      send_commands(ana["token"], [ add_items(id, "Tapioca") ])

      isaac = pair("Isaac")
      send_commands(isaac["token"], [ add_items(id, "Tapioca") ])
      assert_equal({ "id" => id, "status" => "rejected", "error" => "idempotency_conflict" }, JSON.parse(response.body)["results"].first)

      post "/api/v1/devices", params: { password: @password, name: "Isaac", previous_token: "chute", device_id: ana["device_id"] }
      assert_response :unprocessable_content

      assert_equal 1, SyncCommand.where(command_id: id).count
      assert_equal ana["device_id"], SyncCommand.find_by!(command_id: id).android_device_id
      assert_equal 1, @home.list_items.where(name: "Tapioca").count
    end
  end

  private

  def with_sync_routes
    with_routing do |set|
      set.draw do
        post "/api/v1/devices", to: "api/v1/devices#create"
        post "/api/v1/commands", to: "api/v1/commands_probe#create"
      end
      yield
    end
  end

  def pair(name, previous_token: nil)
    params = { password: @password, name: name }
    params[:previous_token] = previous_token if previous_token
    post "/api/v1/devices", params: params
    assert_response :created
    JSON.parse(response.body)
  end

  def send_commands(token, commands)
    post "/api/v1/commands", params: { commands: }, headers: bearer(token), as: :json
  end

  def bearer(token) = { "Authorization" => "Bearer #{token}" }

  def add_items(id, name) = { "id" => id, "type" => "add_items", "payload" => { "names" => [ name ] } }
end
