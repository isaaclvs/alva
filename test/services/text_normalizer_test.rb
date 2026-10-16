require "test_helper"

class TextNormalizerTest < ActiveSupport::TestCase
  test "removes accents and downcases" do
    assert_equal "pao de acucar", TextNormalizer.call("Pão de Açúcar")
  end

  test "collapses and trims whitespace" do
    assert_equal "leite integral", TextNormalizer.call("  Leite   Integral ")
  end

  test "plural and singular forms share a key" do
    {
      "Bananas" => "Banana", "Ovos" => "Ovo", "Pães" => "Pão", "Limões" => "Limão",
      "Cereais" => "Cereal", "Pastéis" => "Pastel", "Bombons" => "Bombom",
      "Flores" => "Flor", "Nozes" => "Noz", "Vinagres" => "Vinagre",
      "Pães de queijo" => "Pão de queijo"
    }.each do |plural, singular|
      assert_equal TextNormalizer.call(singular), TextNormalizer.call(plural), "#{plural} vs #{singular}"
    end
  end

  test "leaves short words alone" do
    assert_equal "gas", TextNormalizer.call("Gás")
  end

  test "handles nil" do
    assert_equal "", TextNormalizer.call(nil)
  end
end
