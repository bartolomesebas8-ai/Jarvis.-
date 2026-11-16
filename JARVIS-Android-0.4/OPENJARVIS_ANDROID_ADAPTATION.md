# OpenJarvis Android adaptation for JARVIS 3.13

## Scope

This is an Android-native adaptation inspired by OpenJarvis' composable architecture. It does **not** embed or install the upstream Python runtime. The Android app continues to use its existing Kotlin, llama.cpp and GGUF model stack.

## Integrated components

| OpenJarvis concept | JARVIS 3.13 component |
|---|---|
| Intelligence | Existing GGUF model and LocalModelManager |
| Engine | OpenJarvisAndroidKernel -> LocalLlamaRuntime -> llama.cpp |
| Agents | Existing chat orchestration in MainActivity |
| Tools and Memory | JarvisFreeWebResearchProvider, KnowledgeLibrary, JarvisLearningEngine and shared storage |
| Learning | JarvisLearningEngine persistent records and sessions |

## What this integration changes

- Adds OpenJarvisAndroidKernel as the Android-side boundary for local generation.
- Routes the chat screen's local inference through that boundary.
- Exposes a health snapshot with engine state, model path, learning records/sessions, imported-document count and research provider.
- Reuses the current Qwen/GGUF loader and native engine; it does not add a second model runtime.
- Preserves existing chat, memory, storage, permissions and web-research flows.

## Current limitations

- This is an architectural adaptation, not the upstream OpenJarvis Python package.
- The current free research provider is Wikipedia/MediaWiki in Spanish, not unrestricted internet search.
- JarvisLearningEngine stores and retrieves information; it does not fine-tune model weights or autonomously rewrite application code.
- External ChatGPT/Claude/Gemini teacher connections are not part of this slice and require a provider with valid credentials or a genuinely free service.
- Never commit API keys, tokens or other secrets.

## Validation

A green Android build confirms compilation and packaging, not successful model inference on every phone. Qwen 3 still needs a device test after installing the newly generated APK.
