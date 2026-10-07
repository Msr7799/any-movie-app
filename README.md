# Any Movie — Android + Server

نسخة محدثة تجمع بين **كتالوج عام** مركزي و**مكتبة شخصية** مستقلة لكل مستخدم، مع لوحة مستخدم سينمائية، Sidebar متحرك، ودمج TMDB من خلال السيرفر فقط.

## المعمارية

- **Public Catalog:** MongoDB عبر `server/api/v1/catalog.ts`. العناصر العامة المنشورة فقط تظهر في التطبيق.
- **Personal Library:** Firebase Realtime Database تحت `users/{uid}/library/{catalogId}` مع نسخة محلية للعمل دون اتصال.
- **TMDB:** المفتاح يبقى على السيرفر. Android يستدعي `/api/v1/tmdb/*` ولا يحتوي مفتاح TMDB داخل APK.
- **Admin:** جلسة منفصلة محمية على السيرفر. يمكن للأدمن إدارة حالة العنصر (`metadata_only`, `draft`, `published`, `archived`) ومفاتيح TMDB وباقي مفاتيح الخدمات.

## أهم ما تمت إضافته

- `UserDashboardActivity` بتصميم داكن قريب من هوية موقع سينما.
- Animated Drawer/Sidebar مع Dashboard، الكتالوج العام، مكتبتي، قائمتي، المشاهدة الأخيرة، التقييمات، والعودة للمشغل.
- إحصائيات شخصية للمكتبة وقائمة المشاهدة والمشاهَد والتقييمات.
- بحث TMDB داخل لوحة المستخدم وإضافة النتيجة للمكتبة الشخصية.
- حفظ نتائج TMDB مباشرة في المكتبة الشخصية بدون تعديل الكتالوج العام، مع ترقية الربط تلقائياً إذا نشر الأدمن نفس TMDB ID لاحقاً.
- `UserLibraryRepository` للمفضلة، Watchlist، التقييم، تقدم المشاهدة وحالة المشاهدة.
- ربط تقدم Media3 بالمكتبة الشخصية.
- صفحة تفاصيل محسنة تعرض backdrop، البيانات، الطاقم، الصور والفيديوهات من TMDB.
- فصل Firebase Public Catalog القديم؛ مصدر الكتالوج العام الآن السيرفر/MongoDB.
- قواعد Firebase تمنع أي مستخدم من الوصول إلى مكتبة UID آخر.
- إدارة TMDB server-side عبر متغيرات البيئة أو إعدادات الأدمن المشفرة.

## تشغيل Android

المتطلبات: Android Studio حديث يدعم AGP 9.1.1، JDK 17، Android SDK API 37.

ضع عنوان السيرفر في `gradle.properties` إذا أردت تغييره:

```properties
ANY_MOVIE_API_BASE_URL=https://your-server.example
```

ثم افتح المشروع في Android Studio وشغّل `app`. تم إضافة `gradle/libs.versions.toml` و`gradle/wrapper/gradle-wrapper.properties` إلى النسخة المعدلة. إذا كانت نسختك الأصلية لا تحتوي `gradle-wrapper.jar`، يستطيع Android Studio استخدام Gradle المثبت/المضمّن أو يمكنك إعادة إنشاء الـwrapper محلياً.

## Firebase

- فعّل Anonymous Auth، ويمكن تفعيل Google وEmail/Password.
- ضع `app/google-services.json` الخاص بمشروعك.
- انشر `database.rules.json`.
- راجع `FIREBASE_SETUP.md`.

## Server / TMDB

> **مهم:** التطبيق الافتراضي يتصل بـ `https://movies-search-server.vercel.app`. إذا كان هذا النشر ما زال يعرض API 1.x فلن يحتوي مسارات TMDB الجديدة. انشر مجلد `server/` من هذه النسخة على مشروع Vercel نفسه واضبط مفاتيح البيئة أدناه. شاشة Dashboard تعرض إصدار الخادم وحالة TMDB لتشخيص هذا مباشرة.


داخل `server/.env` محلياً أو في متغيرات بيئة الاستضافة استخدم أحد الخيارين:

```env
TMDB_API_KEY=
# أو
API_READ_AUTH_TOKEN=
```

كما يمكن للأدمن حفظهما من لوحة الإدارة؛ القيم تحفظ مشفرة في MongoDB ولا تعاد كنص صريح إلى العميل.

المسارات الجديدة:

```text
GET  /api/v1/tmdb/search?q=...&type=multi|movie|tv
GET  /api/v1/tmdb/details?id=...&type=movie|tv
POST /api/v1/catalog/from-tmdb   # metadata resolution only; no public write
POST /api/admin/catalog/from-tmdb # admin-only public catalog creation
GET  /api/v1/catalog
```

`/api/v1/catalog/from-tmdb` أصبح مسار **حل بيانات فقط** ولا يكتب في MongoDB. المستخدم العادي يستطيع حفظ النتيجة في مكتبته الشخصية/Firebase فقط. إنشاء سجل عام من TMDB أصبح تحت `/api/admin/catalog/from-tmdb` ويتطلب جلسة الأدمن، وبعدها تبقى حالة العنصر `metadata_only` إلى أن يضيف الأدمن مصدر تشغيل مصرحاً به وينشره.

## أسرار البناء

لا ترفع هذه الملفات إلى Git:

```text
server/.env
keystore.properties
local.properties
```

استخدم `.env.example` و`keystore.properties.example` كقوالب.

## التحقق المنفذ على هذه النسخة

- تم التحقق من صحة ملفات XML وJSON.
- تم فحص ملفات Kotlin/TypeScript المعدلة لاكتشاف أخطاء syntax.
- تعذر تنفيذ build Android كامل داخل بيئة التسليم لعدم توفر Android SDK/Gradle wrapper binary واعتماديات الشبكة محلياً؛ لذلك شغّل Gradle Sync ثم `assembleDebug` في Android Studio قبل النشر.
