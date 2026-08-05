# ADR 0001: Native Compose application

Status: accepted

999 Vault Android is a Kotlin, single-activity Jetpack Compose/Material 3 app. A WebView or cross-platform shell would not satisfy Android playback, storage, accessibility, background-transfer, and lifecycle requirements. Android framework types remain at module edges and `:core:model` stays platform-free.

