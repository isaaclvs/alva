require "test_helper"

class TextNormalizerTest < ActiveSupport::TestCase
  test "removes accents and downcases" do
    assert_equal "pao de acucar", TextNormalizer.call("Pão de Açúcar")
  end

  test "collapses and trims whitespace" do
    assert_equal "leite integral", TextNormalizer.call("  Leite   Integral ")
  end

  test "handles nil" do
    assert_equal "", TextNormalizer.call(nil)
  end
end
