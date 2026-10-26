# O'rtacha tezlik — Android ilova (prototip)

Ikki kamera orasidagi **o'rtacha tezlik nazorati** zonalarida haydovchini ogohlantiradi.
Faqat GPS bilan ishlaydi (radar signalini ushlamaydi).

## Nima qiladi (hammasi avtomatik)
- Ilova ochilishi bilan kuzatuv yoqiladi, kamera bazasi kuniga bir marta o'zi yangilanadi (OpenStreetMap).
- Zona belgilash shart emas: bazadagi **har qanday kameradan o'tganingizda** vaqt va yo'l yoziladi,
  keyingi har qanday kameragacha o'rtacha tezlik real vaqtda hisoblanadi (A–B, B–C, A–C ...).
  Faqat kamera oldida sekinlashib, oraliqda tezlashsangiz — ilova buni ko'rsatadi va ogohlantiradi.
- Ekranda: "Keyingi kameragacha o'rtacha ≤ 43 km/soat, kamida 3:30", prognoz, oldingi kameradan beri o'rtacha.
- Ovoz: kameraga 500 m qolganda; o'rtacha tezlik xavfli bo'lganda (tebranish bilan); kameradan o'tganda oraliq natijasi.
- Kamera chegarasi bazada bo'lmasa — "Standart chegara" olinadi (sozlanadi, boshida 60).
- Ekran o'chiq bo'lsa ham fon rejimida ishlaydi.

## Baza
1. **OpenStreetMap**: `highway=speed_camera`, `enforcement=maxspeed` va `enforcement=average_speed` (from/device/to).
   Kamerani openstreetmap.org ga qo'shsangiz, keyingi yangilanishda hamma foydalanuvchida paydo bo'ladi.
2. **"Bu yerda kamera bor"** tugmasi — joriy joyga kamera qo'shadi.
3. **Import/Eksport** — JSON fayl:
```json
{
  "cameras": [{"id": "cam-1", "lat": 41.31, "lon": 69.28, "limit": 60}],
  "zones": [{"id": "z1", "name": "Toshkent–Chirchiq", "start": [41.3, 69.3], "via": [[41.34, 69.35]], "end": [41.38, 69.4], "limit": 60}]
}
```
Zonalar ham kamera nuqtalariga aylantiriladi.

## Cheklovlar
- Oldindagi kameragacha masofa to'g'ri chiziq bo'yicha olinadi (yo'l egri bo'lsa, ilova ehtiyotroq hisoblaydi).
- Kesishgan ko'chadagi kamera yonidan 40 m ichida o'tsangiz, u ham "o'tilgan" deb olinishi mumkin.
- Telefonda o'zbek tilidagi ovoz (TTS) bo'lmasa, ovoz o'rniga signal chalinadi.
- Bu yordamchi vosita; yo'l belgilari va YHQ ustun.

## Qanday yig'ish (APK)
**Android Studio**: papkani oching → Run. (Android Studio Ladybug yoki yangiroq.)

**GitHub orqali, kompyutersiz**: loyihani GitHub repo'ga yuklang. `.github/workflows/build.yml`
avtomatik ishga tushadi va **Actions → Build APK → antiradar-apk** dan tayyor APK ni yuklab olasiz.

## Tuzilishi
- `core/` — sof Kotlin: avtomatik hisob (`AutoTracker.kt`), zonalar (`ZoneTracker.kt`), oldindagi kamera (`Camera.kt`), OSM'ni o'girish (`Osm.kt`). Testlar: `./gradlew :core:test`.
- `app/` — Android: GPS fon xizmati (`TrackingService.kt`), ekran (`MainActivity.kt`), ovoz (`Alerts.kt`), zonalar (`ZoneRepository.kt`), OSM yuklash (`OsmUpdater.kt`).

OpenStreetMap ma'lumotlari © OpenStreetMap hissadorlari, ODbL.

