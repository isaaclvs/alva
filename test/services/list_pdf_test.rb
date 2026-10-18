require "test_helper"

class ListPdfTest < ActiveSupport::TestCase
  setup do
    @household = households(:home)
    @now = Time.zone.local(2026, 3, 7, 18, 42) # a Saturday
  end

  test "renders the header, the pending items and the footer" do
    text = extract_text(ListPdf.call(@household, now: @now))

    assert_includes text, "Lista de compras"
    assert_includes text, "sábado, 7 de março"
    assert_includes text, "1 item · 1 categoria"
    assert_includes text, "LATICÍNIOS"
    assert_includes text, "Leite"
    assert_not_includes text, "Detergente"
    assert_includes text, "LEMBREI AGORA"
    assert_includes text, "gerada em 07/03/2026 às 18:42 · página 1 de 1"
  end

  test "output is a PDF document" do
    assert ListPdf.call(@household).start_with?("%PDF")
  end

  test "counts items and categories in the plural" do
    @household.list_items.create!(name: "Banana")

    assert_includes extract_text(ListPdf.call(@household)), "2 itens · 2 categorias"
  end

  test "lays the cards out in app order, the Outros category last" do
    @household.list_items.create!(name: "Coisa nova")
    @household.list_items.create!(name: "Banana")

    pieces = ListPdf.new(@household, @now).pages.flatten

    assert_equal %w[Hortifruti Laticínios Outros], pieces.map(&:label)
    assert_equal [ %w[Banana], %w[Leite], [ "Coisa nova" ] ], pieces.map(&:items)
  end

  test "items without a category get their own card, not a second Outros" do
    @household.list_items.create!(name: "Coisa nova")
    ListItem.insert!({ name: "Item antigo", household_id: @household.id, category_id: nil,
      status: ListItem.statuses[:pending], created_at: Time.current, updated_at: Time.current })

    pieces = ListPdf.new(@household, @now).pages.flatten

    assert_equal [ "Laticínios", "Outros", "Sem categoria" ], pieces.map(&:label)
    assert_equal 1, extract_text(ListPdf.call(@household)).scan("OUTROS").size
  end

  test "skips categories without pending items" do
    @household.list_items.create!(name: "Banana", status: :purchased)

    assert_not_includes extract_text(ListPdf.call(@household)), "HORTIFRUTI"
  end

  test "says nothing is missing when there are no pending items" do
    @household.list_items.pending.delete_all

    text = extract_text(ListPdf.call(@household))

    assert_includes text, "Nada faltando"
    assert_not_includes text, "LATICÍNIOS"
  end

  test "renders Portuguese accents with the embedded font" do
    @household.list_items.create!(name: "Pão francês")
    @household.list_items.create!(name: "Feijão")

    text = extract_text(ListPdf.call(@household))

    assert_includes text, "Pão francês"
    assert_includes text, "Feijão"
    assert_includes text, "LATICÍNIOS"
  end

  test "drops characters the font cannot draw, like emoji" do
    @household.list_items.create!(name: "Pizza 🍕")

    text = extract_text(ListPdf.call(@household))
    assert_includes text, "Pizza"
    assert_not_includes text, "🍕"
  end

  test "flows long lists onto more pages, numbering them" do
    100.times { |i| @household.list_items.create!(name: "Item #{i}") }

    pdf = ListPdf.call(@household, now: @now)

    assert_operator PDF::Reader.new(StringIO.new(pdf)).page_count, :>, 1
    assert_includes extract_text(pdf), "(CONT.)"
    assert_includes extract_text(pdf), "página 2 de 2"
  end

  private

  def extract_text(pdf)
    PDF::Reader.new(StringIO.new(pdf)).pages.map(&:text).join("\n")
  end
end
