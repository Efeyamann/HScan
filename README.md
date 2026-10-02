# HScan

Kişisel Android manga ve manhwa okuyucusu. MangaDex, yerel CBZ/ZIP dosyaları ve çevrimdışı bölümler. Türkçe arayüz, koyu tema, dikey okuma ve yakınlaştırma.

## Telefona kurma

[Releases](https://github.com/Efeyamann/HScan/releases) sayfasındaki **HScan.apk** dosyasını indir ve telefonunda aç. Android 8 veya üzeri gerekir. İlk kurulumda Android'in dosyayı açtığın uygulamadan yükleme iznini ver. Yeni APK'yı mevcut uygulamanın üzerine kur; kütüphaneni korumak için uygulamayı kaldırma.

## Kullanım

- **Kaynaklar:** MangaDex'te ara, bölüm dilini seç, seriyi kütüphaneye ekle.
- **Kütüphane:** Serileri ara, okuma durumunu seç, kaldığın yerden devam et.
- **Okuyucu:** Yukarıdan aşağıya kaydır. Çift dokun veya iki parmağınla yakınlaştır. Tek dokun kontrolleri gizler/açar.
- **İndirilenler:** Bölüm listesinden indirme başlat. Tamamlanan bölümleri internetsiz oku.
- **+ düğmesi:** Görsel içeren CBZ/ZIP dosyası içe aktar. Dosya adları doğal sırayla okunur (1, 2, 10).
- **Ayarlar:** Sayfa aralığı ve JSON yedeği. Yedek kapak/görselleri içermez. Yerel arşivleri ayrıca sakla; aynı arşivi tekrar içe aktarmak ilerlemeyi korur.

MangaDex'teki başka sitelere yönlendiren bölümler okuyucuda gösterilmez. Bir bölüm numarası/dili için tek çeviri gösterilir. İngilizce ve Türkçe filtreleri veya tüm diller kullanılabilir.

## Geliştirme ve derleme

Kotlin, Jetpack Compose / Material 3, Room, Coil / Telephoto, OkHttp ve WorkManager. Android Studio kurulumu gerekmez. `main` değişiklikleri GitHub Actions'ta test edilir ve APK'ya derlenir. Actions imza anahtarını repository secrets üzerinden alır; özel anahtar repoya girmez. APK sürüm kodu workflow çalışma numarasıyla artar.

Yerelde derlemek isteyenler için JDK 17 ve Android SDK gerekir: `./gradlew testDebugUnitTest lintDebug assembleDebug`. İmzalı sürüm için `HSCAN_KEYSTORE_PATH` ve `HSCAN_KEYSTORE_PASSWORD` ortam değişkenleri kullanılır.

## Veri

Kütüphane ve ilerleme cihazdaki Room veritabanındadır. İndirilenler uygulamanın özel depolamasında, çevrimiçi sayfalar temizlenebilir önbellektedir. Sunucu, hesap veya analiz servisi yoktur. MangaDex API'si ve görsel sunucularına içerik için bağlanılır.

## Kontroller

CI: JVM birim testleri, Android lint, APK derlemesi ve Android 35 emülatöründe yerel arşiv / veritabanı / okuyucu testleri. Gerçek telefonda okuma akıcılığı ve kurulum ayrıca kontrol edilir.
