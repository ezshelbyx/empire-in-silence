package com.eis.silentoptimizer.ui

import android.content.Context

/**
 * Simple process-wide context holder used by [EisViewModel] before the
 * factory-created instance receives an explicit context. Set once in
 * MainActivity.onCreate.
 */
object EisContextHolder {
    @Volatile
    var context: Context? = null
}
