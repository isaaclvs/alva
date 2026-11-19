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

  test "plain removes accents, case and extra spaces without stemming" do
    assert_equal "pao frances", TextNormalizer.plain("  Pão   Francês ")
    assert_equal "bananas", TextNormalizer.plain("Bananas")
  end

  test "reads a look-alike digit inside a word as its letter" do
    {
      "far0fa" => "farofa", "b4n4na" => "banana", "l3ite" => "leite", "fAroFà" => "farofa",
      "S0rvete" => "sorvete", "p1zza" => "pizza", "c0ca" => "coca", "pre5unto" => "presunto",
      "b0l4cha" => "bolacha", "F4R0FA" => "farofa", "7up" => "7up"
    }.each do |typed, expected|
      assert_equal expected, TextNormalizer.plain(typed), "plain(#{typed.inspect})"
      assert_equal expected, TextNormalizer.call(typed), "call(#{typed.inspect})"
    end
  end

  test "a look-alike digit means the same key and the same dictionary match" do
    assert_equal TextNormalizer.call("Farofa"), TextNormalizer.call("far0fa")
    assert_equal TextNormalizer.call("Banana"), TextNormalizer.call("b4n4na")
    assert_equal TextNormalizer.call("Leite"), TextNormalizer.call("l3ite")
    assert_equal TextNormalizer.call("Leite condensado"), TextNormalizer.call("l3ite condens4do")
  end

  test "a digit with a letter on one side only is not folded" do
    # A digit needs a letter on both sides: supplements and packages keep their
    # number instead of turning into an unrelated word.
    {
      "vitamina d3" => "vitamina d3", "omega3" => "omega3", "complexo b1" => "complexo b1",
      "vitamina b5" => "vitamina b5", "v8" => "v8", "b12" => "b12", "7up" => "7up",
      "500g" => "500g", "coca 2l" => "coca 2l", "leite 0%" => "leite 0%",
      # Only the digits that are really between letters move.
      "l3it3" => "leit3", "b4n4n4" => "banan4", "pre5unt0" => "presunt0"
    }.each do |text, expected|
      assert_equal expected, TextNormalizer.plain(text), "plain(#{text.inspect})"
    end
  end

  test "digits that are not look-alikes inside a word keep their meaning" do
    [ "leite 0%", "coca 2l", "500g", "7up", "vitamina b12", "v8", "Leite 1L", "Açúcar 1kg", "6 ovos" ].each do |text|
      assert_equal I18n.transliterate(text).downcase, TextNormalizer.plain(text), "plain(#{text.inspect})"
    end
  end

  test "a digit followed by another digit is not folded" do
    assert_equal "produto 13", TextNormalizer.plain("Produto 13")
    assert_equal "b12", TextNormalizer.plain("B12")
  end
end
