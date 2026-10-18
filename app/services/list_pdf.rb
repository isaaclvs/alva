require "prawn"

# Windows-1252 is all we need; sanitized in #printable.
Prawn::Fonts::AFM.hide_m17n_warning = true

# Renders the pending items of a household as an A4 PDF, grouped by category,
# with an empty checkbox before each item so it can be ticked with a pen.
# Returns the PDF as a binary string.
class ListPdf
  TITLE = "Lista de compras".freeze
  UNCATEGORIZED = "Outros".freeze
  EMPTY_TEXT = "Nada faltando".freeze

  ITEM_SIZE = 14
  HEADING_SIZE = 16
  BOX_SIZE = 11
  LINE_HEIGHT = 26
  TEXT_COLOR = "18181b".freeze
  MUTED_COLOR = "71717a".freeze

  def self.call(household, date: Date.current)
    new(household, date).render
  end

  def initialize(household, date)
    @groups = pending_groups(household)
    @date = date
  end

  def render
    Prawn::Document.new(page_size: "A4", margin: 48, info: { Title: TITLE }) do |pdf|
      pdf.fill_color TEXT_COLOR
      pdf.stroke_color TEXT_COLOR
      draw_header(pdf)
      @groups.empty? ? draw_empty(pdf) : @groups.each { |name, items| draw_group(pdf, name, items) }
    end.render
  end

  private

  def pending_groups(household)
    household.list_items_by_category.filter_map do |category, items|
      pending = items.select(&:pending?)
      [ category&.name || UNCATEGORIZED, pending ] if pending.any?
    end
  end

  def draw_header(pdf)
    pdf.font("Helvetica", style: :bold, size: 24) { pdf.text TITLE }
    pdf.fill_color MUTED_COLOR
    pdf.font("Helvetica", size: 12) { pdf.text @date.strftime("%d/%m/%Y") }
    pdf.fill_color TEXT_COLOR
    pdf.move_down 20
  end

  def draw_empty(pdf)
    pdf.font("Helvetica", size: ITEM_SIZE) { pdf.text EMPTY_TEXT }
  end

  def draw_group(pdf, name, items)
    # Keep a heading together with at least its first item.
    pdf.start_new_page if pdf.cursor < LINE_HEIGHT * 3
    pdf.move_down 8
    pdf.font("Helvetica", style: :bold, size: HEADING_SIZE) { pdf.text printable(name) }
    pdf.move_down 6
    items.each { |item| draw_item(pdf, item) }
  end

  def draw_item(pdf, item)
    pdf.start_new_page if pdf.cursor < LINE_HEIGHT

    top = pdf.cursor
    pdf.stroke_rectangle [ 0, top - 4 ], BOX_SIZE, BOX_SIZE
    pdf.font("Helvetica", size: ITEM_SIZE) do
      pdf.text_box printable(item.name), at: [ BOX_SIZE + 12, top ], width: pdf.bounds.width - BOX_SIZE - 12,
        height: LINE_HEIGHT, overflow: :shrink_to_fit
    end
    pdf.move_down LINE_HEIGHT
  end

  # The built-in Helvetica only covers Windows-1252 (enough for Portuguese);
  # anything else, like emoji, would raise, so it becomes "?".
  def printable(text)
    text.encode(Encoding::Windows_1252, invalid: :replace, undef: :replace, replace: "?").encode(Encoding::UTF_8)
  end
end
