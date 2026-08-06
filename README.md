# OpenIconRenderer

High-performance pure Kotlin Multiplatform library for extracting launcher icons from Android APK files.

## Targets

- JVM
- Kotlin/Native (Linux, macOS, Windows)
- JS / WasmJS / WasmWASI

## Usage

```kotlin
import com.lingmarket.openiconrenderer.api.OpenIconRenderer
import com.lingmarket.openiconrenderer.api.IconExtractOptions

val apkBytes: ByteArray = ...
val result = OpenIconRenderer.extractLauncherIcon(
    apkBytes,
    IconExtractOptions(outputSize = 432),
)
val pngBytes = result?.pngBytes
```

## Features

- ZIP central-directory indexing with selective entry reads
- Android Binary XML (AXML) parsing without string XML conversion
- `resources.arsc` resource ID resolution
- Adaptive icons, vector drawables, layer-list, shape, inset
- PNG / JPEG / WebP raster decoding
- Pure Kotlin DEFLATE and PNG codec (no external tools)

## Build

```bash
./gradlew check
```

## License

[OpenIconRenderer Source-Available License (OIR-SAL) v1.0](./LICENSE)

- **社区授权（免费）**：可使用、修改、分发，但必须保持开源（同许可证附带完整源码），不得用于闭源产品。
- **商业授权（付费）**：闭源使用须另行取得书面授权；月流水超过 **CNY ¥1,000** 的项目若要闭源使用，须支付授权费。
- **版权方例外**：版权方可自行闭源使用，并可双重许可。

Commercial licensing: contact the developer (QQ 2815968613).
