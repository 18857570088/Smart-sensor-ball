# Smart sensor ball Android

This folder contains a native Android app project for Smart sensor ball.

Implemented features:

- 3, 2, 1 countdown before training starts
- 30-second and 60-second training modes
- Live large-number hit counter
- Automatic post-session report:
  - total hit count
  - average frequency (hits per second)
  - best 3-second burst
- Microphone-based hit detection adapted from the tuned desktop Python version
- Bundled light-hit template based on `hit_template_light.npz`

Notes for opening/building:

- This environment did not have Gradle or Android SDK tools installed, so the project was scaffolded but not built locally here.
- Open the `sensorball-android` folder in Android Studio.
- Let Android Studio sync the project and download the required Android/Gradle components.
- After sync, run the app on an Android phone and grant microphone permission.
