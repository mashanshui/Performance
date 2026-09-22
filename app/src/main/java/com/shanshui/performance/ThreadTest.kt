package com.shanshui.performance

class ThreadTest {
    fun sdtdsf(i: Int): Int {
        val s: MutableList<String?> = ArrayList<String?>()
        s.add(i.toString())
        return (s.get(0)!!.toInt() + 10) / 5
    }
}
