The `termux/termux-app` repository is released under [GPLv3 only](https://www.gnu.org/licenses/gpl-3.0.html) license.

### Exceptions

- [Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator) code is used which is released under [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) license. Check [`terminal-view`](terminal-view) and [`terminal-emulator`](terminal-emulator) libraries.
- Check [`termux-shared/LICENSE.md`](termux-shared/LICENSE.md) for `termux-shared` library related exceptions.
- The bundled [`terminal_default.ttf`](app/src/main/res/font/terminal_default.ttf) is JetBrains Mono Regular 1.0.3, licensed under Apache 2.0. Its [license and attribution](app/src/main/assets/licenses/jetbrains-mono.txt) are included in the APK.

- The Ghostty JNI bridge, Vulkan renderer and Android patches are adapted from [XYenon/termux-app-ghostty](https://github.com/XYenon/termux-app-ghostty/tree/9bdfe0b90bbe3e21cd6070e488d9d38abe3af3e9).
- [Ghostty](https://github.com/ghostty-org/ghostty) is distributed under the [MIT license](app/src/main/assets/licenses/ghostty.txt).
- This software is based in part on the work of the [FreeType Team](https://freetype.org), under the [FreeType Project License](app/src/main/assets/licenses/freetype.txt).
- [HarfBuzz](https://github.com/harfbuzz/harfbuzz) uses the [Old MIT license](app/src/main/assets/licenses/harfbuzz.txt); [libpng](https://github.com/pnggroup/libpng) uses the [PNG Reference Library license](app/src/main/assets/licenses/libpng.txt). These notices are included in the APK.
