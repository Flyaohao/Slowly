package com.couple.translator.core.network

/**
 * 把后端返回的相对资源路径（如 "/uploads/museum/x.jpg"）拼成完整 URL。
 * Coil 等图片加载器无法直接加载相对路径 —— 此前头像传相对路径给 Coil 一直加载不出来。
 */
fun String?.toAbsoluteUrl(): String? {
    if (isNullOrBlank()) return null
    return if (startsWith("http")) this
    else NetworkModule.DEFAULT_BASE_URL.trimEnd('/') + this
}
