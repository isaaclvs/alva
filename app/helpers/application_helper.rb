module ApplicationHelper
  THEMES = %w[dark light].freeze
  DEFAULT_THEME = "dark".freeze
  THEME_COOKIE = :theme
  # Browser chrome colors (the PWA status bar), matching the canvas token of
  # each theme in app/assets/tailwind/application.css.
  THEME_COLORS = { "dark" => "#09090B", "light" => "#F4F4F5" }.freeze

  # The two spaces of the app: "add" (the starting one) and "list". Which one
  # is open is a device preference, like the theme: saved in a cookie by
  # screen_controller.js and never broadcast.
  SCREENS = %w[add list].freeze
  DEFAULT_SCREEN = "add".freeze
  SCREEN_COOKIE = :screen

  # The theme saved in the device's cookie. Anything unexpected falls back to
  # dark, which stays the default. Broadcast renders have no request (and so
  # no cookie); they are only used for the list markup, never the <html> tag.
  def theme_name
    saved = cookies[THEME_COOKIE]
    THEMES.include?(saved) ? saved : DEFAULT_THEME
  end

  def theme_color(theme = theme_name)
    THEME_COLORS.fetch(theme)
  end

  # The screen this device had open, so a reload (or Android bringing the PWA
  # back) reopens it. The old "market mode" cookie meant "only the list", so
  # it still opens the list until the device picks a screen.
  def current_screen
    saved = cookies[SCREEN_COOKIE]
    return saved if SCREENS.include?(saved)

    cookies[:market_mode] == "true" ? "list" : DEFAULT_SCREEN
  end
end
