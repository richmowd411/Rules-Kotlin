package com.squareup.anvil.sample

import android.app.Application
import com.squareup.scopes.ComponentHolder

class App : Application() {
  override fun onCreate() {
    super.onCreate()

    ComponentHolder.components += DaggerAppComponent.create()
  }
}
