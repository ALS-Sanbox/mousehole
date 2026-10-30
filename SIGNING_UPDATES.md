# Mouse Hole — Install newer APKs without uninstalling

Android only permits an APK update if its application ID and signing certificate match the installed APK and its version code increases.

The application ID remains `com.alssanbox.mousehole`. GitHub Actions now increments the version code. Use the **same private signing key** for each build:

1. On your PC with Java installed, generate a new key: `keytool -genkeypair -v -keystore mousehole-update.jks -alias mousehole -keyalg RSA -keysize 3072 -validity 10000`.
2. Back up `mousehole-update.jks` securely, along with the passwords. **Never commit it to GitHub.**
3. On Windows PowerShell, convert it to base64: `[Convert]::ToBase64String([IO.File]::ReadAllBytes('mousehole-update.jks'))`.
4. In this GitHub repository, open **Settings → Secrets and variables → Actions → New repository secret** and create:
   - `MOUSEHOLE_KEYSTORE_BASE64`: the base64 string.
   - `MOUSEHOLE_KEYSTORE_PASSWORD`: your keystore password.
   - `MOUSEHOLE_KEY_ALIAS`: `mousehole`.
   - `MOUSEHOLE_KEY_PASSWORD`: your key password.
5. Run the **Build Mouse Hole APK** workflow on `main` again. The resulting APK and future builds can upgrade each other if you keep using the same key.

**One-time transition:** Previous APKs from GitHub Actions were signed using temporary debug keys created on different runners. You may need to uninstall the old app **once** before installing the first APK signed with your persistent key. After that, updates will install over it. Keep your signing key forever.

If secrets are not configured, GitHub Actions still makes a regular debug APK and prints a warning: that APK might not upgrade existing installs.
Before uninstalling the old version, back up any files stored only in its private storage. Photos/videos already copied into Pictures/MouseHole and Movies/MouseHole via MediaStore are independent of app-private storage.