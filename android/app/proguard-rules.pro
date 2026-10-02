# Retrofit
-keepattributes Signature
-keepattributes Exceptions
# 泛型签名（ApiResponse<T> 的 T）在 release 必须保留，否则 R8 抹掉后
# Moshi/Retrofit 反射解析泛型会抛 Class cannot be cast to ParameterizedType
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Moshi
-keep class com.squareup.moshi.** { *; }
# 注意：下面这几条原先指向 com.couple.translator.data.model / .network，
# 那是v2.0 前的旧包名，工程里**从来不存在**，keep 规则静默不匹配（不报错、
# 也不生效），于是该保的类被混淆掉了。2026-10-02 按真实路径修正。
-keep class com.couple.translator.core.data.model.** { *; }
-keep class com.couple.translator.feature.couple.data.model.** { *; }
-keep class com.couple.translator.feature.single.data.model.** { *; }
# ApiResponse / PagedResponse 都在 core.network（同文件）
-keep class com.couple.translator.core.network.ApiResponse { *; }
-keep class com.couple.translator.core.network.PagedResponse { *; }
# 兜底：任何走 KotlinJsonAdapterFactory 反射的 data class，其泛型签名不能被抹
-keepclassmembers,allowobfuscation class * {
    @com.squareup.moshi.* <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
