class AndroidDevice < ApplicationRecord
  belongs_to :household

  validates :name, presence: true
  validates :token_digest, presence: true, uniqueness: true
  validates :access_digest, presence: true

  def self.issue(household:, name:)
    secret = SecureRandom.urlsafe_base64(32)
    device = create!(household:, name: name.to_s.strip.presence || "Android", token_digest: digest(secret),
      access_digest: HouseholdAccess.token)
    [ device, secret ]
  end

  def self.authenticate(secret)
    return if secret.blank?

    find_by(token_digest: digest(secret), access_digest: HouseholdAccess.token, revoked_at: nil)
  end

  def self.digest(secret)
    Digest::SHA256.hexdigest(secret)
  end
end
