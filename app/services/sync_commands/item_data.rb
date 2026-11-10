module SyncCommands
  # How a command reports an item, and how a later command reads that back.
  #
  # Every entry carries `public_id` (the UUID the command cited),
  # `canonical_public_id` (the server's record; different when the cited UUID is
  # an alias) and the state the item has *after* the command, including the
  # `lock_version` that a dependent command inherits (see
  # SyncCommands::SetItemState).
  module ItemData
    module_function

    def entry(item, cited_public_id, **extra)
      { "public_id" => cited_public_id, "canonical_public_id" => item.public_id, "name" => item.name,
        "status" => item.status, "purchased_at" => item.purchased_at&.utc&.iso8601(6),
        "lock_version" => item.lock_version }.merge(extra.stringify_keys)
    end
  end
end
