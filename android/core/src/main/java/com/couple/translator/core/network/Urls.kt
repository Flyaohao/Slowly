package com.couple.translator.core.network

/**
 * 把后端返回的相对资源路径（如 "/uploads/museum/x.jpg"）拼成完整 URL。
 * Coil 等图片加载器无法直接加载相对路径 —— 此前头像传相对路径给 Coil 一直加载不出来。
 */
fun String?.toAbsoluteUrl(): String? {
    if (isNullOrBlank()) return null
    val path = trim()
    if (path.startsWith("http")) return path
    // 两侧各补一次斜杠。后端目前一律返回 "/uploads/..." 形式，拼出来与旧写法逐字节相同；
    // 这里兜住的是「漏了前导斜杠」的情况——旧写法会拼出
    // "http://host:8000uploads/x.png" 这种必然 404 的死链，而且错得很安静。
    return NetworkModule.DEFAULT_BASE_URL.trimEnd('/') + "/" + path.trimStart('/')
}
