# Craft Anything + Ghidra 12.1.4 (Android ARM64)

Plugin nativo preinstalado para **Antigravity Android (`aarch64-linux-android`)** que integra el motor completo de **Craft Anything** (**302 herramientas MCP** compiladas en Rust para Bionic ARM64) junto con los binarios nativos **ARM64 de Ghidra 12.1.4** (`decompile` y `sleigh`) e instalador inteligente bajo demanda.

> [!TIP]
> **¿Cómo funciona al activarlo?**
> 1. **Nivel 1 — Motor Rápido en Rust (Activo al instante, 0 descargas):** En cuanto activas este plugin, tienes disponibles las **302 herramientas MCP** con desensamblador y decompilador pseudo-C integrado (`<50 MB` de RAM, sin Java).
> 2. **Nivel 2 — Superpotencia Ghidra 12.1.4 Nativo ARM64 (Bajo demanda):** Cuando quieras análisis profundo de tipos C++/vtables, grafos globales de referencias cruzadas (`xrefs`) o ejecutar `analyzeHeadless`, simplemente escríbele al asistente en el chat: **«Instala Ghidra»** (o ejecuta `craft-ghidra-setup` en la terminal).

---

## Qué incluye este plugin

| Componente | Descripción |
| :--- | :--- |
| **`craft-anything` MCP Server** | Binario nativo `craft-anything-cli` (`aarch64-linux-android`, PIE, 16 KB page-aligned) ejecutándose en modo `--deep mcp --all-tools` con **302 herramientas**. |
| **Ghidra 12.1.4 ARM64 Binaries** | Binarios nativos Bionic ARM64 `decompile` y `sleigh` precompilados con Android NDK e inyectados automáticamente. |
| **`craft-ghidra-setup`** | Comando automático de 1 paso para descargar e instalar bajo demanda **OpenJDK 21 + Ghidra 12.1.4 Public** (~450 MB) usando `pkg`, verificando SHA-256 y eliminando el `.zip` al instante. |
| **3 Skills Especializados** | `craft-anything` (investigación basada en evidencia), `craft-rebuild` (estudio, portado y reconstrucción Clean-Room / Deep), y `ghidra-arm64` (descompilación nativa en Android). |
| **Reglas (`rules/AGENTS.md`)** | Contexto automático de rutas Android (`$HOME`, `$PREFIX`, directorios temporales sin puntos para Ghidra), recomendación proactiva de Ghidra y buenas prácticas de memoria en móvil. |

---

## Niveles de Potencia

### 1. Inspección multi-formato inmediata (sin requerir Java/Ghidra)
- Ejecutables y librerías nativas **ELF (`.so`), PE (`.exe`/`.dll`), Mach-O** (desensamblador y pseudo-C integrado en Rust).
- Paquetes **Android APK / DEX / AAB**, manifiestos, recursos y tablas de símbolos JNI.
- Aplicaciones **Electron (`.asar`)**, bundles JavaScript/TypeScript y mapas de módulos (`js_app_map`).
- Ensamblados **.NET / CIL**, módulos **WebAssembly (WASM)**, binarios Go y motores de videojuegos (Unity, Godot, Unreal, GameMaker, RPG Maker).

### 2. Descompilación profunda con Ghidra 12.1.4 ARM64 (bajo demanda)
- Desbloquea el decompilador C/C++ oficial de Ghidra (NSA), recuperación avanzada de structs/`vtables` C++, análisis global de referencias cruzadas y `analyzeHeadless`.
- Pídele al agente en el chat **«Instala Ghidra»** o ejecuta una sola vez:
  ```sh
  craft-ghidra-setup
  ```
- El servidor MCP detectará automáticamente `$PREFIX/opt/ghidra/ghidra_12.1.4_PUBLIC` y usará los binarios nativos ARM64 `decompile` y `sleigh` sin necesidad de reiniciar la app.

### 3. Portado y reconstrucción de software (`craft-rebuild`)
- Estudio de arquitectura, inventario de assets/rutas, registro de hipótesis/hallazgos con IDs de evidencia (`ev_...`) y reconstrucción paso a paso hacia Android (Kotlin/Compose, NDK/SDL), Web (React/Vite) o Rust.
