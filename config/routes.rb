Rails.application.routes.draw do
  mount ->(env) { SyncCable::Server.instance.call(env) }, at: "/api/v1/cable"
  # Define your application routes per the DSL in https://guides.rubyonrails.org/routing.html

  # Reveal health status on /up that returns 200 if the app boots with no exceptions, otherwise 500.
  # Can be used by load balancers and uptime monitors to verify that the app is live.
  get "up" => "rails/health#show", as: :rails_health_check

  # Render dynamic PWA files from app/views/pwa/* (remember to link manifest in application.html.erb)
  get "manifest" => "rails/pwa#manifest", as: :pwa_manifest
  get "service-worker" => "rails/pwa#service_worker", as: :pwa_service_worker

  get "entrar", to: "sessions#new", as: :login
  post "entrar", to: "sessions#create"
  delete "sair", to: "sessions#destroy", as: :logout

  post "api/android/devices", to: "android_devices#create"
  post "api/quick_add", to: "quick_add#create"

  namespace :api do
    namespace :v1 do
      # Explicit sync enrollment; the other protected sync endpoints come with their tasks.
      resources :devices, only: :create
      # Idempotent batch of commands; JSON only like the snapshot.
      post "commands", to: "commands#create", format: false, defaults: { format: :json }
      # JSON only; a format suffix is not a way to ask for anything else.
      get "snapshot", to: "snapshots#show", format: false, defaults: { format: :json }
      # The PWA's /lista.pdf for a device credential: same PDF, bearer instead of cookie.
      get "list.pdf", to: "list_exports#show", format: false
      # Read-only shopping history, one month per page; additive to v1.
      get "history", to: "history#index", format: false, defaults: { format: :json }
    end
  end

  # Defines the root path route ("/")
  resources :list_items, only: [ :show, :create, :edit, :update, :destroy ] do
    patch :toggle, on: :member
    post :undo_purchase, on: :member
    post :restore, on: :collection
  end
  resources :shopping_trips, only: [ :create, :destroy ]

  # Read-only shopping history ("Compras"), one page per month, and the one
  # action on it: putting a finished trip's names back on the list.
  get "compras", to: "shopping_history#index", as: :shopping_history
  post "compras/:id/adicionar", to: "shopping_history#add_all", as: :shopping_history_add_all

  resources :habitual_items, only: [ :create, :destroy ] do
    post :add_selected, on: :collection
    post :add_all, on: :collection
  end

  # GET /lista.pdf: shopping list download for printing.
  get "lista.pdf", to: "list_exports#show", as: :list_export, format: false

  root "list_items#index"
end
