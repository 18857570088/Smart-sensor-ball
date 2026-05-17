# Codemagic IPA Build Guide

This project is prepared for Windows source maintenance plus cloud macOS builds.
Windows cannot create a valid signed iOS IPA by itself. Codemagic supplies the
macOS/Xcode build machine.

## 1. Push the project to GitHub

Create a private GitHub repository, for example:

```bash
smart-sensor-ball-ios
```

From this directory:

```bash
git init
git add .
git commit -m "Prepare Smart sensor ball iOS project"
git branch -M main
git remote add origin https://github.com/<your-account>/smart-sensor-ball-ios.git
git push -u origin main
```

## 2. Connect Codemagic

1. Log in to Codemagic.
2. Connect the GitHub private repository.
3. Select the project root containing `codemagic.yaml`.
4. Run `ios-simulator-check` first. This does not require Apple signing.

## 3. Enable signed IPA builds

Signed IPA builds require Apple Developer Program membership.

1. Create the Bundle ID `com.zclei.smartsensorball`.
2. Create the app `Smart sensor ball` in App Store Connect.
3. In App Store Connect, create an API key with access for signing and upload.
4. In Codemagic, add the Apple Developer Portal / App Store Connect integration named:

```text
Codemagic App Store Connect API
```

5. Add a Codemagic environment variable group named `code-signing`.
6. Add the secret variable `CERTIFICATE_PRIVATE_KEY` to that group. The value must be a 2048-bit RSA private key including the `-----BEGIN RSA PRIVATE KEY-----` and `-----END RSA PRIVATE KEY-----` lines.
7. Run the `ios-signed-ipa` workflow.

You can generate a new signing private key on macOS or Linux with:

```bash
ssh-keygen -t rsa -b 2048 -m PEM -f ios_distribution_private_key -q -N ""
```

Open `ios_distribution_private_key` as text and paste the full content into Codemagic as `CERTIFICATE_PRIVATE_KEY`.

The workflow will run:

```bash
app-store-connect fetch-signing-files "$BUNDLE_ID" --type IOS_APP_STORE --create
```

This lets Codemagic fetch or create the matching App Store provisioning profile and distribution certificate for `com.zclei.smartsensorball`.

The generated IPA will be available from the Codemagic build artifacts.
When TestFlight is ready, change `submit_to_testflight` in `codemagic.yaml`
from `false` to `true`.

## 4. Expected build settings

- Project: `SmartSensorBall.xcodeproj`
- Scheme: `SmartSensorBall`
- Bundle ID: `com.zclei.smartsensorball`
- App name: `Smart sensor ball`
- Deployment target: iOS 15+

## 5. Hardware smoke test after installing

1. Open the app and grant Bluetooth permission.
2. Scan for devices with the `SENBALL#` prefix.
3. Connect the device and confirm the app sends `C5 5C 04 00`.
4. Start training and confirm `C5 5C 04 01` is sent after `3, 2, 1, GO`.
5. Confirm telemetry packets use header `D5 5D 03`.
6. Confirm byte 4 shows battery and byte 5 increases the punch count.
7. End training and confirm `C5 5C 04 00` is sent.
