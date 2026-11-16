# OpenJarvis Android adaptation for JARVIS 3.13

## Scope

This branch starts an Android-native adaptation inspired by OpenJarvis' five-primitives architecture. It does **not** embed or install the upstream Python package. The upstream project expects a Python environment and desktop/server-oriented dependencies; importing that stack directly into an Android APK would add incompatible runtime assumptions and substantial build weight.

## Existing JARVIS components mapped to the primitives

| OpenJarvis primitive | Existing Android component |
|---|---|
| Intelligence | `LocalModelManager` and the selected GGUF model |
| Engine | `LocalLlamaRuntime` + the existing llama.cpp JNI/native build |
| Agents | Existing chat orchestration in `MainActivity`; a dedicated tool-using agent remains future work |
| Tools & Memory | `JarvisFreeWebResearchProvider`, `KnowledgeLibrary`, `JarvisLearningEngine`, shared storage |
| Learning | `JarvisLearningEngine` records and sessions |

## First integration slice

- Adds `OpenJarvisAndroidKernel` as a stable Android boundary.
- Routes local model generation through that boundary instead of calling `LocalLlamaRuntime` directly from the chat screen.
- Exposes a health snapshot for the local engine, stored knowledge, imported documents, and current research provider.
- Leaves existing model loading, storage, chat history, permissions, and UI untouched.

## Important limitations

- This is an architectural adaptation, not the upstream OpenJarvis runtime.
- The current free web provider is a focused Spanish Wikipedia/MediaWiki search, not unrestricted web search.
- `JarvisLearningEngine` persists and retrieves knowledge; it does not fine-tune model weights or autonomously retrain the local model.
- External ChatGPT/Claude/Gemini teacher adapters are not implemented by this change and require user-supplied provider credentials or a genuinely free provider.
- No API keys, tokens, or secrets belong in source control.

## Validation status

The source changes must pass the Android Gradle build and be tested on a physical Android device before this integration can be called complete. A GitHub commit alone is not proof that the APK works.
