
package com.lingmarket.openiconrenderer.benchmark

import com.lingmarket.openiconrenderer.api.IconExtractOptions
import com.lingmarket.openiconrenderer.api.OpenIconRenderer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

@OptIn(ExperimentalForeignApi::class)
fun dumpMain(args: Array<String>) {
    // unused - keep DumpIcon via changing entry - actually use main override
}
