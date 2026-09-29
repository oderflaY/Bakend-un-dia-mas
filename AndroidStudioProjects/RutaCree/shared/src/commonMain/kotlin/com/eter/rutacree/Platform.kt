package com.eter.rutacree

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform