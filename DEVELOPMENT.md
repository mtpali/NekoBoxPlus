# Current release: 1.4.2-106 / فیلترشکن

The 2026-10-26 user change log and both main@7380499 and the draft PR branch@7757ccd were reviewed before editing. Preserve startup camera/notification permission requests, concise subscription-import errors, fifteen-tap QR access, the white notification stop button with black text, and the unchanged pinned native core.

Expiration now reads the live background service's root profile and selector group instead of relying on the last-used profile preference. It cancels pending startup/recovery, directly stops the affected service on Main and observes its actual teardown before retirement. A delayed service start rechecks expiry. Retirement retains the connected profile ID when it belongs to the expired group, replaces its name with «اشتراک شما منقضی شد», and does not alter other groups. Expiry posts the same Persian message on a separate notification channel; disabled notification permissions cannot retry an already committed retirement. Notification taps open the application.

The subscription Group Settings toolbar has a QR icon beside delete. It uses the existing QR dialog with duplicate/state-saved guards and preserves protected-source restrictions. Both the legacy home-screen toggle shortcut and the Quick Settings tile are labelled VPN and use the original launcher artwork. Tile updates no longer replace the label or icon with a profile name/status glyph. No new AppWidget provider is introduced.

VersionCode is 465 (93 × 5). The deliverable uses the available original v2 signing key/certificate from the delivered 104 APKs (05d8ab…); the later source-only signer pin 9e5062… did not have a matching private key available. No new signing key is generated. Builds also run on fix/notification-expiry-qr-widget so the draft PR can be validated without merging main.

Required checks: clean source preparation twice, full existing audits, the complete JVM suite including live-service scope and profile-ID/name regressions, Debug and minified Release builds, native guard, actual APK/R8/JSON/artwork/signature validation. No device/emulator or real VPN scenario is claimed from CI alone. Historical sections below do not override this release.

---

# Historical release: 1.4.2-105 / فیلترشکن

Application ID and v2 signing certificate remain unchanged. Android versionCode is 460 (92 × 5); the launcher and network core stay pinned as in104.

Expiry metadata is now registered for ordinary clipboard, text file, ZIP, external URI/config-profile and gallery imports, in addition to subscription updates and camera/protected imports. JSON arrays use the earliest valid root `_comment`; invalid documents cannot cancel a previous deadline. Existing custom JSON profiles missing a schedule are recovered once. The reported `2026-10-25T16:47:00` means `2026-10-25T13:17:00Z` (Asia/Tehran). Foreground recovery is re-entrant after completion rather than disabled for the lifetime of the process.

The user explicitly requested an internet clock independent of the device date. HTTPS Date is fetched from GitHub API first, GitHub web second and Cloudflare as fallback, with cache-busting, no redirects, TLS verification and stale Age rejection. Time advances with elapsedRealtime, validated against boot/process identity. There is no device-wall-clock fallback for expiry decisions or remaining-day display; before verification the UI shows pending internet time. The foreground clock sync is asynchronous and guarded; failed network fetches retain the shared cooldown. An expected verification deferral finishes the active verification and schedules one lightweight 15-minute retry request, so foreground/new-clock recovery can enqueue a fresh verification immediately instead of remaining stuck behind WorkManager backoff. Running retirement work is never replaced by recovery. Scheduler105 uses fresh unique work names and an explicit input version; queued104/older work is inert after upgrade and old matching tags are cancelled, avoiding inherited retry/backoff delays. Enqueue failures still use exponential WorkManager retry. Offline/Doze/force-stop/exact-alarm restrictions can defer execution; no unverified device timestamp authorizes deletion.

MainActivity.onResume already called the activity-scoped model in104. Release105 starts foreground subscription refresh before cancellable UI metadata loading, so service/database refresh callbacks cannot silently discard the request. The Exclave reference uses the same due-subscription updater, network checks and 60-second foreground guard. Auto/Manual share this Activity path.

Explicit source removals: Tools and all its standalone network/backup screens and implementation (including Git/WebDAV and bandwidth-tool IPC); logs viewer; sing-box dashboard/WebView/assets and external web UI delivery; ICMP ping/controller/menu/notification/progress infrastructure; alternate quick toolbar/layout editor; country badges/renderer/SVG assets; compact statistics implementation/layout; legacy view, settings-bottom-bar, direct-speed/group-name/persistent-notification and always-address preference gates. Internal core selector/traffic APIs, protocol support, crash diagnostics and database serialization remain compatible. Old preference keys are no longer read. SOURCE_REMOVALS.json lists the removed files, canonical existing-file edits remain in app.patch, and new resources/classes are in overlay.

The VPN status notification is visible and ongoing, with a dedicated low-importance channel. Its single explicit stop button exists in both collapsed and expanded custom content, independently of wake lock and saved notification preferences. Android/user permission and notification channel controls still apply.

The tab double-tap Go to navigator mode is removed. Ten consecutive short taps on a subscription tab show its QR directly when a link exists and sharing is allowed; #Base64/raw-GitHub protection cannot be bypassed. Existing copy/move group picking remains. Requested menu labels and successful-import text are Persian.

Required validation: clean pinned source preparation twice, source/XML/manifest/feature-removal audits, native guard checks, all JVM tests, Debug/Release build, actual R8/DEX JSON contract and signed ARM APK checks. Device/emulator tests have not been performed; a successful build does not by itself prove that the reported device problem is fixed. The historical sections below do not override this release.

---

# Current release: 1.4.2-104 / فیلترشکن

The latest user request supersedes the earlier launcher image/name. Application ID and v2 release certificate stay unchanged. Android versionCode is 455 (base 91 × 5). The exact supplied icon(1).png is copied without raster edits. Adaptive foreground inset is 18%; all dark logo pixels fit inside the 33dp safe circle (maximum radius 30.9981dp).

QR camera/gallery payloads are handed to a resumed MainActivity, imported without subscription/profile confirmation and then return to the configuration screen. Routing/theme special links retain their dedicated flows. Protected #Base64 imports and exact HTTPS raw.githubusercontent.com subscription origins hide editing, sharing and exports; ordinary sources retain them. Origin checks reject lookalike domains and credentials. GroupManager and RawUpdater persist the flag, while dynamic UI guards protect existing imports immediately.

Only the toggle launcher shortcut remains; its label and the quick-settings tile label are MobileTina. Enable/disable shortcut activities are removed from the manifest. The scanner remains available inside the application.

Expiry was compared with mtpali/exclave@6a4169e3a5951549ba1960ca95e9c4ca873fd772 and mtpali/MobileTinaVPN@670b89531d274376da9af9c1fc58fc3be8152817. Version103 had parallel foreground Handler, alarm and worker execution, repeated scheduling and cross-database reads inside the profile transaction. Version104 has a single revision-specific verification stream, an alarm and one lightweight fallback; the fallback only requests verification. No foreground timer/polling exists. Network constraints apply if trusted time is missing; exponential retry starts at 15 minutes. All groups share a persisted 15-minute failed-clock cooldown, instead of repeating network time requests for every group. A monotonic trusted clock schedules future deadlines; timezone-less comments use Asia/Tehran. Legacy work is cancelled once and legacy worker requests without a revision are inert. The expiry receiver catches recoverable failures and only enqueues work. A short idempotent profile transaction does not read PublicDatabase or call UI/service/network code. Renewal guards compare source digest and lastUpdated; removal of the expiry record compares its full revision. Completed work cannot recreate the expired marker. Only the affected group is retired. Verification runs in the service-owning :bg process and observes real service teardown rather than binding/starting the service for every expiry.

Integrity checks still validate the signer, manifest, native guard and protected artwork/text, once per process. Installed APK replacement restarts the process. Rehashing all artwork on every activity resume and a 37-second main-thread timer was removed. Encoded social content, encrypted original artwork, masked signer pin and R8 stay enabled; the public label change updates the JVM/native branding digest. Images use an 8MiB LRU cache and home rendering changes the bitmap only when its visual state changes.

The automatic screen now orders status → ping → server name. It probes the running connection once after connection/profile change, including custom JSON and single-profile imports; no manual group speed test is required. The ping row remains available while checking and allows manual retry. This is not a recurring network poll. Apps VPN default Off and selectable Light/Dark/System (OLED dark) remain as in103.

Validation must include pinned patch re-application, XML/shortcut/launcher checks, native guard tests, Debug+Release compilation, all JVM tests, real R8 DEX/JSON-field verification and signed ARM APK verification. New retirement tests replay expiration 200 times, preserve unrelated groups, reject renewed source/revision and retain raw-GitHub protection. Network-time cooldown tests cover 1000 competing attempts and reboot/reset boundaries. No device/emulator, camera, actual network speed or battery/ANR measurement was performed; the reported device freeze cannot be declared reproduced or conclusively fixed without that evidence. Android Doze/force-stop, absent trusted time/network and exact-alarm permissions may delay expiration. Client-side obfuscation is tamper resistance, not absolute uneditability.

The sections below are historical and can contain defaults superseded by this release.

---

# راهنمای توسعهٔ VPN

هویت این برنامه `VPN` با application ID برابر `com.nb4a.mobiletina` است. پایه، NekoBox+ نسخهٔ `1.4.2-97` در commit ثبت‌شده در `UPSTREAM_COMMIT` است. هستهٔ موجود AmneziaWG دوباره پیاده‌سازی نشده است.

## قرارداد جاری نسخهٔ 1.4.2-102

کد انتشار واقعی `445` است. این بخش بر پیش‌فرض‌های تاریخی پایین مقدم است. امضا همان کلید v2 نسخهٔ 100 است؛ کلید تازه ساخته نمی‌شود. تم تابع گوشی است: روز Light و شب OLED مشکی خالص.

- منوی اصلی، به‌روزرسانی/تست/انتخاب اشتراک را بدون آیکون نشان می‌دهد؛ Show active، All to proxy و میانبر ابزارها از این منو کنار رفته‌اند. «حذف کانفیگ ها» زیر تست سرعت، فقط کانفیگ‌های گروه فعلی را با حفظ لینک پاک می‌کند. حذف تکراری/غیرفعال/ناامن مستقیماً از DB می‌خواند، نتیجهٔ خالی را اعلام و حذف را با listener اصلی همگام می‌کند. در حذف کانفیگ فعال، توقف واقعی سرویس تأیید می‌شود.
- حذف هر اشتراک کنار مداد همان ردیف است. GroupManager حذف گروه و تمام کانفیگ‌هایش را انجام می‌دهد؛ Undo اصلی حفظ است.
- آخرین اصلاح کاربر: تفکیک برنامه‌ها پیش‌فرض **Proxy** است؛ فقط پنج بستهٔ رسمی WhatsApp/Instagram/Telegram/Chrome/Google Search در صورت نصب انتخاب می‌شوند. مهاجرت یک‌باره است؛ تغییر بعدی کاربر حفظ می‌شود. Off دستی از صفحه خارج نمی‌شود. منطقهٔ پیش‌فرض ایران و رتبه‌بندی قبلی برنامه‌ها حفظ‌اند.
- پینگ ردیف‌ها بدون ms و شکست تست با «غیرفعال» نمایش داده می‌شود؛ جزئیات خطای اصلی در داده/صفحهٔ جزئیات باقی است. آمار مصرف هر پروفایل فعال و فاصلهٔ به‌روزرسانی ۱۰۰۰ میلی‌ثانیه است؛ از شمارنده‌های واقعی هسته استفاده می‌شود.
- پیام‌های به‌روزرسانی، انتخاب کانفیگ، تعداد حذف و تأییدهای اصلی حذف/پاک‌سازی/جایگزینی/واردکردن فارسی‌اند. Cancel «بیخیال» و دکمه‌های تأیید متعلق به برنامه فارسی‌اند.
- `#Base64` در Clipboard، فایل/ZIP، QR و ورود لینک تشخیص داده می‌شود. فقط پیشوند # همراه با Base64 معتبر UTF-8، نشان حفاظت است؛ Base64 معمولی، URL عادی و comment متنی حفاظت نمی‌گیرند. URL و query پس از decode حفظ می‌شوند؛ پروکسی HTTP دارای اعتبارنامه همچنان پروکسی است. payload محلی محافظت‌شده در گروه جدا قرار می‌گیرد.
- `SubscriptionBean.protectedContent` در BLOB نسخهٔ ۹ ذخیره می‌شود؛ رکورد قدیمی نسخهٔ ۸ عادی باقی است. حفاظت در refresh و backup roundtrip حفظ می‌شود. ویرایش گروه/پروفایل، QR/کپی/فایل کانفیگ، لینک/خروجی اشتراک و انتقال/خروجی دسته‌ای برای همین گروه مخفی یا رد می‌شوند. اتصال، تست، به‌روزرسانی و حذف باقی‌اند؛ گروه‌های عادی تغییری ندارند.
- کد بلندِ fragment عنوان پاک می‌شود و عنوان انسانی حفظ است. نام عمومی اشتراک همان `instagram : mobile.tina` و Ungrouped برابر VPN است.
- نشانگر پایان، SOCKS با آدرس 1 و پورت 1 بدون نام کاربری/رمز است. فقط گروه منبع بازنشسته، لینک آن حذف و یک نشانگر نگه داشته می‌شود؛ گروه‌های دیگر پاک نمی‌شوند. JSON دارای outbounds و `_comment` معتبر زمان‌بندی می‌شود. `2026-09-06T11:23:00` بدون offset به وقت Asia/Tehran، برابر `2026-09-06T07:53:00Z` است. offset صریح محترم است؛ تاریخ نامعتبر timer سالم قبلی را حذف نمی‌کند.
- انقضا از زمان آنلاین تأییدشده و elapsedRealtime استفاده می‌کند. تایمر داخل فرایند، AlarmManager (دقیق اگر مجوز آن در Android فراهم باشد)، و WorkManager پشتیبان همزمان‌اند؛ در راه‌اندازی دوباره recovery انجام می‌شود. در خواب/force-stop/نبود مجوز exact alarm، اجرای دقیقِ پس‌زمینه تضمین نمی‌شود. برنامه تنظیمات باتری یا مجوز آلارم را خودکار باز نمی‌کند.

## حفاظت انتشار نسخهٔ 102

`dontobfuscate` و keep سراسری برنامه حذف شده‌اند. R8 نام کلاس‌ها و متدها را تغییر می‌دهد؛ JSON هسته، مدل DB/backup، JNI و Worker قرارداد ثابت دارند تا Release اتصال را خراب نکند. mapping واقعی، نام‌گذاری کلاس‌های برند و ثابت‌بودن فیلدهای JSON کنترل می‌شوند.

عنوان‌ها/لینک‌های برند و متن ورود encoded هستند. پنج تصویر شامل هدر کشو با AES-256-CTR در حافظه باز می‌شوند و بایت اصلی حفظ است. `BRANDING_PROTECTION.json`، digestهای Kotlin/C++ و `libv.so` قرارداد واحد دارند. guard اقتباس‌شده از Exclave گواهی واقعی نصب، manifest، نسخه، متن‌ها و ciphertextها را هنگام شروع، resume و هر ۳۷ ثانیه بررسی و proof دارای nonce تولید می‌کند. pin گواهی در DEX و native با mask متفاوت ثبت می‌شود. TracerPid مرجع منتقل نشده است.

این روش ویرایش معمول منابع و امضای مجدد را رد می‌کند؛ APK سمت کاربر را غیرقابل‌ویرایش یا محتوای قابل نمایش را غیرقابل‌استخراج نمی‌کند. آیکون Launcher به‌دلیل نیاز Android منبع معمولی است. حفاظت اشتراک، محدودیت داخل برنامه است و جایگزین کنترل سمت ارائه‌دهنده نیست.

بررسی‌ها: `scripts/branding-contract.py` تطابق منابع و digest؛ `scripts/test-native-guard.py` پنج بردار native روی میزبان JDK/C++؛ `scripts/verify-release-protection.py` DEX/ELF/mapping واقعی؛ آزمون‌های JVM برای #Base64، BLOB قدیمی/جدید، حفظ حفاظت در refresh، زمان دقیق تهران و بازیابی تصاویر. CMake برابر 3.22.1 و NDK guard برابر 27.2.12479018 است؛ هستهٔ شبکهٔ پین‌شده عوض نشده است. آزمون واقعی نصب، دوربین، خواب گوشی و ترافیک در این محیط انجام نشده است.

## دریافت سورس

```bash
git clone --recurse-submodules https://github.com/mtpali/NekoBoxPlus.git
cd NekoBoxPlus
python3 scripts/prepare-app.py
```

سورس کامل پایه در submodule پوشهٔ `upstream`، تغییرات فایل‌های قبلی در `patches/app.patch`، حذف‌های مجاز در `SOURCE_REMOVALS.json` و فایل‌های اختصاصی در `overlay` قرار دارند. صفحهٔ اصلی جدید بر معماری خود NekoBox+ بنا شده است؛ سورس مخزن‌های مرجع MobileTinaVPN و Exclave تغییر نکرده است.

## تغییرهای نسخهٔ 1.4.2-101

کد انتشار `440` است و با همان کلید جدید نسخهٔ 100 ساخته می‌شود.

- کارت حجم/زمان از XML مرجع Exclave منتقل شد: عنوان، نوار پیشرفت ۶dp و یک ردیف مشترک زمان و مصرف؛ padding و حاشیه‌ها همان مرجع‌اند. ردیف‌های اضافی و رقم بزرگ نسخهٔ 100 برداشته شدند.
- اسلاید افقی Auto/Manual باقی است؛ pull-to-refresh فقط در Manual فعال است. تصویر اصلی کشو با اندازهٔ ۱۶۸dp و حالت Fit مرجع نمایش داده می‌شود.
- حالت نمایش همیشه تابع گوشی است: Dark Mode گوشی، OLED با پس‌زمینهٔ مشکی؛ حالت دیگر، Light. حالت اجباری ذخیره‌شده به System مهاجرت می‌کند و گزینهٔ حالت شب برای نمایش سیاست System باقی می‌ماند ولی قابل تغییر دستی نیست. پیش‌تنظیم AMOLED/Black هم در روز، تم روشن دارد.
- درخواست خودکار معافیت باتری از مسیر اتصال حذف شد؛ مجوز اصلی VPN و مجوز ضروری شبکهٔ محلی مستقل‌اند.
- اعلان اتصال فقط دکمهٔ «خاموش کردن فیلترشکن» دارد؛ دکمه‌های اضافی اعلان تست نیز حذف‌اند و لمس اعلان، بازگشت به تست را حفظ می‌کند.
- شش مقصد قبلی (پروفایل‌ها، مسیریابی، مسدودسازی تبلیغات، ابزارها، لاگ و داشبورد) در انتهای Settings قرار دارند و بازگشت به Settings انجام می‌شود. میانبرهای اصلی کشو حفظ شده‌اند.
- دیالوگ Diff و تنظیم بی‌اثر آن حذف شدند؛ نتیجهٔ به‌روزرسانی با snackbar نشان داده می‌شود.
- Cancel در رابط متعلق به برنامه «بیخیال» است؛ no_proxies_found «هیچ کانفیگی وجود ندارد» است. نام پیش‌فرض اشتراک و گروه عادی به‌ترتیب `instagram : mobile.tina` و `VPN` هستند؛ نام سفارشی حفظ می‌شود.
- لینک اشتراک خام، پیش از ورود به مسیر واردکردن، به قالب مناسب تبدیل می‌شود. QR از v2rayNG و wrapperهای موجود پشتیبانی می‌کند؛ QR پروکسی HTTP دارای نام کاربری همچنان پروکسی است.
- به‌روزرسانی خودکار قبلی حفظ شده و سه مسیر ایجاد اشتراک که به‌اشتباه autoUpdate=false داشتند، اصلاح شدند. انتخاب اشتراک‌های موجود تغییر داده نمی‌شود.
- کنترلر، پیاده‌سازی و ورودی‌های TCPing از برنامه حذف شدند. قابلیت تشخیص TCP برای fragmentation با نام supportsTcpTransport حفظ شده تا پروتکل‌ها تغییر نکنند. دادهٔ قدیمی دکمهٔ حذف‌شده از چینش ابزارها کنار گذاشته می‌شود.
- تست گروهی URL بدون دیالوگ اجرا می‌شود؛ اجرای دوبارهٔ دکمه حین همان تست، آن را لغو می‌کند. تست تکی قبلاً هم بدون دیالوگ بود. ICMP و ابزار سنجش پهنای باند مستقل باقی‌اند.

## تغییرهای نسخهٔ 1.4.2-100

نسخهٔ جدید کد `435` دارد. همهٔ تغییرها در patch و overlay ثبت می‌شوند و هستهٔ شبکه همان پایهٔ پین‌شده است.

- منطقهٔ نصب تازه بدون نمایش انتخابگر روی ایران است. آیکون یک پله بزرگ‌تر است: inset تطبیقی ۲۱٪ و قدیمی ۹٪.
- لینک سه پیج اینستاگرام در دیالوگ ورود لمس‌پذیر است و با fallback مرورگر باز می‌شود. متن اصلی و بررسی صحت آن ثابت‌اند.
- به‌روزرسانی اشتراک مانند Exclave در پس‌زمینه با شرط وجود شبکه و retry اجرا می‌شود؛ هنگام بازگشت به برنامه نیز اشتراک‌های موعدرسیده بررسی می‌شوند. پیش‌فرض ۶۰دقیقه و حداقل دورهٔ پس‌زمینه ۱۵دقیقه است. مهاجرت فعال‌سازی تنها یک‌بار اجرا می‌شود؛ انتخاب بعدی کاربر حفظ می‌شود.
- جابه‌جایی افقی Auto/Manual و تازه‌سازی عمودی هر دو صفحه اضافه شد. ژست لبهٔ سیستم و حالت انتخاب/جستجو محدودیت‌های خود را حفظ می‌کنند.
- ردیف‌های اصلی NekoBox با دکمه‌های آشکار ویرایش، تست، اشتراک‌گذاری و حذف بازگشته‌اند. QR و Clipboard مستقیماً فرمت پشتیبانی‌شده را صادر می‌کنند؛ منوی انتخاب SN link برداشته شده و پروتکل فاقد لینک استاندارد فرمت سازگار خود را حفظ می‌کند.
- گزینهٔ تکراری Add configuration در منوی اصلی حذف است؛ افزودن از هدر و همهٔ پروتکل‌های Manual Settings در دسترس‌اند. والد یک زیرمنو دیگر ناوبری اجرا نمی‌کند؛ عملیات پس از آماده‌شدن نمای صفحهٔ دستی اجرا می‌شود.
- ترتیب سه گزینهٔ اول: به‌روزرسانی اشتراک، تست سرعت، انتخاب اشتراک. ترتیب اولیهٔ گروه‌ها By delay است؛ مهاجرت ترتیب اصلی یک‌بار اجرا می‌شود.
- کشو ۲۸۸dp و دارای جداکننده بین گزینه‌هاست: صفحهٔ اصلی، اشتراک‌ها، تنظیمات، تفکیک برنامه‌ها، درباره موبایل تینا، پاکسازی کامل. ابزارهای قبلی کشو در زیرمنوی Tools هدر باقی‌اند. صفحهٔ اصلی در Manual هم انتخاب‌شده دیده می‌شود.
- پاکسازی تنها پس از تأیید، توقف سرویس و لغو به‌روزرسانی‌ها اجرا می‌شود؛ زمان‌بندی‌های انقضا نیز با قفل مشترک لغو می‌شوند؛ سرورها/اشتراک‌ها در یک تراکنش حذف و گروه خالی معتبر ساخته می‌شود. تنظیمات نگه داشته می‌شوند.
- کارت حجم/انقضای تکراری Manual حذف است؛ محتوای اطلاعیه و لینک‌های پشتیبانی اصلی حفظ شده‌اند. کارت Auto حجم باقی‌مانده و برند `instagram : mobile.tina` را نمایش می‌دهد.
- ردیف اختصاصی قدیمی، چیدمان‌های بی‌استفاده و ۲۴ متن سفارشی بدون ارجاع حذف شدند. مجوزها، هسته‌ها و ابزارهای کاربردی حفظ‌اند.
- نتیجهٔ ساخت/آزمون و امضا باید از اجرای همین نسخه گزارش شود؛ اجرای دستگاه هنوز انجام نشده است.

## رابط مرجع نسخهٔ 1.4.2-99

نسخهٔ مرجع قبلی `1.4.2-99` با versionCode برابر `430` است. مرجع ظاهر همان شاخهٔ `dev-mobiletina-amneziawg-20260904` از `mtpali/exclave` در commit `6a4169e3a5951549ba1960ca95e9c4ca873fd772` است.

- دیالوگ Welcome to VPN قبلی حذف شد. کلاس واقعی `MobileTinaFirstLaunchDialog` و متن و digest آن از مرجع منتقل شدند؛ رنگ مشکی، نوار گرادیانی اینستاگرام، کارت‌ها و دکمهٔ صورتی همان مرجع هستند. فونت با فونت اصلی Exclave یکسان است. کلید مستقل `mobileTinaExclaveWelcomeShownV1` باعث نمایش یک‌بارهٔ دیالوگ صحیح در ارتقای نسخهٔ قبل هم می‌شود.
- Auto از XML واقعی Exclave با تصویر ۲۳۶dp، فاصله‌ها، وضعیت، پینگ قابل لمس و کارت اشتراک استفاده می‌کند. اتصال و انقضا همچنان بر ViewModel و callback واقعی سرویس همین NekoBox تکیه دارند.
- هدر بومی ۱۵۰dp شامل اسکن، افزودن، بیشتر و دکمه‌های حالت دستی/خودکار در هر دو حالت مشترک است. افزودن متنی، Clipboard، فایل و تمام پروتکل‌های موجود از منوی افزودن در دسترس‌اند. به‌روزرسانی، جستجو، گروه‌ها، تست‌ها، ترتیب، چیدمان، انتخاب دسته‌ای و سایر عملیات از منوها حذف نشده‌اند.
- نوار پایین Manual از تصاویر اصلی ۸۹dp اتصال و ۱۴۳×۴۷dp اتصال خودکار مرجع استفاده می‌کند. تصاویر Drawer، اتصال و دکمهٔ خودکار بدون re-encode منتقل شده‌اند؛ هش‌های آن‌ها در `EXCLAVE_UI_ASSETS_SHA256.json` ثبت و داخل APK بررسی می‌شوند.
- ردیف تک‌ستونهٔ پیش‌فرض از XML فشردهٔ خود Exclave استفاده می‌کند. منوی سه‌نقطهٔ هر ردیف، ویرایش، تست، تمام شیوه‌های QR/کپی/خروجی کانفیگ و حذف را نگه می‌دارد. انتخاب دسته‌ای، حالت‌های دو‌ستونه/فشرده و رابط Legacy اصلی همچنان موجودند. در TV، رابط و فوکوس اصلی حفظ می‌شوند.
- About از layout و styleهای بومی خود Exclave استفاده می‌کند. سربرگ تصویری Drawer نیز فایل واقعی همان مرجع است؛ مقصدهای اصلی NekoBox در Drawer باقی‌اند.
- حاشیهٔ آیکون adaptive از ۲۴٪ به ۲۲٪ و حالت قدیمی از ۱۲٪ به ۱۰٪ تغییر کرد؛ این تغییر، دو پله بزرگ‌ترشدن آیکون است.
- ترجمه‌های چندزبانه دوباره اضافه نشده‌اند. برچسب‌های فارسی ثابتِ اختصاصی رابط، مطابق مرجع Exclave منتقل شده‌اند؛ تنظیمات و صفحات عمومیِ باقی‌مانده همان منابع پیش‌فرض انگلیسی را دارند.
- پنل دستی برای نوار کنترل و navigation bar فضای واقعی رزرو می‌کند؛ هدر هم status bar و cutout را در نظر می‌گیرد. جستجو و انتخاب دسته‌ای، toolbar کامل اصلی را موقتاً در دسترس قرار می‌دهند.

## سابقهٔ اصلاح رابط نسخهٔ 1.4.2-98

- این نسخه کد انتشار `425` دارد و برای نصب به‌صورت به‌روزرسانی با کلید ثابت قبلی امضا می‌شود.
- فونت واقعی `text.ttf` از Exclave، با همان بایت‌ها و هش ثبت‌شده در `REFERENCE_EXCLAVE_FONT_SHA256`، هم در Compose و هم در تم‌های XML و دیالوگ‌ها استفاده می‌شود.
- تم Simple، پالت خنثی روشن/تیرهٔ مرجع را با Material 3 همین برنامه سازگار می‌کند. تمام تم‌ها و حالت‌های Light/Dark/System اصلی همچنان قابل انتخاب‌اند. در ارتقا، تم آبی پیشین به Simple منتقل می‌شود؛ تم‌های دیگر حفظ می‌شوند.
- Applications در نصب تازه خاموش است. مهاجرت یک‌بارهٔ همین نسخه، فعال‌سازی اجباری نسخهٔ پیشین را خاموش می‌کند و فهرست انتخاب‌شده را نگه می‌دارد؛ انتخاب‌های بعدی کاربر بازنویسی نمی‌شوند.
- پنج برنامهٔ WhatsApp، Instagram، Telegram، Chrome و Google Search در صورت نصب، به همین ترتیب در ابتدا هستند. نسخه‌های سیستمی Chrome و Google Search هم بدون نمایش سایر برنامه‌های سیستمی قابل مشاهده‌اند و جستجو همچنان اعمال می‌شود.
- تصویر اسپلش قدیمی و منابع آن حذف شده‌اند. پیش‌نمایش سفارشی شروع غیرفعال و تصویر گذار Android 12+ شفاف است؛ خود گذار سیستمی سیستم‌عامل تحت کنترل برنامه نیست.
- آیکون اصلی با حاشیهٔ ۲۴٪ در foreground آیکون adaptive و ۱۲٪ در حالت قدیمی استفاده می‌شود. تمام آیکون‌ها و aliasهای جایگزین و Change Icon حذف‌اند. receiver مخصوص ارتقا، alias ثابت اصلی را برای نصب‌هایی که قبلاً آیکون دیگری انتخاب کرده‌اند دوباره فعال می‌کند.
- About قبلی حذف و محتوای کامل بخش دربارهٔ Exclave، شامل سه لینک اینستاگرام، لینک تلگرام سازنده و دو نشانی فروشگاه، در Compose منتقل شده است. دارایی LICENSE و اطلاعیه‌های مجوز حذف نشده‌اند.
- تنها زبان عمومی انگلیسی باقی است؛ ترجمه‌های دیگر، انتخاب‌های آن‌ها و منابع زبانی وابستگی‌ها از بستهٔ انتشار خارج می‌شوند. صفحهٔ اختصاصی درباره، طبق مرجع، متن فارسی و جهت RTL دارد.
- حذف‌های این بخش دستور صریح کاربر هستند و بر قرارداد حفظ همهٔ اجزای ظاهری در تحویل قبلی مقدم‌اند. هیچ پروتکل، دکمهٔ اتصال/ویرایش/تست/اشتراک‌گذاری، گروه، ابزار یا تنظیم اتصال دیگری برای این تغییر حذف نشده است.

## قابلیت‌ها و قراردادها

- Auto صفحهٔ آغاز است؛ Manual و منوی کناری، رابط اصلی، تمام پروتکل‌ها، تنظیمات، ویرایش، اشتراک‌گذاری و ابزارهای NekoBox+ را در دسترس نگه می‌دارند.
- چهار تصویر ارسالی بدون تغییر بایت‌های WebP، در منابع رمز‌شده نگهداری و هنگام نمایش رمزگشایی می‌شوند. White خاموش، Yellow در حال تست/اتصال، Blue متصل و Red خطا/انقضا است. آیکون ارسالی تنها آیکون برنامه است.
- انتخاب خودکار از پینگ جدید همان تلاش استفاده می‌کند؛ حداکثر ۲۴ نامزد در دسته‌های شش‌تایی، با مقصد آزمایش ثانویه و تلاش مجدد محدود بررسی می‌شوند. برای تک‌پروفایل تست دسته‌ای اجرا نمی‌شود. قابلیت‌هایی که تست URL آن‌ها در هستهٔ اصلی پشتیبانی نمی‌شود، از مسیر خود آن هسته استفاده می‌کنند.
- لغو تلاش، token قبلی و پنجرهٔ در حال انتظار مجوز VPN را باطل می‌کند. تأیید اتصال از وضعیت سرویس می‌آید؛ نمایش رنگ آبی از یک flag ذخیره‌شده ساخته نمی‌شود.
- کارت اشتراک، حجم و تاریخ موجود در دادهٔ واقعی را نشان می‌دهد. حجم صفر، حجم نامحدود، نبود اطلاعات و overflow عددی جداگانه مدیریت می‌شوند. ده لمس متوالی کارت، QR/کپی لینک همان اشتراک را باز می‌کند.
- واردکردن کانفیگ، متن Base64، wrapperهای اشتراک و لینک مستقیم افزوده شده است؛ مسیرهای اصلی اسکن/واردکردن/ویرایش باقی‌اند. اشتراک‌های تازه، پیش‌فرض به‌روزرسانی خودکار ۶۰دقیقه‌ای دارند؛ تنظیم قبلی کاربر تغییر داده نمی‌شود.
- انقضای JSON با `_comment` دارای timestamp و نشانگر SOCKS `1:1` پشتیبانی می‌شود. پاک‌سازی به همان گروه محدود است؛ انقضای گروه فعال پس از دریافت وضعیت توقف واقعی سرویس اعمال می‌شود. پاک‌سازی بر اساس تاریخ گوشیِ تأییدنشده انجام نمی‌شود. payload ناقص یا تاریخ نامعتبر، timer معتبر قبلی را حذف نمی‌کند.
- نشست پیوستهٔ اتصال با AlarmManager و زمان monotonic به ۲۴ ساعت محدود می‌شود. Android در نبود مجوز exact alarm می‌تواند اجرای fallback را به تأخیر بیندازد.
- گزینهٔ `Bypass .ir domains` در تنظیمات Routing افزوده شده است. فقط config زمان اجرا تغییر می‌کند، پروفایل ذخیره‌شده و فیلدهای ناشناخته حفظ می‌شوند، DNS hijack در اولویت می‌ماند، و تست پینگ از این policy خارج است. تشخیص دامنهٔ پنهان‌شده با ECH/DNS رمز‌شده یا مقصد IP literal نیازمند بررسی همان کانفیگ است.
- تم Simple پیش‌فرض نصب تازه است؛ Light و OLED به تنظیم روشن/تیرهٔ گوشی وابسته‌اند. Dynamic Color، اتصال هنگام راه‌اندازی و سایر تنظیمات اصلی جز استثناهای صریح بالا باقی‌اند. حذف‌های تاریخی دیگر راهنماهای قدیمی منتقل نشده‌اند.

## کلید انتشار جدید نسخهٔ 1.4.2-100

طبق درخواست مالک، این نسخه با کلید جدید ساخته می‌شود و برای نصب آن باید نسخهٔ دارای امضای قبلی حذف شود. کنترل گواهی زمان اجرا با کلید جدید هماهنگ و کل برنامه دوباره ساخته می‌شود. اثرانگشت عمومی مرجع در `RELEASE_CERT_SHA256` است. پشتیبان خصوصی جدید `VPN-signing-backup-v2.zip` تحویل می‌شود و برای تمام به‌روزرسانی‌های بعدی باید همان کلید حفظ شود. کلید خصوصی و رمز در مخزن سورس قرار نمی‌گیرند.

## ساخت

ابتدا workflow `Build native core` و سپس `Build VPN APK` اجرا شود. CI فایل‌های ARMv7، ARM64 و Universal، گزارش آزمون‌ها، mapping مربوط به R8 و مشخصات APK را تولید می‌کند. منبع، خروجی native و artifact همگی با مرجع پین‌شده کنترل می‌شوند. تغییر هسته یا نسخهٔ پایه نیازمند هماهنگ‌کردن pin و نام artifact/cache در workflowها است.

APK انتشار پس از CI با کلید خصوصی برنامه امضا می‌شود. SHA-256 عمومی گواهی در `RELEASE_CERT_SHA256` ثبت است و با `VPN_EXPECTED_CERT_SHA256` به Gradle تزریق می‌شود. کلید خصوصی و رمز در مخزن قرار ندارند؛ نسخهٔ پشتیبان تحویلی برای امضای به‌روزرسانی‌ها لازم است. هنگام ساخت با کلید خود، fingerprint گواهی همان کلید را تنظیم کنید.

R8، resource shrinking، strip هسته، بررسی گواهی انتشار و رمزگذاری تصاویر فعال‌اند. این موارد جایگزین تست دستگاه یا تضمین غیرقابل‌تغییر بودن APK نیستند. Core و lifecycle پیش‌فرض حفظ شده‌اند؛ ساخت موفق به‌تنهایی عبور ترافیک واقعی را اثبات نمی‌کند.

## اعتبارسنجی

```bash
python3 scripts/audit-source.py
# پس از آماده‌بودن SDK و native AAR، از پوشهٔ پروژهٔ Android:
./gradlew :app:testPlusDebugUnitTest :app:assemblePlusDebug :app:assemblePlusRelease
```

کنترل نهایی شامل package، نبود debuggable، معماری واقعی ELF، حضور Amnezia در هر دو هسته، چهار تصویر، امضا و mapping همان build است. هیچ emulator یا گوشی در این محیط آزمایش نشده است. اتصال واقعی، تغییر شبکه، خواب گوشی، لغو، خاموش/روشن‌شدن و انقضا باید با کانفیگ آزمایشی روی دستگاه بررسی شوند.


## بررسی فیلدهای JSON پس از R8

نام و نوع تمام فیلدهای عمومی مدل‌های SingBoxOptions و فیلدهای نمونهٔ AndroidTunPayload از فایل‌های class پیش از R8 استخراج و با فیلدهای تعریف‌شده در DEX نهایی مقایسه می‌شوند. mapping ممکن است فیلدهای بدون تغییر را ننویسد، بنابراین شمارش آن معیار حفظ قرارداد JSON نیست. نتیجهٔ مقایسه در json-field-contract.json همراه خروجی انتشار ذخیره می‌شود.

نام فیلدهای JSON باید عیناً ثابت بماند؛ نام کلاس نوع مرجع آن‌ها (برای نمونه Gson JsonElement) می‌تواند با R8 تغییر کند. مقایسهٔ نوع با اعمال نگاشت دقیق نام کلاس از mapping انجام می‌شود؛ حذف فیلد، تغییر نام کلید یا نوعی متفاوت از همان کلاس نگاشت‌شده همچنان خطاست. قرارداد خروجی هر دو جدول pre_r8 و expected را نگه می‌دارد.

## تغییرهای نسخهٔ 1.4.2-103؛ مقدم بر پیش‌فرض‌های نسخهٔ 102

- نسخه 103، کد ARM برابر 450 است. کلید انتشار v2 ثابت می‌ماند.
- آیکون iconn.png کاربر بدون تغییر بایت تصویر جایگزین شد. همان inset امن 21٪ تطبیقی و 9٪ قدیمی باقی است؛ تمام بخش روشن لوگو در شعاع 29.64dp از مرکز viewport108dp قرار می‌گیرد و داخل دایرهٔ امن شعاع33dp است. hash ورودی در LAUNCHER_ICON_SHA256 ثبت و در audit بررسی می‌شود.
- پیش‌فرض Apps VPN mode خاموش است. مهاجرت mobiletinaAppModeOffV103 یک‌بار آن را خاموش می‌کند؛ انتخاب برنامه‌های موجود حفظ می‌شود و انتخاب دستی بعدی بازنویسی نمی‌شود. نصب تازه نیز Off است.
- Night mode دوباره قابل انتخاب است. Theme انتخاب ذخیره‌شدهٔ Light/Dark/System را رعایت و انتخاب پالت، حالت شب را بازنشانی نمی‌کند. حالت تیره همچنان OLED است.
- زیرخط سه آیدی Welcome حذف و قابلیت لمس لینک‌ها و متن رمزگذاری‌شدهٔ آن‌ها حفظ شد.
- Scanner برای #Base64، ابتدا متن را به MainActivity تحویل و خود را می‌بندد؛ واردکردن در lifecycle بازگشتهٔ Main انجام می‌شود. حالت return-scan-text و واردکردن چند QR تصویری حفظ‌اند.
- URL test از شناسهٔ منوی سفارشی مستقیماً اجرا می‌شود؛ به وجود همان MenuItem در toolbar وابسته نیست و handler صریح URL/ICMP نیز دارد. کنترلر موجود و URL-test بدون دیالوگ حفظ‌اند.
- زیرمنوی حذف بلافاصله بعد از حذف کانفیگ‌هاست؛ نسخهٔ تکراری آن از کپی Misc کنار گذاشته می‌شود.
- هفت عبارت Group مطابق متن فارسی درخواستی ترجمه شدند.
- بررسی انقضا چند خطر کد را آشکار کرد: retry صفر، باقی‌ماندن موعد پس از تبدیل به marker، استثنای کنترل‌نشدهٔ scope، جایگزین‌کردن worker در حال اجرا، callback معلق زیر mutex و SharedPreferences کش‌شده بین UI و :bg. بدون لاگ دستگاه، این‌ها شواهد کد و علت‌های محتمل هستند؛ کرش مشخص روی گوشی بازتولید نشده است.
- انقضا اکنون از KV موجود Room در PublicDatabase با تراکنش و مهاجرت یک‌باره از تنظیمات قبلی، و RemoteWorkManager سازگار با معماری همین برنامه استفاده می‌کند. schema جدید یا تغییر هسته لازم نیست.
- گیرندهٔ Alarm فقط verification را صف می‌کند؛ شبکه یا توقف VPN را داخل broadcast منتظر نمی‌ماند. foreground استثنا را ثبت و حداقل 30ثانیه برای retry صبر می‌کند؛ worker نیز retry با backoff دارد. deadline و verification نام‌های جدا دارند و worker موعد اصلاح‌شده را بدون لغو خودش دنبال می‌کند.
- تبدیل به marker با بازخوانی وضعیت در تراکنش، به گروه مبدأ محدود و idempotent است. رکورد پایان‌یافته حذف می‌شود؛ worker تکراری noop است. callbackها پس از آزادکردن قفل و تغییر پس‌زمینه با broadcast محدود به همان بسته به UI اطلاع داده می‌شود.
- مسدودسازی marker از وجود timer مستقل است. برای تاریخ مدیریت‌شده، ساعت گوشیِ تأییدنشده به‌تنهایی مانع اتصال نمی‌شود؛ ساعت HTTPS/monotonic معتبر و تفسیر تهران حفظ‌اند. سه تست regression برای retry و marker نهایی اضافه شدند.
- اجرای واقعی دوربین، رندر گوشی، اتصال شبکه و رفع کرش روی دستگاه در این محیط ادعا نمی‌شود؛ نتیجهٔ دقیق build/test/امضا در گزارش تحویل همان نسخه ثبت می‌شود.
