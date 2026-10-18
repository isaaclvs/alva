require "prawn"

# Renders the pending items of a household as an A4 PDF to take to the shop:
# a header with the day and totals, one card per category (colored like the
# app) laid out in two columns, each item with an empty box to tick with a
# pen, a few blank lines for things remembered on the way, and a footer with
# when it was generated and the page number. Returns the PDF as a string.
class ListPdf
  TITLE = "Lista de compras".freeze
  EMPTY_TEXT = "Nada faltando".freeze
  EXTRA_LABEL = "Lembrei agora".freeze

  WEEKDAYS = %w[domingo segunda-feira terça-feira quarta-feira quinta-feira sexta-feira sábado].freeze
  MONTHS = %w[janeiro fevereiro março abril maio junho julho agosto setembro outubro novembro dezembro].freeze

  # Same hues as the category icons in the app (ListItemsHelper::CATEGORY_ICONS).
  CATEGORY_COLORS = {
    "Hortifruti" => "10b981",
    "Laticínios" => "0ea5e9",
    "Mercearia" => "f59e0b",
    "Açougue" => "f43f5e",
    "Padaria" => "f97316",
    "Bebidas" => "06b6d4",
    "Limpeza" => "6366f1",
    "Higiene" => "d946ef"
  }.freeze
  DEFAULT_COLOR = "a1a1aa".freeze

  FONT_DIR = Rails.root.join("vendor/fonts/inter")

  TEXT_COLOR = "18181b".freeze
  MUTED_COLOR = "71717a".freeze
  FAINT_COLOR = "a1a1aa".freeze
  BORDER_COLOR = "e4e4e7".freeze
  BOX_COLOR = "52525b".freeze

  # Geometry, in PDF points (A4 is 595 × 842).
  MARGIN = 40
  GUTTER = 14
  HEADER_HEIGHT = 50
  FOOTER_HEIGHT = 20
  CARD_GAP = 10
  CARD_PADDING_X = 10
  CARD_PADDING_TOP = 8
  CARD_HEADING_HEIGHT = 16
  CARD_PADDING_BOTTOM = 4
  CARD_RADIUS = 5
  ROW_HEIGHT = 19
  BOX_SIZE = 8
  EXTRA_ROWS = 3
  EXTRA_HEIGHT = 18 + EXTRA_ROWS * ROW_HEIGHT

  def self.call(household, now: Time.current)
    new(household, now).render
  end

  def initialize(household, now)
    @groups = pending_groups(household)
    @now = now
  end

  def render
    Prawn::Document.new(page_size: "A4", margin: MARGIN, info: { Title: TITLE }) do |pdf|
      @pdf = pdf
      setup_font
      draw_pages
      draw_footer
    end.render
  end

  # Where each card goes; see ColumnLayout.
  def pages
    ColumnLayout.call(@groups,
      height_for: ->(rows) { card_height(rows) },
      first_capacity: content_height - HEADER_HEIGHT - FOOTER_HEIGHT,
      capacity: content_height - FOOTER_HEIGHT,
      gap: CARD_GAP)
  end

  private

  attr_reader :pdf

  def pending_groups(household)
    household.list_items_by_category.filter_map do |category, items|
      pending = items.select(&:pending?)
      [ Category.label_for(category), pending.map(&:name) ] if pending.any?
    end
  end

  def setup_font
    pdf.font_families.update("Inter" => {
      normal: FONT_DIR.join("Inter-Regular.ttf").to_s,
      bold: FONT_DIR.join("Inter-Bold.ttf").to_s
    })
    pdf.font "Inter"
    pdf.fill_color TEXT_COLOR
  end

  def draw_pages
    layout = pages
    bottoms = []

    layout.each_with_index do |columns, index|
      pdf.start_new_page if index.positive?
      top = pdf.bounds.top
      if index.zero?
        draw_header
        top -= HEADER_HEIGHT
      end

      bottoms = columns.each_with_index.map do |pieces, column|
        x = column * (column_width + GUTTER)
        pieces.reduce(top) { |y, piece| draw_card(piece, x, y) - CARD_GAP } + CARD_GAP
      end
    end

    draw_empty if @groups.empty?
    draw_extra_lines if bottoms.min - (pdf.bounds.bottom + FOOTER_HEIGHT) >= EXTRA_HEIGHT + CARD_GAP * 2
  end

  def draw_header
    top = pdf.bounds.top
    pdf.font("Inter", style: :bold, size: 22) do
      pdf.text_box TITLE, at: [ 0, top ], width: pdf.bounds.width, height: 30
    end

    pdf.fill_color MUTED_COLOR
    pdf.text_box "#{day_text}\n#{totals_text}", at: [ 0, top + 1 ], width: pdf.bounds.width, height: 30,
      size: 8.5, leading: 2, align: :right, valign: :bottom
    pdf.fill_color TEXT_COLOR

    pdf.line_width 1.2
    pdf.stroke_color TEXT_COLOR
    pdf.stroke_horizontal_line 0, pdf.bounds.width, at: top - 36
  end

  def draw_empty
    pdf.fill_color MUTED_COLOR
    pdf.text_box EMPTY_TEXT, at: [ 0, pdf.bounds.top - HEADER_HEIGHT ], width: pdf.bounds.width, height: 20, size: 12
    pdf.fill_color TEXT_COLOR
  end

  # Returns the y where the card ends.
  def draw_card(piece, x, top)
    height = card_height(piece.items.size)

    pdf.line_width 0.75
    pdf.stroke_color BORDER_COLOR
    pdf.stroke_rounded_rectangle [ x, top ], column_width, height, CARD_RADIUS
    pdf.fill_color CATEGORY_COLORS.fetch(piece.label, DEFAULT_COLOR)
    pdf.fill_rounded_rectangle [ x, top ], 3, height, 1.5
    pdf.fill_color TEXT_COLOR

    draw_card_heading(piece, x, top - CARD_PADDING_TOP)
    row_top = top - CARD_PADDING_TOP - CARD_HEADING_HEIGHT
    piece.items.each_with_index do |name, index|
      draw_row(name, x, row_top - index * ROW_HEIGHT, separator: index.positive?)
    end

    top - height
  end

  def draw_card_heading(piece, x, top)
    inner_x = x + CARD_PADDING_X
    inner_width = column_width - CARD_PADDING_X * 2
    label = piece.label.upcase
    label += " (CONT.)" if piece.continued

    pdf.font("Inter", style: :bold, size: 7.5) do
      pdf.text_box printable(label), at: [ inner_x, top ], width: inner_width - 20, height: 12,
        character_spacing: 0.6, overflow: :shrink_to_fit
      pdf.fill_color FAINT_COLOR
      pdf.text_box piece.total.to_s, at: [ inner_x, top ], width: inner_width, height: 12, align: :right
      pdf.fill_color TEXT_COLOR
    end
  end

  def draw_row(name, x, top, separator:)
    inner_x = x + CARD_PADDING_X
    inner_width = column_width - CARD_PADDING_X * 2

    if separator
      pdf.stroke_color BORDER_COLOR
      pdf.line_width 0.6
      pdf.dash(1, space: 1.5)
      pdf.stroke_horizontal_line inner_x, inner_x + inner_width, at: top
      pdf.undash
    end

    draw_box(inner_x, top - (ROW_HEIGHT - BOX_SIZE) / 2.0)
    pdf.text_box printable(name), at: [ inner_x + BOX_SIZE + 8, top ], width: inner_width - BOX_SIZE - 8,
      height: ROW_HEIGHT, size: 10.5, valign: :center, single_line: true, overflow: :shrink_to_fit, min_font_size: 7
  end

  def draw_box(x, top)
    pdf.stroke_color BOX_COLOR
    pdf.line_width 0.9
    pdf.stroke_rounded_rectangle [ x, top ], BOX_SIZE, BOX_SIZE, 1.5
  end

  # Blank lines, anchored above the footer of the last page, for items to
  # write by hand. Left out when the last page has no room for them.
  def draw_extra_lines
    top = pdf.bounds.bottom + FOOTER_HEIGHT + EXTRA_HEIGHT

    pdf.fill_color MUTED_COLOR
    pdf.font("Inter", style: :bold, size: 7.5) do
      pdf.text_box EXTRA_LABEL.upcase, at: [ 0, top ], width: pdf.bounds.width, height: 12, character_spacing: 0.6
    end
    pdf.fill_color TEXT_COLOR

    2.times do |column|
      x = column * (column_width + GUTTER)
      EXTRA_ROWS.times do |row|
        row_top = top - 16 - row * ROW_HEIGHT
        draw_box(x, row_top - (ROW_HEIGHT - BOX_SIZE) / 2.0)
        pdf.stroke_color BORDER_COLOR
        pdf.line_width 0.6
        pdf.stroke_horizontal_line x + BOX_SIZE + 8, x + column_width, at: row_top - ROW_HEIGHT + 3
      end
    end
  end

  def draw_footer
    pdf.repeat(:all) do
      pdf.fill_color FAINT_COLOR
      pdf.text_box "Alva", at: [ 0, pdf.bounds.bottom + 9 ], width: 100, height: 10, size: 7.5
      pdf.fill_color TEXT_COLOR
    end
    pdf.number_pages "gerada em #{@now.strftime('%d/%m/%Y às %H:%M')} · página <page> de <total>",
      at: [ 0, pdf.bounds.bottom + 9 ], width: pdf.bounds.width, align: :right, size: 7.5, color: FAINT_COLOR
  end

  def card_height(rows)
    CARD_PADDING_TOP + CARD_HEADING_HEIGHT + rows * ROW_HEIGHT + CARD_PADDING_BOTTOM
  end

  def column_width
    (pdf_width - MARGIN * 2 - GUTTER) / 2.0
  end

  def content_height
    PDF::Core::PageGeometry::SIZES["A4"].last - MARGIN * 2
  end

  def pdf_width
    PDF::Core::PageGeometry::SIZES["A4"].first
  end

  def day_text
    "#{WEEKDAYS[@now.wday]}, #{@now.day} de #{MONTHS[@now.month - 1]}"
  end

  def totals_text
    items = @groups.sum { |_, names| names.size }
    return EMPTY_TEXT if items.zero?

    "#{items} #{items == 1 ? 'item' : 'itens'} · " \
      "#{@groups.size} #{@groups.size == 1 ? 'categoria' : 'categorias'}"
  end

  # Inter has no emoji: drop any character the font can't draw instead of
  # printing an empty box.
  def printable(text)
    text.each_char.select { |char| pdf.font.glyph_present?(char) }.join.squish
  end
end
