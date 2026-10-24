package com.jjmluz.photostv

import com.jjmluz.photostv.data.PhotosApi
import okhttp3.Interceptor

// Release builds always use the real library; the demo lives in the debug source set.

fun demoApi(): PhotosApi? = null

fun demoImages(): Interceptor? = null
