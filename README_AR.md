# Screen Time Exporter — مُصدّر وقت الشاشة

تطبيق Android خفيف يقرأ سجل استخدام التطبيقات من النظام مرة يوميًا، وينشئ تقرير اليوم السابق الساعة **00:05** في:

```text
/storage/emulated/0/Seif Health/ScreenTime
```

الملفات الناتجة لكل يوم:

```text
ScreenTime-YYYY-MM-DD.csv
ScreenTime-YYYY-MM-DD.json
ScreenTime-YYYY-MM-DD.txt
```

## المزايا

- لا توجد خدمة مراقبة مستمرة.
- لا يوجد سيرفر محلي.
- لا يحتاج إنترنت.
- يقرأ سجل Android عبر UsageStatsManager.
- يحسب مدة كل تطبيق وعدد مرات فتحه.
- يعيد الجدولة بعد إعادة تشغيل الهاتف.
- يحاول إنشاء التقارير الناقصة بعد التشغيل، حتى 7 أيام حسب البيانات التي ما زال Android يحتفظ بها.
- APK موقّع بمفتاح ثابت داخل المشروع حتى يمكن تثبيت التحديثات فوق النسخة السابقة.

## بناء APK عن طريق GitHub بدون Android Studio

1. أنشئ Repository جديدًا على GitHub.
2. ارفع **محتويات** هذا الفولدر إلى جذر الـRepository، وليس الفولدر الخارجي نفسه.
3. افتح تبويب **Actions**.
4. اختر **Build signed APK**.
5. اضغط **Run workflow**.
6. بعد انتهاء البناء افتح التشغيل الناجح، ثم نزّل Artifact باسم:

```text
ScreenTimeExporter-release
```

7. فك الضغط؛ ستجد:

```text
app-release.apk
```

## بناء APK باستخدام Android Studio

1. افتح فولدر المشروع في Android Studio.
2. انتظر انتهاء Gradle Sync.
3. من القائمة:

```text
Build > Generate App Bundles or APKs > Generate APKs
```

أو من Terminal داخل المشروع:

```bash
gradle :app:assembleRelease
```

الناتج:

```text
app/build/outputs/apk/release/app-release.apk
```

## التثبيت على الهاتف

### بالطريقة العادية

1. انقل `app-release.apk` إلى الهاتف.
2. افتحه من مدير الملفات.
3. وافق على **Install unknown apps** إذا طُلب.
4. ثبّت التطبيق وافتحه.

### باستخدام ADB

```bat
adb install -r app-release.apk
```

اسم الحزمة:

```text
com.seif.screentimeexporter
```

## الإعداد داخل التطبيق

1. اضغط **تفعيل صلاحية بيانات الاستخدام**.
2. فعّل الوصول لتطبيق **Screen Time Exporter**.
3. ارجع واضغط **تفعيل صلاحية التخزين** ثم وافق.
4. اضغط **إنشاء معاينة اليوم حتى الآن** للاختبار.
5. اضغط **إنشاء تقرير أمس الآن** للتأكد من الملفات.

## إعدادات البطارية المقترحة عبر ADB

```bat
adb shell cmd deviceidle whitelist +com.seif.screentimeexporter
adb shell cmd appops set com.seif.screentimeexporter RUN_IN_BACKGROUND allow
adb shell cmd appops set com.seif.screentimeexporter RUN_ANY_IN_BACKGROUND allow
adb shell am set-inactive com.seif.screentimeexporter false
```

لا تعمل **Force stop** للتطبيق؛ لأن Android يلغي أو يؤجل أعماله المجدولة حتى تفتحه مرة أخرى.

## ربطه لاحقًا مع Termux وRclone

بعد ظهور الملفات، يمكن لـTermux رفع الفولدر التالي يوميًا:

```bash
"$HOME/storage/shared/Seif Health/ScreenTime"
```

إلى أي مسار تختاره في Google Drive.

## ملاحظات الدقة

- التقرير يعتمد على أحداث الاستخدام التي يسجلها Android.
- قد تختلف الأرقام قليلًا عن Digital Wellbeing حسب طريقة احتساب تعدد النوافذ والأحداث التي تسجلها الروم.
- Android يحتفظ بأحداث UsageEvents لعدة أيام فقط؛ لذلك التطبيق ينشئ التقرير يوميًا بدل محاولة إعادة بناء سجل قديم جدًا.
