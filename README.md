# Vasu Traders

This standalone Android Studio project builds `com.vasutraders`. Customers can register, browse the priced catalog, choose quantities, review their cart and delivery address, and follow submitted orders.

## Local emulator

Run the loopback demo backend in the sibling `cab-api` project using `./scripts/run-traders-local.sh`.

```sh
./gradlew -PtradersDebugApiRoot=http://10.0.2.2:8094 :app:assembleDebug
```

The emulator APK is under `app/build/outputs/apk/debug`. Register with a sample mobile number and use demo code `123456` to set a password. Sign in again with the same mobile number and password. Manage catalog prices and delivery dates through the local Trade Admin page at `http://127.0.0.1:3000/tradeadmin.html`.

## Google Play release

The app targets Android 16 (API level 36). Build a signed Android App Bundle with the
app’s dedicated PKCS#12 upload key. Use the `upload` alias and the same password for
the store and key. The local signing files created for this app are under
`~/Library/Application Support/Vasu Traders Upload Signing/customer`.

```sh
signing_dir="$HOME/Library/Application Support/Vasu Traders Upload Signing/customer"
export TRADERS_UPLOAD_STORE="$signing_dir/upload.p12"
export TRADERS_UPLOAD_PASSWORD="$(cat "$signing_dir/password.txt")"
./gradlew :app:bundleRelease
```

The signed bundle is `app/build/outputs/bundle/release/app-release.aab`.
Builds default to `https://api.vasucabs.com` and support another deployed HTTPS API
origin via `-PtradersApiRoot`. Deploy the Traders password API and schema v2 first.
SMS is used for account registration and password setup or recovery.

Publish the Traders account page and use its `#privacy` and `#delete-account`
links in Play Console. The app links to `https://vasucabs.com/traders-account.html`
by default. Set `-PtradersAccountUrl` if you host the page on another domain.
Provide a dedicated password-enabled customer account under App access and
complete the Data safety and store listing fields before submission.
