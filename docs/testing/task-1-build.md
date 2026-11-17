# Evidências de build — Tarefa 1

## Toolchain e versões

As versões são fixas, sem intervalos ou versões dinâmicas, em
`gradle/libs.versions.toml`. A referência oficial já registrada em
`docs/research/2026-11-16-android-notification-collector.md` confirma para AGP
9.0.1 o Gradle 9.1.0, Build Tools 36.0.0 e Kotlin integrado 2.2.10. Por isso o
projeto aplica o plugin de Compose 2.2.10, mas **não** aplica
`org.jetbrains.kotlin.android`.

| Item | Versão/configuração |
| --- | --- |
| Android Gradle Plugin | 9.0.1 |
| Gradle Wrapper | 9.1.0 |
| Kotlin integrado / plugin Compose | 2.2.10 |
| Compose BOM | 2026.04.01 |
| `activity-compose` | 1.13.0 |
| `kotlinx-coroutines-android` | 1.10.2 |
| JUnit | 4.13.2 |
| AndroidX test runner | 1.6.2 |
| AndroidX ext JUnit | 1.2.1 |
| compile SDK / target SDK / min SDK | 36 / 36 / 26 |
| Bytecode Java / Kotlin | 17 / 17 |
| JDK do launcher Gradle | 21 |

O SDK Windows local contém a plataforma API 36 e Build Tools 36.0.0. O wrapper
Gradle foi gerado pelo `wrapper` da distribuição oficial Gradle 9.1.0
disponível no cache Windows; nenhum JAR de terceiro foi baixado ou fabricado.

### Ajuste de compatibilidade

O primeiro `:app:checkDebugAarMetadata` falhou porque o Compose BOM 2026.09.00
seleciona Compose 1.12.1, cujos AARs exigem compile SDK 37 e AGP 9.1.0. Como o
escopo fixa AGP 9.0.1/API 36 e o SDK local não oferece API 37, o BOM foi ajustado
para a versão estável 2026.04.01, que mapeia Compose para 1.11.0. A nova
verificação de metadados passou sem elevar o SDK nem alterar as demais versões
fixadas.

## Configuração de captura futura

`BuildConfig.C6_PACKAGE_NAME` recebe `c6PackageName` (vazio por padrão), com
escape de literal Java. Um valor vazio não habilita captura genérica. Nenhum
pacote bancário foi presumido.

O manifesto fonte do app não declara permissões. O manifest mesclado adiciona
somente a permissão interna `br.com.c6tag.diagnostico.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`,
protegida por `signature` e usada pelas bibliotecas AndroidX. Não há
`INTERNET`, `POST_NOTIFICATIONS` nem permissões de armazenamento. A Activity
launcher está `exported=true` e `allowBackup=false`.

## Verificação executada

- `powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:checkDebugAarMetadata` — `BUILD SUCCESSFUL`.
- `powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:assembleDebug :app:lintDebug` — `BUILD SUCCESSFUL` (48 tarefas; 36 executadas, 12 atualizadas).
- APK gerado: `app/build/outputs/apk/debug/app-debug.apk` (11.468.410 bytes).
- Lint: 0 erros e 9 avisos. Os avisos são sobre versões fixadas com atualizações disponíveis, `allowBackup` (XML de extração pertence à Tarefa 6) e ausência de ícone (nenhum ícone deve ser gerado nesta tarefa).
- `:app:testDebugUnitTest` — `NO-SOURCE`; não há testes de regra de negócio nesta tarefa e isso não é reportado como testes aprovados.
- Os dois manifests mesclados foram lidos; as únicas permissões são a interna de assinatura AndroidX descrita acima.
