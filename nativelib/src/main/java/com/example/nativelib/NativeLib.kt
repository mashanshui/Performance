package com.example.nativelib

class NativeLib {

    /**
     * A native method that is implemented by the 'nativelib' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String

    external fun getCpuTime(): Float

    external fun bindMainThreadToMaxCore(): Boolean

    external fun bindCurrentThreadToMaxCore(): Boolean

    external fun openGcInhibit(seconds: Int)

    external fun closeGcInhibit()

    companion object {
        // Used to load the 'nativelib' library on application startup.
        init {
            System.loadLibrary("nativelib")
        }
    }
}
