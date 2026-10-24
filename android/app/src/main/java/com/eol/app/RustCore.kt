package com.eol.app

object RustCore {
  init {
    System.loadLibrary("eolpiano_core")
  }

  @JvmStatic
  external fun helloworld(name: String): String
} 