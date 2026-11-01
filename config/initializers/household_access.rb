# Fail closed if a public deployment has no password, including on restart.
if ENV["ALVA_PUBLIC_HOST"].present? || ENV["ALVA_PASSWORD_DIGEST"].present?
  digest = ENV["ALVA_PASSWORD_DIGEST"]
  unless digest.present? && BCrypt::Password.valid_hash?(digest)
    raise "Configure ALVA_PASSWORD_DIGEST with a valid bcrypt hash before publishing Alva"
  end
end
