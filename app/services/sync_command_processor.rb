# Runs a batch of sync commands for one authenticated device, at most once.
#
# The caller has already authenticated the credential (Api::V1::BaseController
# yields `device` with the `sync` scope): the household and the identity come
# from it and from nothing in the payload. Business commands are not defined
# here; each registers an executor under its `type` (see EXECUTORS) in the task
# that introduces it.
#
# An executor is anything that responds to `call(command)` with a
# SyncCommandProcessor::Outcome. It runs inside the transaction that holds the
# household's lock, so its effect and the ledger row (SyncCommand) commit or
# roll back together. It must not rescue database errors or write outside the
# transaction. An executor that returns :conflict or :rejected must not have
# needed its writes: they are rolled back anyway.
class SyncCommandProcessor
  # type => executor. Each business command registers here in the task that
  # introduces it.
  EXECUTORS = {
    "add_items" => SyncCommands::AddItems,
    "set_item_state" => SyncCommands::SetItemState,
    "undo_item_state" => SyncCommands::UndoItemState,
    "correct_item" => SyncCommands::CorrectItem,
    "remove_item" => SyncCommands::RemoveItem,
    "undo_remove" => SyncCommands::UndoRemove
  }.freeze

  # `dependency` is the ledger row (SyncCommand) of the command named by
  # `depends_on`, set only once the processor has checked that it was applied
  # and belongs to the same lineage; nil otherwise. It is read-only context for
  # the executor — what the earlier command produced (ids, versions) — and the
  # content of this command is never rewritten with it.
  Command = Data.define(:id, :type, :depends_on, :observed_revision, :preconditions, :payload, :household,
    :dependency)

  # applied: the effect happened. noop: the state was already the desired one.
  # Both consume the command. conflict: a precondition failed. rejected: the
  # command is invalid for the current state. Neither consumes the command.
  Outcome = Data.define(:status, :data, :error) do
    def self.applied(data = {}) = new(status: :applied, data:, error: nil)
    def self.noop(data = {}) = new(status: :noop, data:, error: nil)
    def self.conflict(error = "precondition_failed", data = {}) = new(status: :conflict, data:, error:)
    def self.rejected(error, data = {}) = new(status: :rejected, data:, error:)

    def initialize(status:, data:, error:)
      raise ArgumentError, "unknown outcome #{status.inspect}" unless %i[applied noop conflict rejected].include?(status)
      raise ArgumentError, "data must be a Hash" unless data.is_a?(Hash)

      super
    end

    def consumed? = %i[applied noop].include?(status)
  end

  TYPE = /\A[a-z][a-z_]{0,63}\z/

  def initialize(device:, executors: EXECUTORS)
    raise ArgumentError, "a sync credential is required" unless device&.sync?

    @device = device
    @household = device.household
    @executors = executors
  end

  # Results come back in the order of the commands. `observed_revision` is the
  # envelope's default for commands that carry no revision of their own.
  def call(commands, observed_revision: nil)
    commands.map { |raw| process(raw, observed_revision) }
  end

  private

  attr_reader :device, :household, :executors

  def process(raw, default_revision)
    command, digest = parse(raw, default_revision)
    return failure(raw_id(raw), :rejected, "malformed_command") unless command

    result = nil
    # The batch spans the transaction on purpose: the list is broadcast once,
    # after the commit, and only if something committed. A command that rolls
    # back never reaches its after_commit callbacks, so it announces nothing.
    household.batch_list_broadcasts do
      # requires_new makes this a real transaction (or savepoint) even when the
      # caller is already inside one. The Rollback is raised here and not inside
      # with_list_lock: a Rollback in a joined transaction is swallowed there and
      # undoes nothing.
      ActiveRecord::Base.transaction(requires_new: true) do
        result, commit = household.with_list_lock { SyncCommand.uncached { run(command, digest) } }
        raise ActiveRecord::Rollback unless commit
      end
    end
    result
  end

  # Under the household lock: the ledger is read and written by one command at a
  # time, so two submissions of the same UUID cannot both apply. The unique
  # index (household, command id) backs this up at the database.
  def run(command, digest)
    recorded = SyncCommand.find_by(household_id: household.id, command_id: command.id)
    return [ replay(command, recorded, digest), false ] if recorded

    executor = executors[command.type]
    return [ failure(command.id, :rejected, "unsupported_command"), false ] unless executor
    dependency = dependency_for(command)
    return [ blocked(command), false ] if command.depends_on && !dependency

    outcome = executor.call(command.with(dependency:))
    return [ failure(command.id, outcome.status, outcome.error, outcome.data), false ] unless outcome.consumed?

    data = JSON.parse(JSON.generate(outcome.data))
    revision = current_revision
    SyncCommand.create!(household:, command_id: command.id, android_device: device, lineage_id: device.lineage_id,
      command_type: command.type, depends_on: command.depends_on, content_digest: digest,
      status: outcome.status.to_s, revision:, data:)
    [ { id: command.id, status: outcome.status.to_s, revision:, data: }, true ]
  end

  # The original result, whichever credential of the lineage asks again. Another
  # lineage, or other content under a used UUID, learns nothing about it.
  def replay(command, recorded, digest)
    return failure(command.id, :rejected, "idempotency_conflict") unless
      recorded.lineage_id == device.lineage_id && recorded.content_digest == digest

    { id: command.id, status: "duplicate", original_status: recorded.status, revision: recorded.revision,
      data: recorded.data }
  end

  # A command depends on one the same lineage already got applied (or found
  # satisfied). One that never arrived, conflicted or was rejected has no row,
  # so what depends on it waits; the retry of both resolves it.
  def dependency_for(command)
    return unless command.depends_on

    SyncCommand.find_by(household_id: household.id, command_id: command.depends_on, lineage_id: device.lineage_id)
  end

  def blocked(command)
    { id: command.id, status: "blocked", error: "dependency_not_applied", depends_on: command.depends_on }
  end

  def failure(id, status, error, data = nil)
    { id:, status: status.to_s, error:, **(data.present? ? { data: } : {}) }
  end

  def current_revision
    Household.uncached { Household.where(id: household.id).pick(:sync_revision) }
  end

  # [command, content digest], or nil when the command is not well formed.
  # Fields beyond the contract are ignored: neither hashed nor passed on. The
  # digest covers the revision the command itself carries; the envelope default
  # only reaches the executor, so resending in another envelope is still a
  # replay.
  def parse(raw, default_revision)
    raw = raw.to_unsafe_h if raw.respond_to?(:to_unsafe_h)
    return unless raw.is_a?(Hash)

    raw = raw.deep_stringify_keys
    id = uuid(raw["id"])
    depends_on = raw["depends_on"].nil? ? nil : uuid(raw["depends_on"])
    own_revision = raw["observed_revision"]
    preconditions = raw["preconditions"].nil? ? {} : raw["preconditions"]
    payload = raw["payload"].nil? ? {} : raw["payload"]
    return unless id && raw["type"].is_a?(String) && TYPE.match?(raw["type"])
    return if !raw["depends_on"].nil? && (depends_on.nil? || depends_on == id)
    return unless [ preconditions, payload ].all?(Hash)
    return unless own_revision.nil? || (own_revision.is_a?(Integer) && own_revision >= 0)

    digest = SyncCommand.content_digest(type: raw["type"], depends_on:, observed_revision: own_revision,
      preconditions:, payload:)
    [ Command.new(id:, type: raw["type"], depends_on:, observed_revision: own_revision || default_revision,
      preconditions:, payload:, household:, dependency: nil), digest ]
  end

  def uuid(value)
    value.downcase if value.is_a?(String) && SyncCommand::UUID.match?(value)
  end

  def raw_id(raw)
    raw = raw.to_unsafe_h if raw.respond_to?(:to_unsafe_h)
    value = raw.is_a?(Hash) ? (raw["id"] || raw[:id]) : nil
    value if value.is_a?(String)
  end
end
