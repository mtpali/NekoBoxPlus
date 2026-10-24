# VPN for Android

Android VPN client based on NekoBox+ with MobileTina additions.

- Application name: `VPN`
- Application ID: `com.nb4a.mobiletina`
- Supported ABIs: `armeabi-v7a`, `arm64-v8a`
- Auto/Manual swipe navigation, pull-to-refresh, scheduled subscription updates and a compact drawer are included.
- Upstream protocols and menu destinations are retained. The requested UI exceptions are the original About screen, alternate launcher icons/icon selector, translated languages, and branded startup artwork.
- The default interface uses the owner's Exclave font and a plain light/dark palette. Per-app routing starts disabled; WhatsApp, Instagram, Telegram, Chrome and Google Search are prioritized when installed.
- AmneziaWG is supplied by the original NekoBox+ core.

The complete upstream source is pinned as the `upstream` Git submodule. Custom source files live in `overlay`, changes to existing files in `patches/app.patch`, requested file removals in `SOURCE_REMOVALS.json`, and build adaptation scripts in `scripts`. Run `git clone --recurse-submodules` to obtain both.

## Build

The native workflow builds the original patched Go/Rust core for both ARM ABIs. APK compilation and validation run separately so UI changes do not repeatedly rebuild the unchanged core. A successful build and a device traffic test are separate checks.

Upstream: https://github.com/IranTSR/NekoBoxPlus
Original project: https://4pda.to/forum/index.php?showtopic=1121122

See [DEVELOPMENT.md](DEVELOPMENT.md) for the feature contract, exact build commands and signing workflow.

## License

The upstream GPL-3.0 license and third-party notices remain in the submodule. These customizations are also GPL-3.0-or-later.
