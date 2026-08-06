package com.lingmarket.openiconrenderer

import com.lingmarket.openiconrenderer.api.IconExtractOptions
import com.lingmarket.openiconrenderer.api.OpenIconRenderer

fun OpenIconRenderer.extractLauncherIconFromFile(
    path: String,
    options: IconExtractOptions = IconExtractOptions(),
) = extractLauncherIcon(path, options)
