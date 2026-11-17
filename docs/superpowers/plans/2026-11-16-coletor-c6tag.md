# Coletor C6 Tag — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produzir um APK local de diagnóstico que registre eventos do C6 e permita validar sua interpretação antes de desenvolver saldo ou alertas.

**Architecture:** Um aplicativo Android de módulo único. O listener filtra a origem antes de ler o conteúdo; um módulo de diagnóstico preserva cada evento em SQLite privado e classifica os textos sem produzir movimentações financeiras. Compose apresenta estado, histórico, detalhes, limpeza e exportação explícita.

**Tech Stack:** Kotlin nativo, Jetpack Compose, Android SDK, SQLiteOpenHelper, coroutines, JUnit e testes instrumentados AndroidX. Sem servidor, Room/KSP, injeção de dependência externa ou permissão INTERNET.

---

## Documentos, decisões e limites

- Revisão independente do plano: aprovada, sem bloqueios. Pesquisa Android
  incorporada; uma recomendação de exported foi corrigida pelo exemplo oficial.
- Especificação aprovada: `docs/superpowers/specs/2026-11-16-coletor-c6tag-design.md`.
- Pesquisa: `docs/research/2026-11-16-android-notification-collector.md`.
- Usar @test-driven-development ao implementar e @verification-before-completion antes de afirmar conclusão.
- O escopo termina no diagnóstico; não implementar saldo, limiar, alerta, push,
  recarga ou comunicação entre aparelhos.
- Não criar Git/worktree nem commits neste diretório sem autorização: não existe
  repositório. Os checkpoints abaixo são verificações, não commits obrigatórios.
- Nenhuma movimentação bancária será automatizada ou exigida. Testes naturais
  podem permanecer pendentes e devem ser reportados assim.
- Instalar o APK e habilitar acesso especial só com o aparelho do usuário disponível.
- Não executar `dumpsys notification --noredact`, não coletar extratos e não
  copiar dados pessoais para fixtures, relatórios ou Logcat.

## Evidência e ambiente

### Alegação a estabelecer

O APK consegue preservar e explicar eventos observados do C6, distinguindo
callbacks, fotografia inicial, resumos, remoções e candidatos a movimentos,
sem confundir esse histórico com prova de captura completa ou saldo bancário.

### Caminhos considerados

1. Só capturas de tela: evidenciam conteúdo visual, não identificadores/callbacks.
2. Só testes sintéticos: repetíveis, mas não provam o comportamento do C6.
3. **Escolhido:** testes determinísticos + testes Android + matriz manual no S23.

A capacidade durável de diagnóstico é o próprio produto desta etapa. Não criar
emissor de notificações falsas no APK nem abrir exceção para outro pacote em
produção. Fixtures são dados sanitizados de teste.

### Ferramentas encontradas, ainda não validadas com build

- Projeto: `C:\Users\Victor\dev_mob\notification_app`.
- Android SDK: `C:\Users\Victor\AppData\Local\Android\Sdk`.
- JDK 21: `C:\Program Files\Java\jdk-21`.
- Android Studio disponível; JBR padrão é Java 25, por isso preferir JDK 21.
- Gradle 9.1.0 em cache Windows; SDK API 36 e build-tools 36 instalados.
- Execução Windows de `gradle --version` confirmada com Gradle 9.1.0 e JDK
  21.0.9; isso valida o launcher, não o build do projeto.
- Dependências em cache: AGP 9.0.1, Kotlin 2.2.10, Compose BOM 2026.09.00,
  activity-compose 1.13.0 e coroutines 1.10.2.
- O WSL não tem Java, Gradle ou ADB no PATH. Usar execução Windows, não misturar
  um Gradle Linux com build-tools Windows.
- As versões finais devem passar pela confirmação de compatibilidade da Tarefa 1.
  Cache comprova presença, não compatibilidade.

### Política de build

Usar um conjunto fixo, sem versões dinâmicas: AGP 9.0.1, Gradle 9.1.0,
Kotlin integrado ao AGP 9 (sem plugin `org.jetbrains.kotlin.android`),
plugin Compose compatível com o Kotlin integrado, BOM 2026.04.01,
activity-compose 1.13.0 e coroutines-android 1.10.2.

Ajuste comprovado na Tarefa 1: BOM 2026.09.00 selecionava AARs que exigiam
compileSdk 37 e AGP 9.1.0. BOM 2026.04.01 passou na verificação de metadados e
no build com API 36/AGP 9.0.1. Ver `docs/testing/task-1-build.md`.

Baseline: minSdk 26; compileSdk/targetSdk 36; bytecode Java/Kotlin 17,
Gradle executado com JDK 21. Um Android mais recente pode executar um APK
target 36; confirmar restrições reais no aparelho. Não exigir API 37 apenas
porque o usuário informou Android 17. Registrar a versão real via `getprop`.

Dependências de teste: JUnit 4.13.2, AndroidX test runner 1.6.2,
AndroidX test ext JUnit 1.2.1 e Compose UI test alinhado ao BOM.
Confirmar minCompileSdk transitivo antes de fechar o scaffold; se alguma
dependência exigir SDK superior, fixar uma versão estável compatível com API 36
e registrar a alteração no plano antes de prosseguir.

## Mapa de arquivos

Pacote do APK: `br.com.c6tag.diagnostico`; nome visível: `C6 Tag Diagnóstico`.
O APK é independente do aplicativo do banco, sem sugerir vínculo oficial.

```text
settings.gradle.kts
build.gradle.kts
gradle.properties
gradle/libs.versions.toml
gradle/wrapper/gradle-wrapper.properties
gradle/wrapper/gradle-wrapper.jar
gradlew
gradlew.bat
.gitignore
README.md
tools/gradle.ps1
tools/adb.ps1
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/res/values/strings.xml
app/src/main/res/xml/backup_rules.xml
app/src/main/res/xml/data_extraction_rules.xml
app/src/main/java/br/com/c6tag/diagnostico/
  CollectorApplication.kt
  MainActivity.kt
  diagnostic/DiagnosticEvent.kt
  diagnostic/NotificationClassifier.kt
  diagnostic/DiagnosticStore.kt
  diagnostic/DiagnosticDatabase.kt
  diagnostic/DiagnosticRecorder.kt
  diagnostic/DiagnosticExporter.kt
  capture/C6NotificationListener.kt
  capture/NotificationSnapshotMapper.kt
  capture/CaptureStatus.kt
  ui/DiagnosticViewModel.kt
  ui/DiagnosticScreen.kt
  ui/EventDetail.kt
app/src/test/java/br/com/c6tag/diagnostico/
  diagnostic/NotificationClassifierTest.kt
  diagnostic/DiagnosticRecorderTest.kt
app/src/androidTest/java/br/com/c6tag/diagnostico/
  diagnostic/DiagnosticDatabaseTest.kt
  diagnostic/DiagnosticExporterTest.kt
  capture/NotificationSnapshotMapperTest.kt
  capture/ManifestPrivacyTest.kt
  ui/DiagnosticScreenTest.kt
docs/testing/s23-diagnostic-checklist.md
docs/testing/verification-results.md
```

Responsabilidades:

- `DiagnosticEvent`: modelos sem dependência Android; tipo de evento, conteúdo,
  interpretação e metadados, sem métodos financeiros.
- `NotificationClassifier`: função pura de classificação e valor em centavos.
- `DiagnosticStore`: pequena interface interna de persistência para o recorder,
  não um framework genérico de repositórios.
- `DiagnosticDatabase`: esquema SQLite, consultas paginadas e manutenção.
- `DiagnosticRecorder`: fila serial, filtro exato e persistência, sem deduplicação.
- `DiagnosticExporter`: documento JSON versionado a partir de snapshot consistente.
- `NotificationSnapshotMapper`: leitura controlada dos campos Android.
- `CaptureStatus`: estado transitório de conexão/erros, não inferido do último
  registro persistido de uma execução antiga.
- `CollectorApplication`: composição manual; uma instância de banco, recorder
  e status compartilhados por UI e listener.

## Tarefa 1 — Ferramentas, scaffold e build reproduzível

**Status:** implementada, build/lint verificados e duas revisões aprovadas.
Limitação menor registrada: preflight de múltiplos dispositivos do wrapper ADB
cobre servidor padrão; teste planejado usa servidor local, sem -H/-P/-L.

**Files:** arquivos Gradle/wrapper, `.gitignore`, `tools/gradle.ps1`,
`tools/adb.ps1`, manifest mínimo, recursos mínimos, `CollectorApplication.kt`,
`MainActivity.kt`, `README.md`.

- [ ] 1. Confirmar a pesquisa e o conjunto de versões acima nas fontes oficiais;
  registrar qualquer ajuste antes de adicionar dependências.
- [ ] 2. Criar `tools/gradle.ps1`: resolver a raiz a partir de `$PSScriptRoot`,
  configurar JAVA_HOME para JDK 21 e ANDROID_HOME para o SDK encontrado,
  encaminhar argumentos com `@args` para `gradlew.bat` e propagar o exit code.
  Falhar com instrução objetiva se ferramenta ausente; não instalar nada.
- [ ] 3. Criar `tools/adb.ps1` para encaminhar argumentos ao
  `platform-tools\adb.exe`, sem serial hardcoded e propagando exit code.
  Se houver múltiplos aparelhos, exigir escolha explícita com `-s`.
- [ ] 4. Localizar o Gradle já extraído no cache Windows e gerar o wrapper
  oficial 9.1.0 em um diretório temporário estreito ou scaffold mínimo.
  Não baixar JAR de terceiros nem fabricar um wrapper textual.
- [ ] 5. Criar o módulo app com versões fixas e tela de texto mínima. Ativar
  BuildConfig. Ler a origem de `providers.gradleProperty("c6PackageName").orElse("")`
  e gerar `BuildConfig.C6_PACKAGE_NAME` com escape seguro.
  Origem vazia significa captura desativada, não “capturar tudo”.
- [ ] 6. Configurar testes e Java/Kotlin 17; omitir INTERNET, POST_NOTIFICATIONS
  e permissões de armazenamento. Não existe alerta próprio nesta fase.
- [ ] 7. Executar:
  `powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:assembleDebug :app:lintDebug`.
  Esperado: exit 0, APK em `app/build/outputs/apk/debug/app-debug.apk`.
- [ ] 8. Inspecionar o manifest mesclado e registrar o baseline. Não afirmar
  captura funcional só porque o APK compilou.

No `.gitignore`, excluir `.gradle/`, `**/build/`, `local.properties`, dumps,
exportações e dados pessoais; preservar documentação sanitizada e wrapper.

## Tarefa 2 — Modelos e interpretação determinística (RED → GREEN)

**Status:** implementada; 19 testes JVM aprovados, build/lint sem erros e duas
revisões aprovadas. Evidência: `docs/testing/task-2-classifier.md`.

**Files:** `DiagnosticEvent.kt`, `NotificationClassifier.kt`,
`NotificationClassifierTest.kt`.

- [ ] 1. Escrever testes antes da implementação, com fixtures sanitizadas:

```kotlin
@Test fun debitUsesChargeValueNotDateOrRoadNumber() {
    val text = "Você usou seu tag no dia 16/11/2026 às 13:37. " +
        "Valor a debitar R$ 16,91. Pedágio SP425 4001 LOCAL."
    val result = classifier.classify(
        title = "Débito C6 Tag", text = text, bigText = null,
        lines = emptyList(), groupSummary = false
    )
    assertEquals(CandidateKind.TAG_DEBIT, result.kind)
    assertEquals(1691L, result.amountCents)
}

@Test fun pixCreditUsesBrazilianAmount() {
    val result = classifier.classify(
        title = "Você recebeu um Pix",
        text = "Pix recebido no valor de R$ 1.234,56, em 16/11/2026.",
        bigText = null, lines = emptyList(), groupSummary = false
    )
    assertEquals(CandidateKind.PIX_CREDIT, result.kind)
    assertEquals(123456L, result.amountCents)
}
```

- [ ] 2. Executar `tools/gradle.ps1 :app:testDebugUnitTest` via PowerShell.
  Esperado: falha por modelos/classificador inexistentes, não por toolchain.
- [ ] 3. Implementar modelos: tipo `POSTED_OR_UPDATED`, `REMOVED`, `ACTIVE_SNAPSHOT`,
  `LISTENER_CONNECTED`, `LISTENER_DISCONNECTED`; origem e horários separados;
  payload textual e metadados opcionais para eventos de conexão.
- [ ] 4. Implementar classificação conservadora: resumo tem precedência;
  título e frase de valor precisam corresponder ao formato esperado.
  Inspecionar bigText, text e lines sem concatenar versões repetidas em um
  suposto lançamento. Aceitar apenas uma interpretação monetária inequívoca;
  discordância entre campos resulta em UNKNOWN com motivo.
- [ ] 5. Interpretar BRL com agrupamento válido e duas casas decimais;
  converter por operações inteiras com detecção de overflow. Valor malformado,
  ausente ou ambíguo nunca vira zero nem candidato válido.
- [ ] 6. Adicionar RED/GREEN para resumo com valor, texto oculto/ausente,
  título desconhecido com R$, bigText completo versus text truncado,
  valores conflitantes, centavos, milhares, overflow e mesmos valores
  em dois objetos distintos. Remoção não representa crédito/estorno.
- [ ] 7. Rodar a suíte; esperado: todos os testes passam, nenhuma dependência Android.

## Tarefa 3 — Histórico SQLite e recorder serial

**Status:** implementada e aprovada nas duas revisões, após reforço de cobertura.
29 testes JVM e 6 SQLite instrumentados no emulador read-only; produção não mudou
na rodada de reforço. Evidência: `docs/testing/task-3-storage.md`.

**Files:** `DiagnosticStore.kt`, `DiagnosticDatabase.kt`, `DiagnosticRecorder.kt`,
`DiagnosticDatabaseTest.kt`, `DiagnosticRecorderTest.kt`.

- [ ] 1. Escrever testes JVM do recorder com store fake e tempo controlável:
  pacote diferente descartado antes de leitura de conteúdo; pacote vazio
  desativa captura; duas chamadas com a mesma chave geram duas inserções.
- [ ] 2. Rodar a suíte e observar RED pela ausência do recorder.
- [ ] 3. Implementar interface interna `append(event)`, `page(beforeId, limit)`,
  `clear()` e `exportSnapshot()`; limites explícitos, sem carregar todo o
  histórico na lista. Métodos de disco executados em Dispatchers.IO.
- [ ] 4. Escrever teste instrumentado do banco: inserir dois registros com
  mesma chave, fechar/reabrir, recuperar os dois com IDs distintos e conteúdo
  preservado. Testar nulls, Unicode, paginação estável e limpeza.
- [ ] 5. Implementar SQLiteOpenHelper v1: `events(id INTEGER PRIMARY KEY
  AUTOINCREMENT, observed_at_ms INTEGER NOT NULL, kind TEXT NOT NULL,
  payload_json TEXT NOT NULL)`. JSON versionado preserva campos opcionais
  separadamente; sem índices únicos de notificação e sem migração destrutiva.
- [ ] 6. Implementar fila serial de persistência com falha observável.
  Filtro do pacote precede mapeamento; conexão/desconexão não tem pacote alvo.
  Manter ordem de chegada e de limpeza. Não usar fila que descarte silenciosamente
  eventos; se houver saturação/erro, sinalizar diagnóstico incompleto.
- [ ] 6a. Documentar retenção manual durante o experimento: o histórico cresce
  até limpeza explícita; não há descarte por idade nem rotação silenciosa.
  Para uso prolongado, discutir política antes de ampliar o diagnóstico.
- [ ] 7. Testar falha de insert sem Logcat com conteúdo, múltiplos callbacks,
  remoção após postagem e fotografia após reconexão. Limpeza enfileirada remove
  o passado; eventos posteriores podem naturalmente aparecer.
- [ ] 8. Rodar JVM e instrumentados; banco só deve ser chamado no aparelho/emulador.
  Sem aparelho, declarar instrumentados pendentes, não substituir por sucesso JVM.

Interface de filtragem a preservar:

```kotlin
fun recordNotification(
    packageName: String,
    toEvent: () -> DiagnosticEvent
) {
    if (allowedPackage.isBlank() || packageName != allowedPackage) return
    enqueue(toEvent())
}
```

O callback lazy garante que conteúdo de outro aplicativo não seja sequer mapeado.

## Tarefa 4 — Listener e fotografia inicial

**Status:** implementada e aprovada nas duas revisões. 32 testes JVM e 16
instrumentados no emulador; binding/leitura C6 real ainda pendentes.
Evidência: `docs/testing/task-4-capture.md`.

**Files:** `C6NotificationListener.kt`, `NotificationSnapshotMapper.kt`,
`CaptureStatus.kt`, `CollectorApplication.kt`, manifest,
`NotificationSnapshotMapperTest.kt`.

- [ ] 1. Escrever testes instrumentados para Notification.Builder: título,
  text, bigText, textLines, flags de resumo e metadados de StatusBarNotification.
  Testar CharSequence/Spanned convertidos em texto e campos ausentes.
- [ ] 2. Rodar os testes e verificar RED pelo mapper inexistente.
- [ ] 3. Implementar leitura apenas de campos documentados e metadados necessários.
  Não serializar Bundle inteiro, PendingIntent, imagem ou conteúdo de outro app.
- [ ] 4. Declarar listener com intent action
  `android.service.notification.NotificationListenerService`,
  `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` no serviço e
  `android:exported="false"`, conforme o exemplo oficial atual: o binding pelo
  sistema não requer expor o serviço a outros aplicativos.
  A permissão protege o binding; não é autorização
  que o app possa conceder a si mesmo. Não adicionar filtros de tipos que
  excluam silenciosamente notificações relevantes.
- [ ] 5. Em onListenerConnected, atualizar estado transitório e registrar conexão;
  consultar activeNotifications somente conectado e gravar cada item do C6
  como ACTIVE_SNAPSHOT. Não tratar snapshot como novo débito.
- [ ] 6. Implementar onNotificationPosted como POSTED_OR_UPDATED, sem alegar
  identidade bancária pela chave. Em onNotificationRemoved, registrar REMOVED
  com metadados e motivo quando disponível, sem criar estorno.
- [ ] 7. Em onListenerDisconnected, atualizar estado e registrar evento quando
  possível. Na criação do processo iniciar como “conexão ainda não confirmada”;
  nunca reutilizar um booleano persistido “conectado”.
- [ ] 8. Capturar exceções operacionais, incluindo falha ao consultar notificações
  ativas, como erro de diagnóstico sem texto bancário no Logcat. Não esconder
  erro como fotografia vazia bem-sucedida.
- [ ] 9. Testar a integração de origem/snapshot e rodar lint + testes JVM.
  Não criar foreground service, polling, wakelock ou receiver de boot sem
  necessidade demonstrada: o listener é gerenciado pelo Android.

## Tarefa 5 — UI de diagnóstico e estados verdadeiros

**Status:** implementada e aprovada nas duas revisões, incluindo correção testada
da separação entre erro ao abrir Settings e estado de autorização.
32 JVM e 35 instrumentados verdes. Evidência: `docs/testing/task-5-ui.md`.

**Files:** `MainActivity.kt`, `DiagnosticViewModel.kt`, `DiagnosticScreen.kt`,
`EventDetail.kt`, `strings.xml`, `DiagnosticScreenTest.kt`.

- [ ] 1. Escrever testes Compose com estado controlado: sem autorização,
  origem não configurada, autorizado mas conexão desconhecida, conectado,
  desconectado e erro de persistência.
- [ ] 2. Rodar para observar RED por UI ausente.
- [ ] 3. Implementar tela principal com aviso “Diagnóstico: não calcula saldo”,
  pacote configurado, acesso autorizado, conexão observada e último evento.
  Autorização e conexão são informações diferentes.
- [ ] 4. Botão abre Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, com tratamento
  se não houver Activity. Atualizar autorização ao voltar ao primeiro plano.
  Sem solicitar POST_NOTIFICATIONS: o coletor ainda não publica alertas.
- [ ] 5. Exibir página de até 100 eventos e “Carregar anteriores”, usando ID
  como cursor. Destacar SNAPSHOT, RESUMO, REMOVIDA e NÃO RECONHECIDA.
- [ ] 6. Implementar detalhe com campos textuais separados, horários separados,
  identificadores e classificação experimental. Não mostrar “saldo”, “lançado”
  ou “movimento confirmado” para um candidato.
- [ ] 7. Implementar confirmação de limpeza que explique que notificações novas
  ainda podem chegar e arquivos exportados não são removidos.
- [ ] 8. Testar lista vazia, paginação, texto longo, valores, falhas,
  cancelamento da limpeza, limpeza confirmada e retorno das configurações.
  Não injetar notificações falsas na produção para facilitar a UI.

## Tarefa 6 — Exportação explícita e exclusão de backup

**Status:** implementada e aprovada nas duas revisões; teste de cancelamento com
stream aberto acrescentado sem alteração de produção. 32 JVM, 47 instrumentados,
lint forçado 0 erros e 8 avisos. Evidência: `docs/testing/task-6-export.md`.

**Files:** `DiagnosticExporter.kt`, `DiagnosticExporterTest.kt`,
`ManifestPrivacyTest.kt`, manifest, XMLs de backup, UI.

- [ ] 1. Escrever teste instrumentado de serialização com dados sanitizados:
  JSON válido UTF-8, schemaVersion, versão do APK, pacote alvo, instante
  de exportação, intervalo de IDs e eventos com textos/metadados separados.
  Usar org.json para evitar biblioteca extra; verificar escaping e nulls.
- [ ] 2. Rodar RED e implementar snapshot consistente de exportação.
  Exportar por streaming a partir de uma transação/consulta consistente;
  não bloquear callbacks na thread principal nem materializar todo o histórico
  na UI. Serializar exportação/limpeza de modo documentado.
- [ ] 3. Antes de abrir o seletor, exibir aviso de dados pessoais e confirmação
  cancelável. Usar ActivityResultContracts.CreateDocument("application/json")
  e ContentResolver, sem permissões de armazenamento.
- [ ] 4. Se cancelar o seletor, não gerar arquivo nem mostrar sucesso.
  Exceção, falta de espaço ou erro de escrita gera falha explícita, não sucesso.
  Avisar que arquivo parcial pode permanecer no destino após falha.
- [ ] 5. Definir android:allowBackup="false", fullBackupContent e
  dataExtractionRules com exclusões explícitas de root/file/database/sharedpref/
  external nos domínios disponíveis, tanto cloud-backup quanto device-transfer.
  Não prometer proteção contra root, captura de tela ou ações manuais do usuário.
- [ ] 6. Criar teste que inspecione manifest/permissões e recursos:
  ausência de INTERNET, READ/WRITE_EXTERNAL_STORAGE e POST_NOTIFICATIONS;
  binding protegido; backup e transferência excluídos.
- [ ] 7. Testar exportação de banco vazio e com registros, cancelamento,
  falha de OutputStream, limpeza e persistência. Destinos de exportação escolhidos
  pelo usuário podem sincronizar o arquivo externamente mesmo sem INTERNET no APK.

## Tarefa 7 — Teste assistido no S23

**Status:** preparação de configuração local e roteiro aprovada nas duas revisões.
Teste físico ainda PENDENTE: aguardando usuário confirmar USB autorizado.
Leitura de alvo restrita à propriedade explícita -P, depois arquivo local e vazio.

**Files:** `README.md`, `docs/testing/s23-diagnostic-checklist.md`,
`docs/testing/verification-results.md`.

- [ ] 1. Criar checklist com colunas: cenário, data, precondição, ação, evento
  esperado, resultado observado, aprovado/falhou/pendente e limitação.
- [ ] 2. Com autorização e aparelho conectado, executar:

```powershell
.\tools\adb.ps1 devices -l
.\tools\adb.ps1 shell getprop ro.product.model
.\tools\adb.ps1 shell getprop ro.build.version.release
.\tools\adb.ps1 shell getprop ro.build.version.sdk
.\tools\adb.ps1 shell pm list packages c6
```

  Esperado: um aparelho autorizado; registrar modelo/API sem serial no relatório.
  Se houver zero, múltiplos ou unauthorized, resolver conexão com o usuário.
  Confirmar com o usuário qual pacote é o C6; não selecionar por suposição.

- [ ] 3. Após confirmar o pacote, armazenar apenas esse identificador em
  `local.properties` como `c6.packageName`. Ajustar leitura Gradle para que
  `-Pc6PackageName` prevaleça, seguido dessa configuração local, seguido de vazio.
  A chave não é segredo bancário; não copiar o arquivo com caminhos para docs.
  Recompilar com ferramentas Windows:

```powershell
.\tools\gradle.ps1 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
.\tools\adb.ps1 install -r app\build\outputs\apk\debug\app-debug.apk
.\tools\adb.ps1 shell am start -n br.com.c6tag.diagnostico/.MainActivity
```

- [ ] 4. Usuário habilita acesso especial. Verificar estado real na UI,
  fotografia de notificações já presentes e ausência de captura de outros apps.
- [ ] 5. Acompanhar crédito voluntário e débito natural. Comparar manualmente
  texto disponível e saldo no banco, sem copiar extrato/CPF para o relatório.
  Registrar se cobrança antiga chega como novo POSTED_OR_UPDATED.
- [ ] 6. Observar separação/agrupamento, expansão/recolhimento e remoção.
  Não exigir que uma ação visual gere callback: anotar o que realmente ocorreu.
  Distinguir mesmo valor com chaves distintas de atualização de uma chave.
- [ ] 7. Testar tela bloqueada, app fora do primeiro plano, processo recriado,
  acesso revogado/restaurado e reinicialização seguida de desbloqueio.
  Fechar pela tela de recentes não equivale a force-stop. Testar force-stop
  separadamente com aviso de interrupção; registrar que pode requerer reabertura.
- [ ] 8. Interrupção da captura: revogar acesso, aguardar sem exigir transação,
  restaurar e observar fotografia. Não declarar recuperação de avisos removidos.
- [ ] 9. Verificar modo sem internet do coletor e que o acesso de rede do banco
  é independente. Não afirmar que o C6 enviará avisos enquanto ele próprio estiver
  offline. Validar consulta de histórico, limpeza e exportação locais.
- [ ] 10. Se uma limitação exigir ajuste de bateria Samsung, documentar o estado
  inicial e pedir ao usuário antes de alterar. Não desativar proteções em massa.
- [ ] 11. Executar instrumentados:
  `.\tools\gradle.ps1 :app:connectedDebugAndroidTest`.
  Esperado: relatório sem falhas. Fixtures sanitizadas; não usar exportação real
  como artefato automático de teste.

## Tarefa 8 — Verificação final e decisão sobre a próxima fase

**Status:** implementação e revisão final do código aprovadas para diagnóstico
assistido. Build/JVM/APKtest/lint reconfirmados, sem alterações de produção.
Captura inicial e crédito real reconhecidos segundo usuário; cenários físicos
restantes e decisão sobre saldo/alertas ainda pendentes. Não tratar como liberação
operacional ou garantia de completude.

**Files:** `README.md`, `docs/testing/verification-results.md`, este plano.

- [ ] 1. Rodar novamente `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
  e instrumentados quando houver aparelho. Registrar comandos, exit codes,
  contagens e caminhos dos relatórios, sem dados pessoais.
- [ ] 2. Inspecionar manifest final do APK, não só fonte, para detectar permissões
  adicionadas por dependências. Verificar INTERNET ausente e backup excluído.
- [ ] 3. Confirmar dois eventos de uma chave preservados, snapshot separado,
  origem exata filtrada, valores em centavos e ausência de saldo operacional.
- [ ] 4. Revisar o diff/arquivos produzidos contra a especificação. Aplicar
  @requesting-code-review proporcionalmente antes de declarar pronto.
- [ ] 5. Preencher matriz: alegação estabelecida/limitada/refutada. Casos reais
  não observados ficam pendentes; incerteza de identificação impede avançar
  automaticamente para cálculo de saldo.
- [ ] 6. Entregar caminho do APK, instruções, resultados comprovados e pendências.
  Disponibilizar revisão do próximo desenho de saldo somente após discutir
  evidências com o usuário. Não iniciar automaticamente apoio remoto.

## Critérios de aceite

- APK instala e abre no S23, sem internet própria.
- Captura somente pacote C6 confirmado; configuração ausente desativa captura.
- Preserva os eventos observados, incluindo repetições, snapshots e remoções.
- Texto reconhecido produz candidato/centavos; ambiguidades permanecem desconhecidas.
- Histórico sobrevive a reabertura; exportação e limpeza funcionam explicitamente.
- Autorização, conexão e captura completa nunca são confundidas.
- Testes automatizados aprovados e cenários reais devidamente registrados,
  inclusive os pendentes e as limitações.
- Nenhuma alegação de saldo real, recuperação completa ou alerta garantido.
