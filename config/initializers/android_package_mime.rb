# Let browsers and Android recognize APK downloads instead of treating unknown
# static extensions as plain text (ActionDispatch::FileHandler's fallback).
Rack::Mime::MIME_TYPES[".apk"] = "application/vnd.android.package-archive"
