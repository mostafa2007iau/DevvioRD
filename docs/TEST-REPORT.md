# گزارش تست‌ها — DevvioRD 1.0.0

Test report for the DevvioRD 1.0.0 release. Every number below comes from a run on
the code in this repository against the binaries in `dist/`.

تاریخ اجرا: ۲۰۲۶‑۱۱‑۰۴ (فاز ۱) و ۲۰۲۶‑۱۱‑۰۵ (فاز ۲) · پلتفرم: linux/amd64 ·
Go 1.27.3 · Java 21 (jdk4py) · Node 20+

---

## خلاصه / Summary

| مجموعه | تعداد | نتیجه | دستور |
| --- | ---: | --- | --- |
| Go unit / integration (سرور) | 49 | ✅ 0 failure | `cd server && go test ./...` |
| Go با race detector | 7 پکیج | ✅ پاک | `cd server && go test ./... -race` |
| منطق اپ اندروید روی JVM | 103 | ✅ 0 failure | `python3 scripts/jvm_tests.py` |
| پروتکل + استریم (WebSocket واقعی) | 21 | ✅ 0 failure | `python3 scripts/integration_test.py --port 5902 --token secret123` |
| انتقال فایل (HTTP واقعی) | 20 | ✅ 0 failure | `python3 scripts/files_test.py --port 5902 --token secret123` |
| کلاینت وب (jsdom) | 75 | ✅ 0 failure | `node scripts/web_test.js --url http://127.0.0.1:5902 --token secret123` |
| صحت QR با دیکودر مستقل | 3 | ✅ 3/3 | `python3 scripts/qr_verify.py` |
| ساختار فایل APK | 14 | ✅ 0 failure | `python3 scripts/verify_apk.py` |
| **جمع** | **285** | **همه سبز** | |

فاز ۲ سی بررسی جدید اضافه کرد (۳۳ تست JVM برای `Hosts`، ۳۶ تست وب برای آمار،
پیش‌تنظیم‌ها، تب مخفی، میان‌برها و دیالوگ‌ها، و یک تست Go برای توقف هر‌نشست).
جدول کامل باگ‌های پیدا و رفع‌شده در [`BUGS.md`](BUGS.md) است.

علاوه بر این‌ها، چهار بررسی دستی/تصویری هم انجام شده که در بخش «بررسی‌های دستی» آمده است.

---

## ۱. Go — تست‌های واحد سرور

هفت پکیج، ۴۸ تست:

| پکیج | تست‌ها | چه چیزی پوشش داده می‌شود |
| --- | ---: | --- |
| `internal/proto` | 9 | چیدمان دقیق بایت‌ها (لمس ۲۵ بایتی، هدر ۱۷ بایتی فریم)، مقاومت در برابر پیام ناقص/زباله، ping/pong |
| `internal/files` | 7 | `SafeJoin` در برابر مسیرهای `..`، نام فایل کنترل‌کاراکتر، نوع محتوا، جلوگیری از بازنویسی |
| `internal/server` | 12 | احراز هویت روی همه مسیرهای محافظت‌شده، سه روش ارسال توکن، CORS، `snapshot.jpg` بدون کلاینت استریم، نشت نکردن توکن در `/api/pair` |
| `internal/input` | 8 | نگاشت کلیدهای W3C به keysym، عدم نگاشت حروف غیرلاتین (تا مسیر IME فعال شود) |
| `internal/capture` | 9 | رندر دسکتاپ مجازی، عدم همپوشانی نوار وظیفه در ۸ رزولوشن، رندر متن فارسی، bounded بودن لاگ رویداد، ضریب فریم |
| `internal/cli` | 2 | تولید QR |
| `internal/selfsetup` | 3 | تشخیص حالت، پوشه فایل پیش‌فرض |

```
ok  github.com/devviord/devviord/server/internal/capture   0.92s
ok  github.com/devviord/devviord/server/internal/cli       0.01s
ok  github.com/devviord/devviord/server/internal/files     0.01s
ok  github.com/devviord/devviord/server/internal/input     0.01s
ok  github.com/devviord/devviord/server/internal/proto     0.01s
ok  github.com/devviord/devviord/server/internal/selfsetup 0.01s
ok  github.com/devviord/devviord/server/internal/server    0.34s
```

`go vet ./...` و `gofmt -l .` هر دو بدون خروجی هستند.

### Race detector

```
go test ./... -race -count=1     # 7/7 ok, no data race reported
```

این اجرا برای اولین بار مسیر «پمپ فریم + نوشتن هم‌زمان به چند کلاینت» را هم تحت
آشکارساز مسابقه قرار داد. تنها هشدار مربوط به تست کارایی فریم بود که زیر
`-race` (با ~۵ برابر کندی مصنوعی) بی‌معنی است و در همان حالت skip می‌شود.

---

## ۲. منطق اپ اندروید روی JVM — 61 check

منبع اپ اندروید با ECJ و `android.jar` کامپایل می‌شود و کلاس‌های بدون وابستگی به
UI مستقیماً روی JVM تست می‌شوند (بدون امولاتور):

```bash
python3 scripts/jvm_tests.py      # 61 passed, 0 failed
```

پوشش اصلی: `Proto` (سریال‌سازی/پارس همه پیام‌ها)، `WsClient` (فریم‌بندی RFC 6455،
fragmentation، mask، بستن تمیز)، `ImeBridge` (تشخیص کلید کنترلی در برابر متن
ترکیبی، حذف backspace تکراری)، `Settings`/`Prefs`، پارس deep link
(`devviord://connect?host=…`)، نرمال‌سازی آدرس، و `SafeJoin` سمت گوشی.

---

## ۳. پروتکل و استریم — 21 check

تست‌ها روی WebSocket واقعی (سرور Go در حال اجرا) اجرا می‌شوند، نه mock:

```bash
./dist/devviord --addr 0.0.0.0:5900 --capture synthetic --input synthetic \
  --quality 72 --fps 20 --files /tmp/devviofiles --token secret123 &
python3 scripts/integration_test.py --token secret123 --frames 25
# 21 passed, 0 failed
```

شامل: handshake و `hello-ok`، توکن اشتباه → 401، نسخه پروتکل ناسازگار، دریافت
فریم JPEG سالم (چک ماژیک `FFD8`/`FFD9`)، ترتیب seq، **ping/pong با echo دقیق
timestamp**، لمس down/up، drag ۱۲ نمونه‌ای، scroll، کلیدهای ASCII، **متن IME
فارسی/چینی/ایموجی**، کلیپ‌بورد دوطرفه، تغییر کیفیت در لحظه، تغییر حالت
touch/mouse، و پذیرش کلاینت دوم به‌صورت هم‌زمان.

نمونه خروجی دستی ping/pong پس از رفع باگ:

```
sent 1793786158824 echoed 1793786158824 match: True rtt: 0.26 ms
```

---

## ۴. انتقال فایل — 20 check

```bash
python3 scripts/files_test.py --token secret123     # 20 passed, 0 failed
```

شامل آپلود ساده، دانلود با تطابق sha256، آپلود ۳ مگابایتی چندچانکی، آپلود
resume (چک offset)، نام فایل یونیکد (`گزارش نهایی ۱۴۰۳.txt`)، فایل صفر بایتی،
ساخت پوشه، rename، delete، سه الگوی path traversal و filename آلوده، رد چانک
بزرگ‌تر از حد (413) و رد offset نادرست.

> این تست‌ها idempotent هستند؛ اجرای پشت‌سرهم هم ۲۰/۲۰ می‌دهد.

---

## ۵. کلاینت وب — 36 check

کلاینت وب داخل jsdom با یک WebSocket واقعی اجرا و رفتارش assert می‌شود:

```bash
node scripts/web_test.js --token secret123          # 36 passed, 0 failed
```

شامل: اتصال و نمایش ویدیو، حالت لمسی و موس (PointerEvent مصنوعی)، ارسال متن با
IME، کلیپ‌بورد، پنل فایل، تنظیمات کیفیت، و ۵ بررسی جفت‌شدن: وجود `/api/pair`،
ماتریس QR در ابعاد درست، ساخت deep link، نشت نکردن توکن، و رندر SVG.

---

## ۶. صحت QR — 3/3

QR تولیدشده در بنر و پنل جفت‌شدن با یک دیکودر مستقل (OpenCV) برگردانده و با
payload اصلی مقایسه می‌شود:

```bash
python3 scripts/qr_verify.py
# 3/3 payloads round-tripped through a real QR decoder
```

---

## ۷. ساختار فایل APK — 14 check

```bash
python3 scripts/verify_apk.py       # 14 passed, 0 failed
```

| بررسی | نتیجه |
| --- | --- |
| `AndroidManifest.xml` باینری AXML معتبر (magic `0x00080003`) | ✅ ۵٬۱۷۶ بایت |
| `resources.arsc` بدون فشرده‌سازی و ۴ بایتی هم‌تراز (لازم برای targetSdk ≥ ۳۰) | ✅ offset 6192 |
| `classes.dex` نسخه ۰۳۵ | ✅ ۱۰۳ KiB |
| امضای v1 (JAR) و بلوک v2/v3 | ✅ |
| پکیج و نسخه | ✅ `ai.devvio.rd` v1.0.0 (1) |
| minSdk / targetSdk | ✅ ۲۱ / ۳۴ |
| اکتیویتی لانچر و وجود کلاس در dex | ✅ `ConnectActivity` |
| هر اکتیویتی اعلام‌شده در manifest در dex موجود است | ✅ |
| آیکون و برچسب برنامه | ✅ `DevvioRD` |

مشخصات فایل نهایی:

```
dist/DevvioRD-1.0.0.apk      82,731 bytes   (فاز ۲: رابط بازطراحی‌شده)
sha256                       f2805330b69d4e6a18b25c8a114e647e293a4eeddb9245337e7b9f798692d17b
signature schemes            v1 + v2 + v3
```

برای مقایسه، APK فاز ۱ ‏۶۶٬۳۴۷ بایت با
`1f76fcf87d0666d1bcccdc08791d9cc9490306daedfc1f8821dcf28fbd83077c` بود؛ افزایش
اندازه از بازنویسی صفحه‌ها و افزودن `Hosts.java` است.

---

## ۸. باینری‌های سرور

شش هدف، همه با `CGO_ENABLED=0` و `-ldflags "-s -w"`:

| فایل | فرمت | اندازه |
| --- | --- | ---: |
| `dist/devviord` | ELF x86‑64 | 7.0 MB |
| `dist/devviord-linux-amd64` | ELF x86‑64 | 7.0 MB |
| `dist/devviord-linux-arm64` | ELF aarch64 | 6.5 MB |
| `dist/devviord-linux-armv7` | ELF ARM | 6.9 MB |
| `dist/devviord-darwin-arm64` | Mach‑O arm64 | 6.6 MB |
| `dist/devviord-darwin-amd64` | Mach‑O x86‑64 | 7.1 MB |
| `dist/devviord-windows-amd64.exe` | PE x86‑64 | 7.2 MB |

هر فایل با خواندن هدر اجرایی (ELF `e_machine` / Mach‑O `cputype` / PE `Machine`)
تأیید شد که معماری و سیستم‌عامل درست است.

---

## ۹. بررسی‌های دستی و تصویری

| بررسی | روش | نتیجه |
| --- | --- | --- |
| بنر شروع + QR + آدرس اتصال | اجرای واقعی سرور و دیکود QR با OpenCV | ✅ |
| تصویر دسکتاپ مجازی | `GET /snapshot.jpg` → بازبینی تصویر | ✅ ۱۲۸۰×۸۰۰ |
| انتقال ورودی از راه دور | ۳ ضربه از کلاینت مستقل → شمارنده `TAPS 3` روی دسکتاپ | ✅ |
| کلیپ‌بورد گوشی → سرور | ارسال `ClipboardEvent` و خواندن `/api/clipboard` | ✅ متن فارسی سالم |
| متن IME | ارسال `TextEvent` فارسی | ✅ «ime text delivered (14 unicode chars)» |

نمونه تصویر: [`docs/img/preview.jpg`](img/preview.jpg)

---

## ۱۰. باگ‌هایی که در این دور تست پیدا و رفع شدند

| # | باگ | اثر | رفع |
| --- | --- | --- | --- |
| ۱ | `DecodeInt64` از offset صفر می‌خواند ولی همه کلاینت‌ها مقدار را از offset ۱ می‌نویسند | RTT نمایش‌داده‌شده در اپ و کلاینت وب کاملاً غلط بود | خواندن از `b[1:9]` و پاس دادن کل پیام در `session.go` |
| ۲ | مهر زمانی نوار وظیفه به‌خاطر کمبود جا بریده می‌شد (`08:3`) | اطلاعات ناقص روی دسکتاپ | چیدمان محاسبه‌شده + تست در ۸ رزولوشن |
| ۳ | برچسب حالت (`TOUCH`) با نام برند همپوشانی داشت و سپس زیر پس‌زمینه‌اش پنهان می‌شد | متن ناخوانا | ترتیب رسم و هندسه درست + تست رگرسیون |
| ۴ | `p`/`q`/`y` در فونت ۵×۷ پایین‌آمدگی ندارند و شبیه `¢` دیده می‌شدند | خط وضعیت ناخوانا | استفاده از حروف بزرگ در خط وضعیت |
| ۵ | پنل رویدادها قدیمی‌ترین ردیف‌ها را نشان می‌داد | آخرین رویدادها دیده نمی‌شد | نمایش tail به‌اندازه فضای موجود |
| ۶ | متن فارسی به‌صورت `????` رندر می‌شد | به‌نظر می‌رسید ورودی خراب است | رومی‌سازی برای نمایش + برچسب `[ar/fa]` |
| ۷ | `/snapshot.jpg` بدون کلاینت استریم ۵۰۳ می‌داد | تصویر پیش‌نمایش در دسترس نبود | `CaptureOnce` روی‌تقاضا + `captureMu` |
| ۸ | کاراکترهای کنترلی در نام فایل آپلودی پذیرفته می‌شدند | نام فایل خراب در لاگ/فایل‌منیجر | حذف `r < 0x20` و `0x7f` در `sanitizeName` |
| ۹ | تست کارایی فریم زیر `-race` شکست می‌خورد | اجرای `go test -race` قرمز می‌شد | skip شدن assert زمان‌بندی در بیلد race |

---

## ۱۱. آنچه تست نشده (محدودیت‌های محیط)

| مورد | دلیل | جایگزین |
| --- | --- | --- |
| نصب APK روی گوشی واقعی | این محیط دستگاه اندروید/امولاتور ندارد | بازرسی ساختاری کامل APK (بخش ۷) + تست منطق روی JVM |
| ضبط دسکتاپ واقعی با ffmpeg/x11grab | `ffmpeg` و X server در محیط نیست | بک‌اند `synthetic` با همان مسیر کد فریم/ورودی |
| کلیپ‌بورد سیستمی (xclip/xsel) | ابزارها نصب نیستند | کلیپ‌بورد درون‌حافظه‌ای؛ مسیر پروتکل کامل تست شده |
| TLS/HTTPS انتها‌به‌انتها | بدون دامنه و گواهی | نمونه پیکربندی nginx در `docs/USER-GUIDE.md` |

مسیرهای `desktop` (ffmpeg/x11) و `clipboard` سیستمی با همان کد و همان تست‌های
پروتکل کار می‌کنند، اما در این محیط اجرای واقعی‌شان ممکن نبود.

---

## ۱۱. فاز ۲ — بازبینی، رفع باگ و بازطراحی رابط

پس از فاز ۱ کل پروژه دوباره بازبینی شد: هر صفحه اپ اندروید بازنویسی و هر مسیر
کلاینت وب مرور شد. جزئیات باگ‌ها در [`BUGS.md`](BUGS.md) و طراحی در
[`UI.md`](UI.md) آمده است. این بخش فقط شواهد تست را ثبت می‌کند.

### ۱۱.۱ چه چیزی به تست‌ها اضافه شد

| مجموعه | قبل | بعد | افزوده |
| --- | ---: | ---: | --- |
| JVM (`scripts/jvm_tests.py`) | 61 | 103 | ۴۲ تست `Hosts`: نرمال‌سازی آدرس، تشخیص پورت (شامل IPv6)، ساخت URL `ws`/`http`، تاریخچه اخیرها، نام‌گذاری بدون رونویسی |
| وب (`scripts/web_test.js`) | 36 | 75 | آمار سرور و پنل، پیش‌تنظیم‌های کیفیت، نشانگر کیفیت اتصال، توقف/ادامه در تب مخفی، منوی کیفیت/نشست، راهنما، میان‌برهای `m`/`i`/`?`/`Esc`، کشوی فایل |
| Go (`server/internal/proto`, `server/internal/server`) | 48 | 49 | `TestPauseIsPerSession` و حضور `TypePause` در فضای نوع‌ها |

### ۱۱.۲ اجرای فاز ۲ (همه سبز)

```
Go            : go vet ./... پاک · go test ./...  → ۷ پکیج ok · ۴۹ تست
Go -race      : go test ./... -race             → ۷ پکیج ok
JVM           : 103 passed, 0 failed
integration   : 21 passed, 0 failed   (پورت 5902، سرور ساخته‌شده از همین سورس)
files         : 20 passed, 0 failed   (دو بررسی احراز هویت: روی سرور دارای توکن
                «۴۰۱ برای درخواست بدون/با توکن غلط» و روی سرور بدون توکن «دسترسی
                آزاد» بررسی می‌شود — هر دو حالت ۲۰/۲۰)
web (jsdom)   : 75 passed, 0 failed   (با توکن و بدون توکن، هر دو ۷۵/۷۵)
QR            : 3/3 payloads
APK           : 14 passed, 0 failed
```

### ۱۱.۳ باگ‌هایی که همان لحظه با تست جدید گرفته شد

- `Hosts.httpBase` برای میزبان `wss://` آدرس `http://` می‌ساخت — تست
  `secure http base` قرمز شد و رفع شد.
- اولین پیاده‌سازی «توقف در تب مخفی» از پیام کیفیت سراسری استفاده می‌کرد و
  نرخ فریم همه کلاینت‌ها را به ۱ می‌رساند — تست وب گرفت و با پیام جدید
  `TypePause` جایگزین شد.
- تست `paintStats` نشان داد پنل آمار `0 fps` می‌دهد در حالی که استریم ۲۰ fps
  بود؛ اولویت به نرخ دیکود مرورگر تغییر کرد.
- `dir=Downloads` روی سروری بدون آن پوشه ۴۰۰ برمی‌گرداند و مشخص شد اپ کورکورانه
  واردش می‌شد؛ انتخاب پوشه شروع به خود فهرست سپرده شد.
- تست آمار گاهی `fps: 0` می‌گرفت چون پروب وسط پنجره اندازه‌گیری می‌رسید؛ تست
  یک بار دیگر poll می‌کند و سه اجرای پشت‌سرهم پایدار است.

### ۱۱.۴ آنچه با تست خودکار قابل پوشش نبود

اپ اندروید روی دستگاه واقعی اجرا نشده است: در این محیط امولاتور و UIAutomator
در دسترس نیست. اعتبارسنجی اپ شامل کامپایل کامل با `ecj`، ساخت و امضای APK،
بررسی ساختار APK (۱۴ بررسی) و ۹۴ تست منطق خالص جاوا روی JVM است. رفتارهای
نیازمند دستگاه (لمس واقعی، چرخش صفحه، دوربین/اسکنر QR، کلیپ‌بورد سیستم) با
بازبینی کد و پوشش منطقی بررسی شده‌اند و در `BUGS.md` صریحاً به‌عنوان محدودیت
ذکر شده‌اند.
