require "test_helper"

# The motion layer is decoration: it explains a state change but never delays
# one, and the same numbers feed the Compose client (AlvaMotion, compared by
# PwaThemeParityTest). This test reads the real CSS, so a new transition without
# its `prefers-reduced-motion` counterpart — or a hard-coded duration — fails
# here instead of shipping as a slow tap for someone who asked for less motion.
class MotionTokensTest < ActiveSupport::TestCase
  CSS = Rails.root.join("app/assets/tailwind/application.css").read

  # duration in ms, or [x1, y1, x2, y2] of a cubic-bezier
  TOKENS = {
    "--duration-fast" => 120,
    "--duration-base" => 180,
    "--duration-slow" => 300,
    "--duration-switch" => 150
  }.freeze

  EASINGS = {
    "--ease-standard" => [ 0.2, 0, 0, 1 ],
    "--ease-exit" => [ 0.4, 0, 1, 1 ]
  }.freeze

  test "the theme declares the shared durations and easings" do
    theme = theme_block
    TOKENS.each do |token, millis|
      assert_equal "#{millis}ms", theme[token], "#{token} is not the #{millis}ms of the scale"
    end
    EASINGS.each do |token, control|
      match = theme.fetch(token).match(/\Acubic-bezier\(([\d.,\s]+)\)\z/)
      assert match, "#{token} is not a cubic-bezier(): #{theme[token]}"
      assert_equal control, match[1].split(",").map { |value| value.strip.to_f }
    end
    # The three state-change durations are the 120/180/300 the issue asks for.
    assert_equal [ 120, 180, 300 ], TOKENS.values.first(3)
  end

  test "no rule writes a duration or an easing of its own" do
    # A bare `150ms`/`cubic-bezier(...)` in a transition bypasses the scale and
    # the parity test with Compose, which is what the tokens exist to prevent.
    offenders = rules.filter_map do |selector, body|
      next unless body.match?(/\b(?:transition|animation)\b/)
      next unless body.match?(/\b[\d.]+m?s\b/) || body.match?(/cubic-bezier|ease-(?:in|out)/)
      selector
    end
    assert_empty offenders, "these rules name a duration instead of a --duration-*/--ease-* token: #{offenders.join(", ")}"
  end

  test "every transition and animation has its reduced-motion counterpart" do
    dismissed = reduced_motion_block
    missing = animated_selectors.reject { |selector| dismissed.include?(selector) }
    assert_empty missing, "prefers-reduced-motion does not turn off: #{missing.join(", ")}"
  end

  test "the highlight of a just-added row is on the scale too" do
    # The animation token uses the standard easing and the one block owns it.
    assert_match(/--animate-highlight: highlight [\d.]+s var\(--ease-standard\)/, theme_block.to_s + CSS)
    assert_includes reduced_motion_block, ".animate-highlight"
  end

  private

  def theme_block
    body = CSS[/@theme \{(.*?)\n\}/m, 1] or flunk("no @theme block")
    body.scan(/(--[\w-]+):\s*([^;]+);/).to_h { |name, value| [ name, value.strip ] }
  end

  # selector => declarations, for the plain rules of the file (no at-rules).
  def rules
    without_comments = CSS.gsub(%r{/\*.*?\*/}m, "")
    without_comments.scan(/([^{}]+)\{([^{}]*)\}/).map { |selector, body| [ selector.strip, body ] }
  end

  def animated_selectors
    rules.select { |_, body| body.match?(/\b(?:transition|animation):/) }
      .flat_map { |selector, _| selector.split(",") }
      .map { |selector| selector.strip.gsub(/\s+/, " ") }
      .uniq
  end

  def reduced_motion_block
    CSS[/@media \(prefers-reduced-motion: reduce\) \{(.*?)\n\}/m, 1] or flunk("no prefers-reduced-motion block")
  end
end
