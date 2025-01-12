package com.irfanerenciftci.bacak_tespiti_uygulama

data class BoundingBox(


    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val score: Float,
    val classIndex: Int,
    val className: String



)
