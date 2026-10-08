# Shopping List

Shared shopping list app, for home use, self-hosted. Traditional Rails
(MVC + views), installable as a PWA straight from the browser. Full product
context in `AGENTS.md`.

## Running the Rails server locally

Requirements: Ruby version from `.ruby-version`, SQLite3.

```bash
bin/setup           # bundle install + prepares the database (idempotent)
bin/rails server    # starts the server at http://localhost:3000
```

Other useful commands:

```bash
bin/rails test           # test suite (model/request/controller)
bin/rails test:system    # system tests (Capybara)
bin/rails db:seed        # populates categories and the item dictionary
```

## Testing as a PWA (installing on your phone)

1. Start the server bound to all interfaces so the local network can reach
   it: `bin/rails server -b 0.0.0.0` (or set up Tailscale — see SHO-9/SHO-10).
2. Find the machine's LAN IP (`ip -4 addr`) and open
   `http://<that-ip>:3000` in your phone's browser (Chrome/Android or
   Safari/iOS), on the same Wi-Fi. Real-time sync works from private LAN IPs
   (allowed Action Cable origins in `config/environments/development.rb`).
3. Use "Add to Home Screen" (browser menu). The app opens full-screen, with
   its own icon, like an installed app.

No build step — just open the URL. There's no offline mode (product
decision); the service worker exists only to satisfy the browser's
installability criteria.

**Note:** the service worker only registers on `https:` or `localhost`.
Testing on the same machine works without HTTPS; testing over a local
network IP or Tailscale (`http://`) works for browsing, but the browser may
not offer "Add to Home Screen" until the server has HTTPS (Tailscale solves
this with automatic certificates via `tailscale serve`, see SHO-9/SHO-10).

## Why PWA (and not a native app)

The original idea (SHO-1) was to package the app as a native Android app. We
evaluated two options and dropped both for now:

- **Ruby Native**: paid SaaS (rubynative.com, Starter plan US$299/year),
  requires an account on their platform, and the build/deploy flow is geared
  towards store publishing — incompatible with the "no account, self-hosted"
  decision already made for this project.
- **Hotwire Native**: open source, but requires a full Android toolchain
  (Android Studio, SDK, Gradle, emulator) just to package a WebView.

For the current goal — having something quickly testable with the real user
— a PWA delivers the essentials (own icon, full screen, "installed" on the
phone) with no toolchain at all. A native app stays on the table for the
future, if it makes sense after testing.
