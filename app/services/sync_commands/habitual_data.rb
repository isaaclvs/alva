module SyncCommands
  # How the habitual commands report a habitual item, and the UUID checks they
  # share. Habituals carry no version: they are only created or removed, never
  # edited, so there is nothing for a client to be stale about except existence.
  module HabitualData
    module_function

    def entry(habitual)
      { "public_id" => habitual.public_id, "name" => habitual.name, "normalized_name" => habitual.normalized_name,
        "created_at" => habitual.created_at.utc.iso8601(6) }
    end

    def uuid(value)
      value.downcase if value.is_a?(String) && SyncCommand::UUID.match?(value)
    end

    def malformed(field)
      SyncCommandProcessor::Outcome.rejected("malformed_command", "field" => field)
    end
  end
end
