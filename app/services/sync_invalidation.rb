# Sends revision-only hints to native sync clients. The snapshot remains the
# source of truth; ActionCable frames never contain list or dictionary data.
module SyncInvalidation
  STREAM = "android_sync_v1".freeze

  module_function

  def broadcast_household(household)
    ActionCable.server.broadcast(stream_for(household), payload(household))
  end

  def stream_for(household)
    "#{STREAM}:#{household.public_id}"
  end

  def payload(household)
    { type: "invalidate", household_revision: household.reload.sync_revision,
      dictionary_revision: SyncScope.revision_for(SyncScope::DICTIONARY) }
  end
end
