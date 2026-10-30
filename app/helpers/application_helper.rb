module ApplicationHelper
  THEMES = %w[dark light].freeze
  DEFAULT_THEME = "dark".freeze
  THEME_COOKIE = :theme
  # Browser chrome colors (the PWA status bar), matching the canvas token of
  # each theme in app/assets/tailwind/application.css.
  COLORS = %w[teal pink].freeze
  DEFAULT_COLOR = "teal".freeze
  COLOR_COOKIE = :color
  COLOR_THEME_COLORS = {
    "teal" => { "dark" => "#09090B", "light" => "#F4F4F5" },
    "pink" => { "dark" => "#151014", "light" => "#fcf8fa" }
  }.freeze

  # The two spaces of the app: "add" (the starting one) and "list". Which one
  # is open is a device preference, like the theme: saved in a cookie by
  # screen_controller.js and never broadcast.
  SCREENS = %w[add list].freeze
  DEFAULT_SCREEN = "add".freeze
  SCREEN_COOKIE = :screen

  # "Iniciar compras": a checklist mode of the list screen, also a device
  # preference (cookie written by shopping_controller.js, never broadcast).
  # It is separate from the old market_mode cookie.
  SHOPPING_COOKIE = :shopping_mode
  SHOPPING_ON = "on".freeze

  # The theme saved in the device's cookie. Anything unexpected falls back to
  # dark, which stays the default. Broadcast renders have no request (and so
  # no cookie); they are only used for the list markup, never the <html> tag.
  def theme_name
    saved = cookies[THEME_COOKIE]
    THEMES.include?(saved) ? saved : DEFAULT_THEME
  end

  def theme_color(theme = theme_name)
    COLOR_THEME_COLORS.fetch(color_name).fetch(theme)
  end

  def color_name
    saved = cookies[COLOR_COOKIE]
    COLORS.include?(saved) ? saved : DEFAULT_COLOR
  end

  def shopping_mode?
    cookies[SHOPPING_COOKIE] == SHOPPING_ON
  end

  # The screen this device had open, so a reload (or Android bringing the PWA
  # back) reopens it. The old "market mode" cookie meant "only the list", so
  # it still opens the list until the device picks a screen. While shopping,
  # it is always the list, whatever the saved screen says.
  def current_screen
    return "list" if shopping_mode?

    saved = cookies[SCREEN_COOKIE]
    return saved if SCREENS.include?(saved)

    cookies[:market_mode] == "true" ? "list" : DEFAULT_SCREEN
  end
end
