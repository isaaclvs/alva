# Executors for the processor tests. They are real: each writes through the
# Household model (items, revisions, tombstones), inside the transaction the
# processor opens. Business commands from later tasks replace them in
# SyncCommandProcessor::EXECUTORS; the interface is the same.
module SyncCommandExecutors
  Outcome = SyncCommandProcessor::Outcome

  # Adds the names with the same rules as the app. Applied when something
  # landed on the list, noop when every name was already pending.
  ADD_ITEMS = lambda do |command|
    results = command.household.add_item_names(command.payload.fetch("names"))
    items = results.map { |r| { "name" => r.item.name, "outcome" => r.outcome.to_s } }
    results.any? { |r| r.outcome.in?(%i[created revived]) } ? Outcome.applied("items" => items) : Outcome.noop("items" => items)
  end

  # Writes first, then fails: whatever it did must disappear with the rollback.
  # `failures` counts down so the retry can succeed.
  def self.failing_then_adding(failures)
    remaining = failures
    lambda do |command|
      result = ADD_ITEMS.call(command)
      if remaining.positive?
        remaining -= 1
        raise "falha depois do efeito"
      end
      result
    end
  end

  CONFLICT = lambda do |command|
    command.household.add_item_names(command.payload.fetch("names"))
    Outcome.conflict("precondition_failed", "current" => "outro estado")
  end

  REJECT = ->(_command) { Outcome.rejected("invalid_name") }

  # Counts how many times the effect ran, regardless of the ledger.
  def self.counting(counter)
    lambda do |command|
      counter << command.id
      ADD_ITEMS.call(command)
    end
  end
end
