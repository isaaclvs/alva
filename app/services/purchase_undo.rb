# Handed only to the device that toggled the row. Version checking makes undo
# one-use and prevents overwriting a later edit, purchase or archived item.
module PurchaseUndo
  module_function

  def dump(item, status:, purchased_at:)
    verifier.generate({ "id" => item.id, "household_id" => item.household_id,
      "lock_version" => item.lock_version, "status" => status,
      "purchased_at" => purchased_at&.utc&.iso8601(6) }, expires_in: 1.minute)
  end

  def load(token)
    verifier.verified(token) if token.is_a?(String)
  end

  def verifier
    Rails.application.message_verifier(:purchase_undo)
  end
end
