require "test_helper"

# Every text pair the two screens actually use has to clear the WCAG 2.x ratio
# for normal text (4.5:1) in all four combinations of dark/light and teal/pink.
# The Android palettes are copies of these tokens — PwaThemeParityTest compares
# them with the CSS — so this list covers both platforms.
# Values and the reasoning behind each change: docs/brand/pink-light.md and #147.
class ThemeContrastTest < ActiveSupport::TestCase
  MINIMUM = 4.5

  # text token, background token
  PAIRS = [
    %w[ink canvas], %w[ink surface], %w[ink surface-2],
    %w[ink-soft canvas], %w[ink-soft surface], %w[ink-soft surface-2],
    %w[ink-faint canvas], %w[ink-faint surface], %w[ink-faint surface-2],
    %w[accent canvas], %w[accent surface], %w[accent surface-2],
    %w[accent-strong canvas], %w[accent-strong surface],
    %w[accent-ink accent],
    %w[warn canvas],
    %w[danger canvas], %w[danger surface], %w[danger surface-2],
    # The swipe-to-remove strip behind a row: `bg-danger` with `text-canvas`.
    %w[canvas danger]
  ]

  APPLICATION = Rails.root.join("app/assets/tailwind/application.css").read
  PINK_LIGHT = Rails.root.join("app/assets/tailwind/pink_light.css").read

  test "every text pair clears 4.5:1 in the four palettes" do
    themes.each do |name, tokens|
      PAIRS.each do |text, background|
        ratio = contrast(tokens.fetch("--color-#{text}"), tokens.fetch("--color-#{background}"))
        assert ratio >= MINIMUM, "#{name}: #{text} on #{background} is #{ratio.round(2)}:1, below #{MINIMUM}:1"
      end
    end
  end

  test "the three text levels stay distinct and ordered in every palette" do
    themes.each do |name, tokens|
      %w[canvas surface surface-2].each do |background|
        levels = %w[ink ink-soft ink-faint].map { |text| contrast(tokens.fetch("--color-#{text}"), tokens.fetch("--color-#{background}")) }
        assert levels[0] > levels[1], "#{name}: ink is not stronger than ink-soft on #{background}"
        assert levels[1] > levels[2], "#{name}: ink-soft is not stronger than ink-faint on #{background}"
      end
    end
  end

  private

  # The cascade of application.css and pink_light.css for each combination.
  def themes
    base = declarations(APPLICATION, "@theme")
    {
      "dark teal" => base,
      "light teal" => base.merge(declarations(APPLICATION, 'html[data-theme="light"]')),
      "dark pink" => base.merge(declarations(APPLICATION, 'html[data-color="pink"]:not([data-theme="light"])')),
      "light pink" => base.merge(declarations(APPLICATION, 'html[data-theme="light"]'))
                        .merge(declarations(PINK_LIGHT, 'html[data-color="pink"][data-theme="light"]'))
    }
  end

  def declarations(css, selector)
    start = css.index("#{selector} {")
    assert start, "rule not found: #{selector}"
    body = css[(start + selector.length + 2)..].split("}", 2).first
    body.scan(/(--color-[\w-]+):\s*([^;]+);/).to_h { |token, value| [ token, value.strip ] }
  end

  def contrast(first, second)
    low, high = [ luminance(first), luminance(second) ].minmax
    (high + 0.05) / (low + 0.05)
  end

  # WCAG 2.x relative luminance of an sRGB hex color.
  def luminance(hex)
    channels = hex.delete_prefix("#").scan(/../).map { |channel| channel.to_i(16) / 255.0 }
    weights = [ 0.2126, 0.7152, 0.0722 ]
    channels.zip(weights).sum do |channel, weight|
      linear = channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055)**2.4
      linear * weight
    end
  end
end
