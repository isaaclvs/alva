require "test_helper"

class ListPdfTest < ActiveSupport::TestCase
  setup { @household = households(:home) }

  test "renders a PDF with title, date and pending items only" do
    text = extract_text(ListPdf.call(@household, date: Date.new(2026, 3, 7)))

    assert_includes text, "Lista de compras"
    assert_includes text, "07/03/2026"
    assert_includes text, "Leite"
    assert_not_includes text, "Detergente"
    assert_not_includes text, "Nada faltando"
  end

  test "output is a PDF document" do
    assert ListPdf.call(@household).start_with?("%PDF")
  end

  test "groups items under category headings in app order, the Outros category last" do
    @household.list_items.create!(name: "Coisa nova")
    @household.list_items.create!(name: "Banana")

    text = extract_text(ListPdf.call(@household))

    assert_operator text.index("Hortifruti"), :<, text.index("Banana")
    assert_operator text.index("Banana"), :<, text.index("Laticínios")
    assert_operator text.index("Laticínios"), :<, text.index("Leite")
    assert_operator text.index("Leite"), :<, text.index("Outros")
    assert_operator text.index("Outros"), :<, text.index("Coisa nova")
  end

  test "items without a category get their own heading, not a second Outros" do
    @household.list_items.create!(name: "Coisa nova")
    ListItem.insert!({ name: "Item antigo", household_id: @household.id, category_id: nil,
      status: ListItem.statuses[:pending], created_at: Time.current, updated_at: Time.current })

    text = extract_text(ListPdf.call(@household))

    assert_equal 1, text.scan("Outros").size
    assert_operator text.index("Coisa nova"), :<, text.index("Sem categoria")
    assert_operator text.index("Sem categoria"), :<, text.index("Item antigo")
  end

  test "skips categories without pending items" do
    @household.list_items.create!(name: "Banana", status: :purchased)

    assert_not_includes extract_text(ListPdf.call(@household)), "Hortifruti"
  end

  test "says nothing is missing when there are no pending items" do
    @household.list_items.pending.delete_all

    text = extract_text(ListPdf.call(@household))

    assert_includes text, "Nada faltando"
    assert_not_includes text, "Laticínios"
  end

  test "renders Portuguese accents" do
    @household.list_items.create!(name: "Pão francês")
    @household.list_items.create!(name: "Açougue de ração")

    text = extract_text(ListPdf.call(@household))

    assert_includes text, "Pão francês"
    assert_includes text, "Laticínios"
    assert_includes text, "Açougue de ração"
  end

  test "does not fail on characters Helvetica cannot draw" do
    @household.list_items.create!(name: "Pizza 🍕")

    assert_includes extract_text(ListPdf.call(@household)), "Pizza ?"
  end

  test "draws one empty checkbox per pending item" do
    @household.list_items.create!(name: "Banana")

    # Each box is a stroked rectangle ("re ... S"); the list has Leite and Banana.
    content = PDF::Reader.new(StringIO.new(ListPdf.call(@household))).pages.first.raw_content
    assert_equal 2, content.scan(/\bre\b/).size
  end

  test "flows long lists onto more pages" do
    40.times { |i| @household.list_items.create!(name: "Item #{i}") }

    assert_operator PDF::Reader.new(StringIO.new(ListPdf.call(@household))).page_count, :>, 1
  end

  private

  def extract_text(pdf)
    PDF::Reader.new(StringIO.new(pdf)).pages.map(&:text).join("\n")
  end
end
