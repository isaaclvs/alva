# A signed, short-lived copy of a list item, handed to the client that removed
# it so "Desfazer" can put it back exactly as it was. Nothing is stored on the
# server: the token is the only copy, and a tampered or expired one is
# rejected (load returns nil).
module ListItemSnapshot
  EXPIRES_IN = 1.minute
  ATTRIBUTES = %w[id household_id name category_id status position purchased_at created_at updated_at].freeze

  module_function

  def dump(item)
    verifier.generate(serialize(item), expires_in: EXPIRES_IN)
  end

  # Attributes (string keys) of the removed item, or nil if the token is not a
  # valid, unexpired snapshot.
  def load(token)
    return unless token.is_a?(String)

    verifier.verified(token)
  end

  def serialize(item)
    ATTRIBUTES.index_with { |name| serialize_value(item.public_send(name)) }
  end

  # JSON has no Time: keep microseconds so the position among items created in
  # the same second survives the round trip.
  def serialize_value(value)
    value.respond_to?(:utc) ? value.utc.iso8601(6) : value
  end

  def verifier
    Rails.application.message_verifier(:undo)
  end
end
