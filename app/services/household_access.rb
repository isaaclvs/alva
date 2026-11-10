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

  # Version of the password a credential is bound to. Nil when access is open,
  # so an old credential is refused (not a 500) after the password is removed.
  def self.token
    return unless enabled?

    Digest::SHA256.hexdigest(ENV.fetch("ALVA_PASSWORD_DIGEST"))
  end

  def self.authorized?(value)
    !enabled? || (value.is_a?(String) && ActiveSupport::SecurityUtils.secure_compare(value, token))
  end
end
