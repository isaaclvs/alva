require "test_helper"

class AndroidDeviceTest < ActiveSupport::TestCase
  setup do
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha-da-casa", cost: 4).to_s
  end

  teardown do
    ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest
  end

  test "legacy issue emits add_only and never promotes to an arbitrary scope" do
    device, secret = AndroidDevice.issue(household: households(:home), name: "Widget")

    assert_equal AndroidDevice::ADD_ONLY, device.scope
    assert_equal AndroidDevice.digest(secret), device.token_digest
    assert_not_equal secret, device.token_digest
    assert_equal HouseholdAccess.token, device.access_digest
    assert_not device.sync?

    assert_raises(ActiveRecord::RecordInvalid) do
      AndroidDevice.issue(household: households(:home), name: "Intruso", scope: "admin")
    end
  end

  test "issue creates distinct sync credentials bound to the current password" do
    first, first_secret = AndroidDevice.issue(household: households(:home), name: "Celular A", scope: AndroidDevice::SYNC)
    second, second_secret = AndroidDevice.issue(household: households(:home), name: "Celular B", scope: AndroidDevice::SYNC)

    assert first.sync?
    assert_not_equal first_secret, second_secret
    assert_not_equal first.token_digest, second.token_digest
    assert_equal AndroidDevice::SYNC, first.scope
    assert_equal first, AndroidDevice.authenticate(first_secret)
  end

  test "authenticate requires the current password version" do
    device, token = AndroidDevice.issue(household: households(:home), name: "Celular", scope: AndroidDevice::SYNC)

    assert_equal device, AndroidDevice.authenticate(token)

    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("outra-senha", cost: 4).to_s

    assert_nil AndroidDevice.authenticate(token)
  end

  test "authenticate refuses every credential when no password is configured" do
    _, token = AndroidDevice.issue(household: households(:home), name: "Celular", scope: AndroidDevice::SYNC)
    ENV.delete("ALVA_PASSWORD_DIGEST")

    assert_nil AndroidDevice.authenticate(token)
    assert_nil AndroidDevice.authenticate("")
    assert_nil AndroidDevice.authenticate(nil)
  end

  test "authenticate rejects unknown and revoked credentials" do
    device, token = AndroidDevice.issue(household: households(:home), name: "Celular")

    assert_nil AndroidDevice.authenticate("nao-existe")
    assert_nil AndroidDevice.authenticate(" ")

    device.revoke!
    assert_nil AndroidDevice.authenticate(token)
  end

  test "revoke! is idempotent and keeps the first timestamp" do
    device, token = AndroidDevice.issue(household: households(:home), name: "Celular")

    device.revoke!
    revoked_at = device.revoked_at
    assert_not_nil revoked_at

    travel 1.minute do
      device.revoke!
    end

    assert_equal revoked_at, device.reload.revoked_at
    assert_nil AndroidDevice.authenticate(token)
  end

  test "rotation blocks both scopes and a new emission works with the new digest" do
    _, add_only_token = AndroidDevice.issue(household: households(:home), name: "Widget")
    _, sync_token = AndroidDevice.issue(household: households(:home), name: "Celular", scope: AndroidDevice::SYNC)

    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("nova-senha", cost: 4).to_s

    assert_nil AndroidDevice.authenticate(add_only_token)
    assert_nil AndroidDevice.authenticate(sync_token)

    renewed, renewed_token = AndroidDevice.issue(household: households(:home), name: "Celular", scope: AndroidDevice::SYNC)
    assert_equal renewed, AndroidDevice.authenticate(renewed_token)
  end

  test "revoking one device does not affect another" do
    revoked, revoked_token = AndroidDevice.issue(household: households(:home), name: "Antigo")
    keeper, keeper_token = AndroidDevice.issue(household: households(:home), name: "Atual")

    revoked.revoke!

    assert_nil AndroidDevice.authenticate(revoked_token)
    assert_equal keeper, AndroidDevice.authenticate(keeper_token)
  end

  test "the database defaults the scope to add_only and rejects unknown values" do
    now = Time.current.to_fs(:db)
    id = AndroidDevice.connection.insert(<<~SQL.squish)
      INSERT INTO android_devices (household_id, name, token_digest, access_digest, lineage_id, created_at, updated_at)
      VALUES (#{households(:home).id}, 'Legado', '#{SecureRandom.hex(32)}', '#{HouseholdAccess.token}', '#{SecureRandom.uuid}', '#{now}', '#{now}')
    SQL

    device = AndroidDevice.find(id)
    assert_equal AndroidDevice::ADD_ONLY, device.scope

    assert_raises(ActiveRecord::StatementInvalid) { device.update_column(:scope, "admin") }
    assert_raises(ActiveRecord::StatementInvalid) { device.update_column(:scope, nil) }
    assert_equal AndroidDevice::ADD_ONLY, device.reload.scope
  end

  test "every credential has a lineage and distinct phones do not share one" do
    first, = AndroidDevice.issue(household: households(:home), name: "A", scope: AndroidDevice::SYNC)
    second, = AndroidDevice.issue(household: households(:home), name: "B", scope: AndroidDevice::SYNC)
    widget, = AndroidDevice.issue(household: households(:home), name: "Widget")

    assert_match(/\A\h{8}-\h{4}-\h{4}-\h{4}-\h{12}\z/, first.lineage_id)
    assert_equal 3, [ first, second, widget ].map(&:lineage_id).uniq.size
  end

  test "a successor continues the predecessor lineage with its own identity and secret" do
    old, old_secret = AndroidDevice.issue(household: households(:home), name: "Celular", scope: AndroidDevice::SYNC)
    old.revoke!

    new, new_secret = AndroidDevice.issue(household: households(:home), name: "Celular", scope: AndroidDevice::SYNC,
      predecessor: AndroidDevice.predecessor_for(households(:home), old_secret))

    assert_equal old.lineage_id, new.lineage_id
    assert_not_equal old.id, new.id
    assert_not_equal old_secret, new_secret
    assert_equal new, AndroidDevice.authenticate(new_secret)
  end

  test "predecessor_for proves possession of a past sync secret of the same household only" do
    home = households(:home)
    old, old_secret = AndroidDevice.issue(household: home, name: "Celular", scope: AndroidDevice::SYNC)
    old.revoke!
    _, widget_secret = AndroidDevice.issue(household: home, name: "Widget")
    other_house = Household.create!(name: "Praia")
    _, other_secret = AndroidDevice.issue(household: other_house, name: "Outro", scope: AndroidDevice::SYNC)

    assert_equal old, AndroidDevice.predecessor_for(home, old_secret), "a revoked secret still proves the phone"
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("outra-senha", cost: 4).to_s
    assert_equal old, AndroidDevice.predecessor_for(home, old_secret), "so does one outlived by a password rotation"

    assert_nil AndroidDevice.predecessor_for(home, widget_secret)
    assert_nil AndroidDevice.predecessor_for(home, other_secret)
    assert_nil AndroidDevice.predecessor_for(home, "nao-existe")
    assert_nil AndroidDevice.predecessor_for(home, "")
    assert_nil AndroidDevice.predecessor_for(home, nil)
    assert_nil AndroidDevice.predecessor_for(home, { "a" => "b" })
  end
end
