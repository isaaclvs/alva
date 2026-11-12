class ListExportsController < ApplicationController
  include ListPdfDownload

  def show
    send_list_pdf(current_household)
  end
end
