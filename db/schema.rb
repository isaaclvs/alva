# This file is auto-generated from the current state of the database. Instead
# of editing this file, please use the migrations feature of Active Record to
# incrementally modify your database, and then regenerate this schema definition.
#
# This file is the source Rails uses to define your schema when running `bin/rails
# db:schema:load`. When creating a new database, `bin/rails db:schema:load` tends to
# be faster and is potentially less error prone than running all of your
# migrations from scratch. Old migrations may fail to apply correctly if those
# migrations use external dependencies or application code.
#
# It's strongly recommended that you check this file into your version control system.

ActiveRecord::Schema[8.1].define(version: 2026_11_10_000001) do
  create_table "android_devices", force: :cascade do |t|
    t.integer "household_id", null: false
    t.string "name", null: false
    t.string "token_digest", null: false
    t.datetime "revoked_at"
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.string "access_digest", null: false
    t.index ["household_id"], name: "index_android_devices_on_household_id"
    t.index ["token_digest"], name: "index_android_devices_on_token_digest", unique: true
  end

  create_table "catalog_items", force: :cascade do |t|
    t.string "name", null: false
    t.string "normalized_name", null: false
    t.integer "category_id", null: false
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.boolean "learned", default: false, null: false
    t.string "public_id", null: false
    t.index ["category_id"], name: "index_catalog_items_on_category_id"
    t.index ["normalized_name"], name: "index_catalog_items_on_normalized_name", unique: true
    t.index ["public_id"], name: "index_catalog_items_on_public_id", unique: true
  end

  create_table "categories", force: :cascade do |t|
    t.string "name", null: false
    t.integer "position", null: false
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.string "public_id", null: false
    t.index ["name"], name: "index_categories_on_name", unique: true
    t.index ["public_id"], name: "index_categories_on_public_id", unique: true
  end

  create_table "habitual_items", force: :cascade do |t|
    t.integer "household_id", null: false
    t.string "name", null: false
    t.string "normalized_name", null: false
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.string "public_id", null: false
    t.index ["household_id", "normalized_name"], name: "index_habitual_items_on_household_and_normalized_name", unique: true
    t.index ["household_id"], name: "index_habitual_items_on_household_id"
    t.index ["public_id"], name: "index_habitual_items_on_public_id", unique: true
  end

  create_table "households", force: :cascade do |t|
    t.string "name", null: false
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.string "public_id", null: false
    t.integer "sync_revision", default: 0, null: false
    t.index ["public_id"], name: "index_households_on_public_id", unique: true
  end

  create_table "list_items", force: :cascade do |t|
    t.string "name", null: false
    t.integer "category_id"
    t.integer "status", default: 0, null: false
    t.integer "position"
    t.datetime "purchased_at"
    t.integer "household_id", null: false
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.integer "shopping_trip_id"
    t.string "normalized_name", null: false
    t.integer "lock_version", default: 0, null: false
    t.string "public_id", null: false
    t.index ["category_id"], name: "index_list_items_on_category_id"
    t.index ["household_id", "normalized_name"], name: "index_pending_list_items_on_household_and_normalized_name", unique: true, where: "status = 0 AND shopping_trip_id IS NULL"
    t.index ["household_id"], name: "index_list_items_on_household_id"
    t.index ["public_id"], name: "index_list_items_on_public_id", unique: true
    t.index ["shopping_trip_id"], name: "index_list_items_on_shopping_trip_id"
  end

  create_table "shopping_trips", force: :cascade do |t|
    t.integer "household_id", null: false
    t.datetime "finished_at", null: false
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.string "public_id", null: false
    t.index ["household_id"], name: "index_shopping_trips_on_household_id"
    t.index ["public_id"], name: "index_shopping_trips_on_public_id", unique: true
  end

  create_table "sync_scopes", force: :cascade do |t|
    t.string "name", null: false
    t.integer "revision", default: 0, null: false
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.index ["name"], name: "index_sync_scopes_on_name", unique: true
  end

  create_table "sync_tombstones", force: :cascade do |t|
    t.string "record_type", null: false
    t.string "public_id", null: false
    t.string "scope", null: false
    t.integer "household_id"
    t.integer "revision", null: false
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.index ["household_id", "revision"], name: "index_sync_tombstones_on_household_id_and_revision"
    t.index ["household_id"], name: "index_sync_tombstones_on_household_id"
    t.index ["public_id"], name: "index_sync_tombstones_on_public_id", unique: true
  end

  add_foreign_key "android_devices", "households"
  add_foreign_key "catalog_items", "categories"
  add_foreign_key "habitual_items", "households"
  add_foreign_key "list_items", "categories"
  add_foreign_key "list_items", "households"
  add_foreign_key "list_items", "shopping_trips"
  add_foreign_key "shopping_trips", "households"
  add_foreign_key "sync_tombstones", "households"
end
