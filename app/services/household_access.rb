# One shared password, no accounts. Optional on private installations; required
# when ALVA_PUBLIC_HOST is set. Rotating the digest invalidates every cookie.
class HouseholdAccess
  COOKIE = :alva_access
  DURATION = 30.days

  def self.enabled?
    ENV["ALVA_PASSWORD_DIGEST"].present? || ENV["ALVA_PUBLIC_HOST"].present?
  end

  def self.authenticate(password)
    BCrypt::Password.new(ENV.fetch("ALVA_PASSWORD_DIGEST")).is_password?(password.to_s)
  end

  def self.token
    Digest::SHA256.hexdigest(ENV.fetch("ALVA_PASSWORD_DIGEST"))
  end

  def self.authorized?(value)
    !enabled? || (value.is_a?(String) && ActiveSupport::SecurityUtils.secure_compare(value, token))
  end
end
