# Libraries

The libraries in this directory can be rebuilt from their sources:
* pocketfft-release.aar - [PocketFFT JNI bindings](https://gitlab.futo.org/alex/pocketfft-jni)
* vad-release.aar - [android-vad fork](https://github.com/abb128/android-vad)
* tensorflow-lite - [TensorFlow](https://github.com/tensorflow/tensorflow)

For TensorFlow Lite, optimized builds are used to reduce APK size. Read more about optimized tflite builds [here](https://www.tensorflow.org/lite/guide/reduce_binary_size). You can also pull the default unoptimized build from Maven instead if size is not a concern.

In the future it may be a better idea to pull these from a self-hosted repository or similar but for now this is simpler

## Integration notes

- These AARs are consumed directly from `libs/` via Gradle dependencies in `app/build.gradle`:

  ```groovy
  implementation(name:'vad-release', ext:'aar')
  implementation(name:'pocketfft-release', ext:'aar')
  ```

- `vad-release.aar` provides WebRTC VAD JNI bindings used for voice activity detection.
- `pocketfft-release.aar` provides FFT routines used during feature extraction.
- `tensorflow-lite` is currently retained for historical reasons; Whisper inference uses `whisper.cpp` via JNI instead of TFLite.

Rebuilding these AARs is optional for typical development; the prebuilt artifacts are sufficient.