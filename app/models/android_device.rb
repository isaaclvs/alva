class AndroidDevice < ApplicationRecord
  ADD_ONLY = "add_only"
  SYNC = "sync"
  SCOPES = [ ADD_ONLY, SYNC ].freeze

  belongs_to :household

  validates :name, presence: true
  validates :token_digest, presence: true, uniqueness: true
  validates :access_digest, presence: true
  validates :scope, presence: true, inclusion: { in: SCOPES }
  validates :lineage_id, presence: true

  before_validation :start_lineage, on: :create

  # Legacy enrollment (widget) issues add_only. Sync is only enrolled explicitly
  # through the v1 route; an arbitrary scope is rejected by validation.
  #
  # `predecessor` is the sync credential this one replaces when the same phone
  # is authorized again: the new row continues its lineage, so commands the
  # predecessor sent can still be resolved. Without one the credential starts a
  # lineage of its own.
  def self.issue(household:, name:, scope: ADD_ONLY, predecessor: nil)
    secret = SecureRandom.urlsafe_base64(32)
    device = create!(household:, name: name.to_s.strip.presence || "Android", scope:,
      token_digest: digest(secret), access_digest: HouseholdAccess.token,
      lineage_id: predecessor&.lineage_id)
    [ device, secret ]
  end

  # The sync credential of `household` that owns `secret`, even if it was
  # revoked or outlived by a password rotation: holding a past secret is the
  # proof that the caller is the phone that held it. Never matches another
  # household's credential or the widget's add_only one.
  def self.predecessor_for(household, secret)
    return unless secret.is_a?(String) && secret.present?

    where(household:, scope: SYNC, token_digest: digest(secret)).first
  end

  # A credential is valid only while it is not revoked and the household
  # password digest is unchanged. Without a configured password the token
  # version is unknown, so every credential is refused instead of erroring.
  def self.authenticate(secret)
    return if secret.blank?

    access_digest = HouseholdAccess.token
    return if access_digest.blank?

    find_by(token_digest: digest(secret), access_digest:, revoked_at: nil)
  end

  def self.digest(secret)
    Digest::SHA256.hexdigest(secret)
  end

  def sync?
    scope == SYNC
  end

  # Idempotent: revoking an already revoked device keeps the first timestamp.
  def revoke!
    update!(revoked_at: Time.current) unless revoked_at?
  end

  private

  def start_lineage
    self.lineage_id ||= SecureRandom.uuid
  end
end
