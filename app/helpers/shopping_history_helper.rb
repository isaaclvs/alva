module ShoppingHistoryHelper
  MONTHS = %w[janeiro fevereiro março abril maio junho julho agosto setembro outubro novembro dezembro].freeze
  WEEKDAYS = %w[domingo segunda terça quarta quinta sexta sábado].freeze

  # "Novembro de 2026": the heading of a page of history.
  def history_month_label(month)
    "#{MONTHS.fetch(month.month - 1).capitalize} de #{month.year}"
  end

  # "Quinta, 12 de novembro · 18:30": when a trip was finished, on the
  # household's clock.
  def history_trip_label(trip)
    at = trip.finished_at.in_time_zone
    "#{WEEKDAYS.fetch(at.wday).capitalize}, #{at.day} de #{MONTHS.fetch(at.month - 1)} · #{at.strftime('%H:%M')}"
  end

  # "1 item" / "3 itens".
  def history_items_label(count)
    "#{count} #{count == 1 ? 'item' : 'itens'}"
  end
end
