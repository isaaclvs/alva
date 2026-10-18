class ListExportsController < ApplicationController
  def show
    send_data ListPdf.call(current_household, date: Date.current),
      type: "application/pdf",
      disposition: "attachment",
      filename: "lista-#{Date.current.iso8601}.pdf"
  end
end
