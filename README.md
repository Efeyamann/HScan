# HScan

Kişisel Android manga ve manhwa okuyucusu. MangaDex, MangaBats, MangaBuddy, yerel CBZ/ZIP dosyaları ve çevrimdışı bölümler. Türkçe arayüz, koyu tema, dikey okuma ve yakınlaştırma.

## Telefona kurma

[Releases](https://github.com/Efeyamann/HScan/releases) sayfasındaki **HScan.apk** dosyasını indir ve telefonunda aç. Android 8 veya üzeri gerekir. İlk kurulumda Android'in dosyayı açtığın uygulamadan yükleme iznini ver. Yeni APK'yı mevcut uygulamanın üzerine kur; kütüphaneni korumak için uygulamayı kaldırma.

## Kullanım

- **Kaynaklar:** Üç sitede aynı anda ara; karttaki küçük etiket kaynağı gösterir. Aynı seri farklı kaynaklarda ayrı kartlarda görünür. Bir kaynak erişilemezse diğerlerinin sonuçları gösterilir. Bölüm dili filtresi MangaDex için geçerlidir; MangaBats ve MangaBuddy İngilizce içerik sunar.
- **Kütüphane:** Serileri ara, okuma durumunu seç. Son okuduğun seri kütüphanede olmasa da ana ekrandaki **Devam et** düğmesiyle bölüm ve sayfa konumuna dön.
- **Arama:** Seri detayından geri dönünce arama metni, dil, sonuçlar ve kaydırma konumu korunur.
- **Okuyucu:** Yukarıdan aşağıya kaydır. Çift dokun veya iki parmağınla yakınlaştır. Tek dokun kontrolleri gizler/açar.
- **İndirilenler:** Bölüm listesinden indirme başlat. Tamamlanan bölümleri internetsiz oku.
- **+ düğmesi:** Görsel içeren CBZ/ZIP dosyası içe aktar. Dosya adları doğal sırayla okunur (1, 2, 10).
- **Güncellemeler:** GitHub Releases üzerinden açılışta ve yaklaşık 6 saatte bir yeni sürüm kontrol edilir. APK Wi-Fi ve mobil veride otomatik iner; Ayarlar’dan otomatik indirmeyi kapatabilir veya yalnız Wi-Fi seçebilirsin. Bildirim izni verirsen indirme bitince haber gelir. Bildirimden ya da uygulamadaki **Güncellemeyi kur** düğmesinden Android’in kurulum onayına geçilir. İlk seferde HScan’e uygulama yükleme izni gerekir. APK boyutu, SHA-256 özeti, paket adı, sürümü ve mevcut uygulamayla aynı imza doğrulanır. Arka plan kontrolünün zamanı Android’in pil/ağ koşullarına bağlıdır.
- **Ayarlar:** Sayfa aralığı ve JSON yedeği. Yedek kapak/görselleri içermez. Yerel arşivleri ayrıca sakla; aynı arşivi tekrar içe aktarmak ilerlemeyi korur.

MangaDex'teki başka sitelere yönlendiren bölümler okuyucuda gösterilmez. Bir bölüm numarası/dili için tek çeviri gösterilir. İngilizce ve Türkçe filtreleri veya tüm diller kullanılabilir.

## Geliştirme ve derleme

Kotlin, Jetpack Compose / Material 3, Room, Coil / Telephoto, OkHttp ve WorkManager. Android Studio kurulumu gerekmez. `main` değişiklikleri GitHub Actions'ta test edilir ve APK'ya derlenir. Actions imza anahtarını repository secrets üzerinden alır; özel anahtar repoya girmez. APK sürüm kodu workflow çalışma numarasıyla artar.

Yerelde derlemek isteyenler için JDK 17 ve Android SDK gerekir: `./gradlew testDebugUnitTest lintDebug assembleDebug`. İmzalı sürüm için `HSCAN_KEYSTORE_PATH` ve `HSCAN_KEYSTORE_PASSWORD` ortam değişkenleri kullanılır.

## Veri

Kütüphane ve ilerleme cihazdaki Room veritabanındadır. İndirilenler uygulamanın özel depolamasında, çevrimiçi sayfalar temizlenebilir önbellektedir. Sunucu, hesap veya analiz servisi yoktur. MangaDex API'sine, MangaBats ve MangaBuddy'nin herkese açık sayfalarına/bölüm listelerine ve görsel sunucularına içerik için bağlanılır. Kaynak bağlantıları yedekte korunur. Veritabanı güncellemesi mevcut kütüphaneyi ve ilerlemeyi korur.

## Kontroller

CI: JVM birim testleri, Android lint, APK derlemesi ve Android 35 emülatöründe yerel arşiv / veritabanı / okuyucu testleri; eski veritabanı geçişi, iki web kaynağında gerçek arama, sayfa yükleme, bölüm indirme ve kaynak bağlantılı yedek testi. Gerçek telefonda okuma akıcılığı ve kurulum ayrıca kontrol edilir.
