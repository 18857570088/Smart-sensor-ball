# TestFlight Test Plan

## Pre-Test Setup

1. Install the TestFlight build.
2. Grant Bluetooth permission when prompted.
3. Ensure the training device is powered on and named with the `SENBALL#` prefix.

## Smoke Tests

1. Launch app for the first time.
2. Confirm the app prompts the user to open Settings and connect Bluetooth.
3. Open Settings.
4. Tap Scan.
5. Confirm only `SENBALL#` devices appear.
6. Select and connect a device.
7. Confirm connection status turns active and battery appears on the home screen.

## Training Tests

1. Tap Start.
2. Confirm the app shows `3, 2, 1, GO`.
3. Confirm iOS sends `C5 5C 04 01` after the countdown.
4. Hit the ball and confirm punch count increments from data2.
5. Let training finish or tap End.
6. Confirm iOS sends `C5 5C 04 00`.

## Regression Tests

- Disconnect while training.
- Start training without Bluetooth connected.
- Switch among Chinese, English, French, and Thai.
- Open privacy policy and user agreement.

