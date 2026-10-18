module ApplicationHelper
  THEMES = %w[dark light].freeze
  DEFAULT_THEME = "dark".freeze
  THEME_COOKIE = :theme
  # Browser chrome colors (the PWA status bar), matching zinc-950 / zinc-100.
  THEME_COLORS = { "dark" => "#09090B", "light" => "#F4F4F5" }.freeze

  # The theme saved in the device's cookie. Anything unexpected falls back to
  # dark, which stays the default. Broadcast renders have no request (and so
  # no cookie); they are only used for the list markup, never the <html> tag.
  def theme_name
    saved = cookies[THEME_COOKIE] if respond_to?(:cookies)
    THEMES.include?(saved) ? saved : DEFAULT_THEME
  end

  def theme_color(theme = theme_name)
    THEME_COLORS.fetch(theme)
  end
end
