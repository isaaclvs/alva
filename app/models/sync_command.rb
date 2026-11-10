require "digest"
require "json"

# Idempotency ledger of the sync protocol: one row per command whose effect was
# committed, written in the same transaction as that effect (see
# SyncCommandProcessor). The row is the command's identity — the UUID, the
# household, the credential and lineage that sent it, the digest of its
# canonical content and its result. A command that did not take effect has no
# row, so it can be sent again.
#
# Rows are never pruned: see "Retenção" in docs/plans/android-sync-contract-v1.md.
class SyncCommand < ApplicationRecord
  UUID = /\A\h{8}-\h{4}-\h{4}-\h{4}-\h{12}\z/
  STATUSES = %w[applied noop].freeze

  belongs_to :household
  belongs_to :android_device

  validates :command_id, presence: true, format: { with: UUID }, uniqueness: { scope: :household_id }
  validates :lineage_id, :command_type, :content_digest, presence: true
  validates :status, inclusion: { in: STATUSES }
  validates :revision, numericality: { only_integer: true, greater_than_or_equal_to: 0 }

  # Digest of what the command asks for, independent of key order and of how the
  # client formatted the JSON. The UUID is the key, not part of the content;
  # array order is kept because it is meaningful (e.g. names to add).
  def self.content_digest(type:, depends_on:, observed_revision:, preconditions:, payload:)
    content = { "type" => type, "depends_on" => depends_on, "observed_revision" => observed_revision,
      "preconditions" => preconditions, "payload" => payload }
    Digest::SHA256.hexdigest(JSON.generate(canonical(content)))
  end

  def self.canonical(value)
    case value
    when Hash then value.sort_by { |key, _| key.to_s }.to_h { |key, item| [ key.to_s, canonical(item) ] }
    when Array then value.map { |item| canonical(item) }
    else value
    end
  end
  private_class_method :canonical
end
