class AndroidDevice < ApplicationRecord
  ADD_ONLY = "add_only"
  SYNC = "sync"
  SCOPES = [ ADD_ONLY, SYNC ].freeze

  belongs_to :household

  validates :name, presence: true
  validates :token_digest, presence: true, uniqueness: true
  validates :access_digest, presence: true
  validates :scope, presence: true, inclusion: { in: SCOPES }

  # Legacy enrollment (widget) issues add_only. Sync is only enrolled explicitly
  # through the v1 route; an arbitrary scope is rejected by validation.
  def self.issue(household:, name:, scope: ADD_ONLY)
    secret = SecureRandom.urlsafe_base64(32)
    device = create!(household:, name: name.to_s.strip.presence || "Android", scope:,
      token_digest: digest(secret), access_digest: HouseholdAccess.token)
    [ device, secret ]
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
end
