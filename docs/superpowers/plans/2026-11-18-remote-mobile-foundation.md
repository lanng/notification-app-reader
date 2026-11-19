# Remote Mobile Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Entregar fundamentos testáveis do estado compartilhado e um protótipo Android debug de alerta crítico sintético com parada imediata e investigação opcional de tela cheia, sem backend ou mudança do acompanhamento financeiro atual.

**Architecture:** Regras puras pequenas para severidade, idade do estado e sessão sonora; fixtures documentam o futuro contrato. Um Adapter de notificação exclusivamente debug e uma entrada debug em Ajustes permitem testar a hipótese de áudio sem tocar no ciclo financeiro. A integração financeira/remota e o backend terão planos posteriores.

**Tech Stack:** Kotlin 2.2.10, Compose/Material 3 existentes, Android Notification API 26+, JUnit 4.13.2 e AndroidX tests; AGP 9.0.1, Gradle 9.1.0, JDK 21, compile/target 36, minSdk 26. Sem novas dependências.

---

**Estado:** versão original aprovada em revisão independente; ampliação para
investigação full-screen solicitada e aprovada pelo usuário, também aprovada
em revisão independente, sem bloqueios.
Injeção do controlador nos testes UI preservada. Nenhuma implementação iniciada.
**Spec:** [`../specs/2026-11-18-remote-monitor-design.md`](../specs/2026-11-18-remote-monitor-design.md).
**Roteiro completo:** [`2026-11-18-remote-monitor-roadmap.md`](2026-11-18-remote-monitor-roadmap.md).

## 1. Escopo, isolamento e gates

Este é somente o plano executável da etapa A, não a implementação integral da
spec. Preservar `applicationId`, versão 1.1/código 2, captura, finanças, histórico,
SQLite v2, temas e preferências. Não substituir `AlertPolicy`, `MonitorNotifier`
ou o ciclo atual; os novos tipos ainda não dirigem decisões financeiras reais.

Fora do escopo: Laravel, login/Firebase/FCM, INTERNET, sincronização, migração
financeira, árbitro persistido de produção, tela cheia no release, alarme/FGS,
overlay, acesso à política DND, implantação ou instalação física.
`USE_FULL_SCREEN_INTENT` será declarada somente em `src/debug/AndroidManifest.xml`,
para investigação opcional, desligada por padrão no probe. Nenhuma concessão
de permissão ou resultado de exibição é presumido.

Esta documentação foi preparada na branch limpa `docs/remote-monitor-design`.
Antes de executar código, usar @using-git-worktrees: não há diretório de
worktrees ou preferência existente detectados; perguntar onde criar a lane.
Não presumir caminho nem misturar a futura pasta do backend com essa lane.
JDK/SDK/local.properties da lane devem ser configurados sem imprimir/copiar
segredos ou substituir propriedades do usuário.

Usar @test-driven-development, @verification-before-completion e, se necessário,
@systematic-debugging. Um implementador por vez no workspace Windows/Gradle.
Conventional Commits locais por tarefa; sem merge/push.
Cada subpasso de código é um ciclo curto teste vermelho → mínimo verde; dividir
casos parametrizados em incrementos, não escrever toda a implementação de uma vez.

**Gate físico:** não executar ADB, conectar, instalar, mudar permissões ou tocar
som em aparelho do usuário sem autorização específica. Instrumentados só em
emulador sintético selecionado explicitamente; nunca usar o S23 por padrão.
O protótipo é hipótese, não promessa de áudio por 30 segundos.

## 2. Mapa de arquivos e responsabilidades

| Arquivo | Responsabilidade |
|---|---|
| `app/src/main/java/br/com/c6tag/diagnostico/remote/SharedAlertSeverity.kt` | Classificação monetária pura, sem ciclo/efeitos |
| `app/src/main/java/br/com/c6tag/diagnostico/remote/HostContactPolicy.kt` | Idade do último contato confirmado, sem confundir movimentação |
| `app/src/main/java/br/com/c6tag/diagnostico/remote/AlertSoundSession.kt` | Identidade e parada da sessão sonora sintética |
| `app/src/debug/java/br/com/c6tag/diagnostico/remote/probe/DebugAlertProbeEntry.kt` | Entrada somente debug em Ajustes |
| `app/src/release/java/br/com/c6tag/diagnostico/remote/probe/DebugAlertProbeEntry.kt` | Implementação vazia para release |
| `app/src/debug/java/br/com/c6tag/diagnostico/remote/probe/AlertProbeActivity.kt` | UI isolada com dados sintéticos e controles explícitos |
| `app/src/debug/java/br/com/c6tag/diagnostico/remote/probe/ProbeDependencies.kt` | Factory debug substituível antes de abrir a Activity em teste |
| `app/src/debug/java/br/com/c6tag/diagnostico/remote/probe/ProbeNotificationController.kt` | Canal/notificação crítica, sem acessar finanças |
| `app/src/debug/java/br/com/c6tag/diagnostico/remote/probe/ProbeSessionStore.kt` | Sessão atual privada, sem dados financeiros |
| `app/src/debug/java/br/com/c6tag/diagnostico/remote/probe/ProbeStopReceiver.kt` | Parada explícita apenas da sessão correspondente |
| `app/src/debug/java/br/com/c6tag/diagnostico/remote/probe/ProbeFullScreenAccess.kt` | Capacidade Android e navegação consentida para configuração especial |
| `app/src/debug/java/br/com/c6tag/diagnostico/remote/probe/ProbeDelayedTrigger.kt` | Uma tentativa sintética atrasada para observar bloqueio/background |
| `app/src/debug/AndroidManifest.xml` | Activity/receiver não exportados |
| `app/src/debug/res/values/strings.xml` | Texto pt-BR de teste, sem recurso debug no release |
| `docs/contracts/remote-monitor-v1.md` | Semântica candidata do contrato e campo versus identidade |
| `docs/contracts/remote-monitor-v1/fixtures/shared-attention.json` | Snapshot sintético, não payload bancário |
| `docs/contracts/remote-monitor-v1/fixtures/critical-occurrence.json` | Ocorrência sintética identificada |
| `docs/testing/remote-mobile-foundation-results.md` | Comandos, resultados e limites da evidência |
| `docs/testing/critical-alert-probe-checklist.md` | Roteiro manual consentido, não autorização |

Os testes correspondentes estão listados em cada tarefa. Não criar
`RemoteEscalation`/scheduler no mobile: a agenda remota pertence ao servidor.

## 3. Comandos e interpretação

Executar na raiz da lane Android em WSL; o script resolve o JDK/SDK Windows.
O caminho real da lane entra em `$PWD`, nunca presumir drive/caminho de outro
engenheiro.

**JVM — todos ou filtro de tarefa:**

```bash
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(wslpath -w "$PWD/tools/gradle.ps1")" :app:testDebugUnitTest --console=plain
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(wslpath -w "$PWD/tools/gradle.ps1")" :app:testDebugUnitTest --tests 'br.com.c6tag.diagnostico.remote.SharedAlertSeverityTest' --console=plain
```

Para filtrar outra classe, trocar somente o nome após `--tests` pelos nomes
exatos de teste listados nas tarefas. Esperado vermelho: assertion failure do
comportamento novo; tipo ainda ausente pode falhar compilação no primeiro ciclo,
mas depois introduzir o stub mínimo e observar a assertion falhar antes do verde.

**Build sem dispositivos:**

```bash
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(wslpath -w "$PWD/tools/gradle.ps1")" :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug --console=plain
```

Esperado verde: exit 0, APK debug/test/release gerados; reportar avisos do lint.
O release pode ser unsigned; isto não autoriza distribuir um APK.

**Instrumentados — somente depois de selecionar emulador sintético autorizado:**

```bash
powershell.exe -NoProfile -Command '$env:ANDROID_SERIAL="SERIAL_DO_EMULADOR_SINTETICO"; & .\tools\gradle.ps1 :app:connectedDebugAndroidTest --console=plain; exit $LASTEXITCODE'
```

`SERIAL_DO_EMULADOR_SINTETICO` é parâmetro de segurança a preencher somente após
seleção explícita, não comando pronto para rodar em qualquer dispositivo.
Não executar descoberta ADB não autorizada para preenchê-lo.
Para teste específico, adicionar argumento Gradle
`-Pandroid.testInstrumentationRunnerArguments.class=br.com.c6tag.diagnostico.remote.RemoteContractFixtureTest`
ou a classe instrumentada da tarefa.

Esperado: XMLs sem falhas/erros/ignorados inesperados. Relatar contagem real de
`app/build/test-results/testDebugUnitTest/` e
`app/build/outputs/androidTest-results/connected/`, não repetir números antigos.
Base histórica: 112 JVM e 150 instrumentados; não é prova da lane atual.

## Task 1: Baseline e fixture de faixa monetária

**Files:**
- Create: `app/src/test/java/br/com/c6tag/diagnostico/remote/SharedAlertSeverityTest.kt`
- Create: `app/src/main/java/br/com/c6tag/diagnostico/remote/SharedAlertSeverity.kt`
- Create: `docs/testing/remote-mobile-foundation-results.md`

- [ ] **Step 1:** Registrar SHA/branch, árvore limpa e baseline com JVM/build do §3.
  Sem testar em dispositivo nesta etapa. Se baseline falhar, parar e investigar
  com @systematic-debugging; não atribuir a falha a uma mudança não feita.
- [ ] **Step 2:** Criar teste vermelho para os casos abaixo, primeiro um por vez.

```kotlin
package br.com.c6tag.diagnostico.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedAlertSeverityTest {
    @Test fun exactHalfIsStillAttention() {
        assertEquals(SharedAlertSeverity.ATTENTION, classifySharedAlert(15_000, 30_000))
    }
    @Test fun oddCentLimitDoesNotRoundCriticalAway() {
        assertEquals(SharedAlertSeverity.CRITICAL, classifySharedAlert(150, 301))
        assertEquals(SharedAlertSeverity.ATTENTION, classifySharedAlert(151, 301))
    }
    @Test fun extremesDoNotOverflow() {
        assertEquals(SharedAlertSeverity.CRITICAL, classifySharedAlert(Long.MIN_VALUE, Long.MAX_VALUE))
        assertEquals(SharedAlertSeverity.NORMAL, classifySharedAlert(Long.MAX_VALUE, Long.MAX_VALUE))
    }
}
```

- [ ] **Step 3:** Rodar filtro `SharedAlertSeverityTest`; observar vermelho.
- [ ] **Step 4:** Implementar o núcleo mínimo abaixo; acrescentar testes para
  null, zero, negativo, limite inválido, 14999/15000/30000, sem dobrar `Long`.

```kotlin
package br.com.c6tag.diagnostico.remote

internal enum class SharedAlertSeverity { UNKNOWN, NORMAL, ATTENTION, CRITICAL }

internal fun classifySharedAlert(balanceCents: Long?, limitCents: Long): SharedAlertSeverity {
    require(limitCents >= 0L)
    if (balanceCents == null) return SharedAlertSeverity.UNKNOWN
    if (balanceCents >= limitCents) return SharedAlertSeverity.NORMAL
    val firstNonCriticalCent = limitCents / 2L + limitCents % 2L
    return if (balanceCents < firstNonCriticalCent) SharedAlertSeverity.CRITICAL
    else SharedAlertSeverity.ATTENTION
}
```

`NORMAL` é apenas classificação de faixa: não autoriza encerrar ciclo ou
confirmar saldo sem confiança; essa proteção será da integração posterior.

- [ ] **Step 5:** Rodar filtro e todos JVM; registrar resultados.
- [ ] **Step 6:** Revisar diff e commitar somente os arquivos desta tarefa:
  `feat(remote): add overflow-safe shared alert severity`.

## Task 2: Idade do contato sem falsa desconexão

**Files:**
- Create: `app/src/main/java/br/com/c6tag/diagnostico/remote/HostContactPolicy.kt`
- Create: `app/src/test/java/br/com/c6tag/diagnostico/remote/HostContactPolicyTest.kt`

- [ ] **Step 1:** Escrever teste vermelho com contato `1_000L`: `3_600_999L`
  ainda recente; `3_601_000L` sem contato recente. Testar contato null e relógio
  anterior ao contato, que não podem ser verdes.
- [ ] **Step 2:** Rodar filtro `HostContactPolicyTest`.
- [ ] **Step 3:** Implementar núcleo:

```kotlin
package br.com.c6tag.diagnostico.remote

internal enum class HostContactState { UNCONFIRMED, RECENT, STALE, CLOCK_UNCERTAIN }

internal fun hostContactState(lastContactAtMs: Long?, nowMs: Long): HostContactState {
    require(nowMs >= 0L)
    if (lastContactAtMs == null) return HostContactState.UNCONFIRMED
    require(lastContactAtMs >= 0L)
    if (nowMs < lastContactAtMs) return HostContactState.CLOCK_UNCERTAIN
    return if (nowMs - lastContactAtMs >= 3_600_000L) HostContactState.STALE
    else HostContactState.RECENT
}
```

- [ ] **Step 4:** Acrescentar extremos válidos, idempotência e prova de que a
  função nem recebe horário de movimentação. Heartbeat/idade não mudam severidade
  ou confiança financeira; não implementar heartbeat nesta fase.
- [ ] **Step 5:** Rodar filtro e todos JVM.
- [ ] **Step 6:** Commit: `feat(remote): model one-hour host contact freshness`.

## Task 3: Contrato candidato e fixtures compartilháveis

**Files:**
- Create: `docs/contracts/remote-monitor-v1.md`
- Create: `docs/contracts/remote-monitor-v1/fixtures/shared-attention.json`
- Create: `docs/contracts/remote-monitor-v1/fixtures/critical-occurrence.json`
- Modify: `app/build.gradle.kts`
- Create: `app/src/androidTest/java/br/com/c6tag/diagnostico/remote/RemoteContractFixtureTest.kt`

- [ ] **Step 1:** Escrever testes instrumentados que leem fixtures dos assets da
  instrumentation APK, com `InstrumentationRegistry.getInstrumentation().context`.
  Testar esquema conhecido, strings de contadores, centavos inteiros e tipo de
  mensagem. Nada depende de conta Google, rede ou serviço externo.
- [ ] **Step 2:** Rodar filtro instrumentado autorizado e observar falta de fixture.
- [ ] **Step 3:** Acrescentar dentro de `android {}`:

```kotlin
sourceSets.getByName("androidTest").assets.srcDir(
    rootProject.file("docs/contracts/remote-monitor-v1/fixtures"),
)
```

  Criar fixtures exatamente neste formato:

```json
{
  "schemaVersion": 1,
  "messageType": "sharedState",
  "monitorId": "synthetic-monitor",
  "hostGeneration": "1",
  "stateVersion": "12",
  "balanceCents": 28000,
  "limitCents": 30000,
  "needsReview": false,
  "lastFinancialUpdateAtMs": 1000,
  "lastHostContactAtMs": 2000,
  "cycleId": "synthetic-cycle",
  "episodeId": "synthetic-attention",
  "severity": "attention"
}
```

```json
{
  "schemaVersion": 1,
  "messageType": "alertOccurrence",
  "monitorId": "synthetic-monitor",
  "hostGeneration": "1",
  "stateVersion": "13",
  "cycleId": "synthetic-cycle",
  "episodeId": "synthetic-critical",
  "recipientId": "synthetic-support",
  "dueNumber": "1",
  "severity": "critical",
  "scheduledAtMs": 600000,
  "expiresAtMs": 900000
}
```

- [ ] **Step 4:** O documento explica:
  - Snapshot HTTP não é o payload FCM; FCM `data` carrega valores string e
    campos suficientes para um aviso visível genérico, sem saldo na tela bloqueada.
  - Centavos/timestamps são inteiros de 64 bits; contadores são strings decimais
    para evitar perda por intermediários JSON; sem números monetários float.
  - `stateVersion`, revisão financeira, transição local, geração de autoridade,
    ciclo, episódio e ocorrência têm papéis distintos conforme spec §5.1.
  - `lastHostContactAtMs` não é horário de evento financeiro; contato recente não
    prova continuidade. Relógio relativo/âncora é assunto da sincronização futura.
  - Campos desconhecidos não concedem poderes; esquema incompatível não produz
    efeito financeiro/sonoro. Não definir endpoints implementados por este teste.
  - São exemplos sintéticos candidatos: schemas/rotas/validação negativa serão
    completados na etapa B antes de prometer compatibilidade ponta a ponta.
- [ ] **Step 5:** Testar correspondência das fixtures com `classifySharedAlert`,
  campo de contato separado e chave de ocorrência. Testes de JSON ficam
  instrumentados: `org.json` do Android não é runtime real nos JVM atuais.
- [ ] **Step 6:** Rodar build e testes instrumentados autorizados; commit:
  `docs(remote): add shared state contract fixtures and executable checks`.

## Task 4: Sessão sonora com identidade e parada não financeira

**Files:**
- Create: `app/src/main/java/br/com/c6tag/diagnostico/remote/AlertSoundSession.kt`
- Create: `app/src/test/java/br/com/c6tag/diagnostico/remote/AlertSoundSessionTest.kt`

- [ ] **Step 1:** Escrever testes: parar ID corrente interrompe; ID anterior
  não afeta nova sessão; fim aos 30000 ms; antes disso ativo; relógio inválido
  não prolonga; tocar novamente exige nova identidade.
- [ ] **Step 2:** Rodar filtro `AlertSoundSessionTest`, observar vermelho.
- [ ] **Step 3:** Implementar estado imutável, sem acesso a `AlertCycle` ou store:

```kotlin
package br.com.c6tag.diagnostico.remote

internal data class AlertSoundSession(
    val occurrenceId: String,
    val startedAtElapsedMs: Long,
    val stopped: Boolean = false,
) {
    init {
        require(occurrenceId.isNotBlank())
        require(startedAtElapsedMs >= 0L)
    }

    fun stop(expectedOccurrenceId: String): AlertSoundSession =
        if (expectedOccurrenceId == occurrenceId) copy(stopped = true) else this

    fun isActive(nowElapsedMs: Long): Boolean =
        !stopped && nowElapsedMs >= startedAtElapsedMs &&
            nowElapsedMs - startedAtElapsedMs < 30_000L
}
```

  Em caso de reboot/continuidade temporal desconhecida, o Adapter descarta a
  sessão do protótipo, não reutiliza um `elapsedRealtime` de outra inicialização.
  Não estender este tipo a uma promessa de timer garantido em background.

- [ ] **Step 4:** Acrescentar caso `Long.MAX_VALUE` para início e tempo válidos;
  subtração só ocorre com tempo >= início, não usar `início + 30000`.
- [ ] **Step 5:** Rodar filtro/todos JVM.
- [ ] **Step 6:** Commit: `feat(remote): separate sound session stop from alert acknowledgment`.

## Task 5: Notificação crítica sintética com cancelamento seguro

**Files:**
- Create: `app/src/debug/java/br/com/c6tag/diagnostico/remote/probe/ProbeNotificationController.kt`
- Create: `app/src/debug/java/br/com/c6tag/diagnostico/remote/probe/ProbeSessionStore.kt`
- Create: `app/src/debug/java/br/com/c6tag/diagnostico/remote/probe/ProbeStopReceiver.kt`
- Create: `app/src/debug/AndroidManifest.xml`
- Create: `app/src/debug/res/values/strings.xml`
- Create: `app/src/androidTest/java/br/com/c6tag/diagnostico/remote/probe/ProbeNotificationTest.kt`
- Create: `app/src/androidTest/java/br/com/c6tag/diagnostico/remote/probe/ProbeStopReceiverTest.kt`

- [ ] **Step 1:** Criar seam `ProbeNotificationPlatform` com publish/cancel e
  capacidades, seguindo o padrão de `MonitorNotificationPlatform`, sem reutilizar
  seu ID/canal/cancelamento. Fake captura as chamadas, sem tocar som nos testes.
  Escrever teste vermelho para canal privado, ação imutável e timeout.
- [ ] **Step 2:** Rodar somente instrumentados de probe em emulador autorizado.
- [ ] **Step 3:** Implementar canal `monitor_critical_probe_v1`, ID `6099`,
  alta importância, `USAGE_NOTIFICATION`, som padrão de notificação, privado,
  `CATEGORY_REMINDER`, sem DND bypass. O núcleo da notificação:

```kotlin
val stop = Intent(context, ProbeStopReceiver::class.java).apply {
    action = "br.com.c6tag.diagnostico.remote.probe.STOP"
    data = Uri.Builder().scheme("monitor-probe").authority("sound")
        .appendPath(occurrenceId).build()
    putExtra("occurrenceId", occurrenceId)
}
val stopIntent = PendingIntent.getBroadcast(
    context, 0, stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)
val notification = Notification.Builder(context, "monitor_critical_probe_v1")
    .setSmallIcon(R.drawable.ic_tag_notification)
    .setContentTitle(context.getString(R.string.probe_critical_title))
    .setContentText(context.getString(R.string.probe_synthetic_only))
    .setVisibility(Notification.VISIBILITY_PRIVATE)
    .setCategory(Notification.CATEGORY_REMINDER)
    .setTimeoutAfter(30_000L)
    .setAutoCancel(false)
    .addAction(Notification.Action.Builder(
        null, context.getString(R.string.probe_stop_sound), stopIntent,
    ).build())
    .build().apply { flags = flags or Notification.FLAG_INSISTENT }
```

  Imports Android correspondentes são locais a esse arquivo. Checar permissão
  antes de publicar, capturar `SecurityException` e retornar resultado tipado.
  Não pedir permissão automaticamente ao abrir o teste; falta de permissão é
  estado da UI. `setTimeoutAfter`/flag são a hipótese a observar, não prova de
  duração audível. Não introduzir áudio próprio/FGS se essa hipótese falhar.

- [ ] **Step 4:** `ProbeSessionStore` usa SharedPreferences privadas exclusivamente
  de teste (`critical_alert_probe`), nunca o SQLite financeiro. Sob um lock de
  processo compartilhado, salvar ID atual antes de publicar. A parada confere
  esse ID e cancela a notificação no mesmo lock; ID velho é no-op. Activity,
  receiver e controller usam a mesma instância/lock de aplicação.
  Ao abrir nova sessão cancelar a anterior, gerar UUID novo e persistir antes
  de publicar; publicação que falha limpa só a sessão correspondente.
  Após recriação, não retomar som automaticamente; parada ainda pode cancelar
  ID persistido. A expiração do Android não significa recuperação financeira.
- [ ] **Step 5:** Receiver não exportado e sem `intent-filter`; aceitar somente
  a ação e ID esperados, parar/cancelar localmente, sem chamada a `acknowledge`,
  store financeiro, rede, job ou alteração de permissão.
  Criar strings “Teste de alerta crítico”, “Dados sintéticos — não é seu saldo”,
  “Parar som”. Não incluir valores financeiros do usuário.
- [ ] **Step 6:** Testar intenção anterior após sessão nova, duas paradas,
  publicação negada, canal existente silenciado não recriado, timeout/flags,
  nenhuma chamada ao canal `monitor_low_balance_v1`. Testes fake não afirmam som.
- [ ] **Step 7:** Rodar instrumentados autorizados e build; commit:
  `feat(debug): add isolated stoppable critical notification probe`.

## Task 6: Acesso debug explícito, sem protótipo no release

**Files:**
- Create: `app/src/debug/java/br/com/c6tag/diagnostico/remote/probe/DebugAlertProbeEntry.kt`
- Create: `app/src/release/java/br/com/c6tag/diagnostico/remote/probe/DebugAlertProbeEntry.kt`
- Create: `app/src/debug/java/br/com/c6tag/diagnostico/remote/probe/AlertProbeActivity.kt`
- Create: `app/src/debug/java/br/com/c6tag/diagnostico/remote/probe/ProbeDependencies.kt`
- Modify: `app/src/debug/AndroidManifest.xml`
- Modify: `app/src/debug/res/values/strings.xml`
- Modify: `app/src/main/java/br/com/c6tag/diagnostico/ui/monitor/MonitorSettingsScreen.kt`
- Modify: `app/src/androidTest/java/br/com/c6tag/diagnostico/capture/ManifestPrivacyTest.kt`
- Create: `app/src/androidTest/java/br/com/c6tag/diagnostico/remote/probe/AlertProbeScreenTest.kt`

- [ ] **Step 1:** Escrever teste UI que entra no probe a partir de Ajustes,
  vê “Dados sintéticos”, toca “Iniciar teste” com platform fake, vê “Parar som”,
  e verifica que abrir a tela nunca publica. Não usar executor de som real
  como default de teste UI. Antes de lançar qualquer Activity, instalar override
  da factory em `ProbeDependencies`; a `AlertProbeActivity` resolve seu controller
  por essa factory em `onCreate`, nunca construindo o platform real diretamente.
  O override usa o mesmo `ProbeSessionStore` com platform fake e registra publish/
  cancel. Fechar as Activities e limpar o override em `finally`/`@After`.
  O teste deve falhar se a factory fake não for utilizada, incluindo o percurso
  Ajustes → probe; não depender de canal silenciado para impedir áudio.
- [ ] **Step 2:** Rodar filtro `AlertProbeScreenTest`, observar vermelho.
- [ ] **Step 3:** Usar a mesma assinatura entre source sets:

```kotlin
// src/debug: arquivo no package br.com.c6tag.diagnostico.remote.probe
internal object DebugAlertProbeEntry {
    @Composable fun Content() {
        val context = LocalContext.current
        TextButton(onClick = {
            context.startActivity(Intent(context, AlertProbeActivity::class.java))
        }) { Text(stringResource(R.string.probe_open)) }
    }
}
```

```kotlin
// src/release: mesmo package e assinatura; sem referências a recursos debug.
internal object DebugAlertProbeEntry {
    @Composable fun Content() = Unit
}
```

  Chamar `DebugAlertProbeEntry.Content()` no fim da seção técnica de Ajustes,
  sem reestruturar navegação existente. Activity debug é não exportada e não
  tem launcher/filter; só a entrada interna/teste a abre.
- [ ] **Step 4:** UI mínima com iniciar teste, parar, estado de permissão/canal,
  acesso explícito à solicitação de `POST_NOTIFICATIONS` e configuração do canal.
  Usar `rememberLauncherForActivityResult` e somente pedir após toque explicado.
  Botão “Parar som” acessível enquanto toca, sem bloquear thread/interface.
  Não mostrar “teste passou” automaticamente; só estado de solicitação/cancelamento.
  Fechar/reabrir a Activity não toca de novo. `contentIntent` da notificação
  pode abrir a Activity sem iniciar outra sessão.
- [ ] **Step 5:** Estender `ManifestPrivacyTest` para verificar novos componentes
  debug não exportados, preservando a lista exata de permissões sem INTERNET.
  No release, checar manifest mesclado/artefato: ausência de `AlertProbeActivity`
  e `ProbeStopReceiver`, ausência de string/canal probe e entrada vazia.
  A classe no-op de entry não é capacidade de teste nem precisa ser removida.
- [ ] **Step 6:** Testar tema claro/escuro, texto ampliado, semântica do botão,
  navegação de retorno e canal negado. Não prometer auditoria TalkBack completa
  por ter testes semânticos. Rodar todos JVM, build debug/test/release e lint.
- [ ] **Step 7:** Commit: `feat(debug): expose consent-driven alert probe without release behavior`.

## Task 6A: Investigar tela cheia opcional sem perder a parada pela notificação

**Files:**
- Create: `app/src/debug/java/br/com/c6tag/diagnostico/remote/probe/ProbeFullScreenAccess.kt`
- Create: `app/src/debug/java/br/com/c6tag/diagnostico/remote/probe/ProbeDelayedTrigger.kt`
- Modify: `app/src/debug/java/br/com/c6tag/diagnostico/remote/probe/ProbeNotificationController.kt`
- Modify: `app/src/debug/java/br/com/c6tag/diagnostico/remote/probe/ProbeDependencies.kt`
- Modify: `app/src/debug/java/br/com/c6tag/diagnostico/remote/probe/ProbeSessionStore.kt`
- Modify: `app/src/debug/java/br/com/c6tag/diagnostico/remote/probe/AlertProbeActivity.kt`
- Modify: `app/src/debug/AndroidManifest.xml`
- Modify: `app/src/debug/res/values/strings.xml`
- Modify: `app/src/androidTest/java/br/com/c6tag/diagnostico/capture/ManifestPrivacyTest.kt`
- Create: `app/src/androidTest/java/br/com/c6tag/diagnostico/remote/probe/ProbeFullScreenTest.kt`

- [ ] **Step 1:** Teste vermelho com fakes: opção desligada/permissão ausente
  gera a mesma notificação com “Parar som”, sem `fullScreenIntent`; capacidade
  concedida + opção ligada inclui PendingIntent de Activity explícito/imutável.
  Não lançar Activity diretamente para simular sucesso do full-screen.
- [ ] **Step 2:** Executar filtro instrumentado `ProbeFullScreenTest` em emulador
  autorizado. Estender `ProbeDependencies` para injetar também capacidade,
  abertura de configurações e disparo atrasado: nenhum teste automatizado
  modifica permissões, toca som, bloqueia ou acorda aparelho real.
- [ ] **Step 3:** Declarar `android.permission.USE_FULL_SCREEN_INTENT` apenas no
  manifest debug. Atualizar allowlist exata de `ManifestPrivacyTest` para debug;
  inspecionar manifest release e provar que essa permissão não está nele.
  `POST_NOTIFICATIONS` e capacidade do canal continuam sendo verificações
  independentes; não recriar canal que o usuário silenciou.
- [ ] **Step 4:** Implementar `ProbeFullScreenAccess`: API 34+ consulta
  `NotificationManager.canUseFullScreenIntent()`. API 29–33 verifica a permissão
  declarada; API 26–28 não depende da API especial moderna. Tratar indisponibilidade
  como sem capacidade, não como concessão. Só após botão explicado abrir:

```kotlin
if (Build.VERSION.SDK_INT >= 34) {
    context.startActivity(Intent(
        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
        Uri.parse("package:${context.packageName}"),
    ))
}
```

  Usar contexto Activity ou flag adequada ao contexto; capturar
  `ActivityNotFoundException`/`SecurityException` e mostrar indisponibilidade.
  Reconsultar capacidade em `onResume`; não usar diálogo de permissão runtime
  para esse acesso especial nem abri-lo automaticamente.
- [ ] **Step 5:** Opção “Tentar tela cheia neste teste” começa desligada.
  Com opção ligada e capacidade verificada, acrescentar ao builder da Task 5
  antes de `build()`:

```kotlin
val fullScreen = Intent(context, AlertProbeActivity::class.java).apply {
    action = "br.com.c6tag.diagnostico.remote.probe.SHOW_ALERT"
    data = Uri.Builder().scheme("monitor-probe").authority("screen")
        .appendPath(occurrenceId).build()
    putExtra("occurrenceId", occurrenceId)
}
builder.setFullScreenIntent(PendingIntent.getActivity(
    context, 1, fullScreen, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
), true)
```

  Se publicar com FSI falhar por restrição, tentar uma vez sem FSI, com a mesma
  sessão/ID/ação de parada. Não duplicar toque, começar outra sessão, alterar
  `CATEGORY_REMINDER` para chamada/alarme ou usar `startActivity` como contorno.
  Aceite da publicação não significa que o sistema abriu tela cheia.
- [ ] **Step 6:** Em `onCreate`/`onNewIntent`, aceitar `SHOW_ALERT` somente quando
  ID e sessão atual ainda ativa coincidirem. Persistir na sessão ID, início
  monotônico e identidade do processo para falhar de forma conservadora se a
  continuidade não puder ser demonstrada. Sessão antiga/parada/expirada ou de
  processo anterior não ganha tela sobre o bloqueio nem inicia áudio novo.
  Stop por receiver ainda pode cancelar o ID persistido após reinício.
  Esta limitação de cold start é do probe; não prova o futuro fluxo FCM.
- [ ] **Step 7:** Para apresentação `SHOW_ALERT` válida, API 27+ usa
  `setShowWhenLocked(true)` e `setTurnScreenOn(true)`; API 26 tem Adapter guardado
  para flags equivalentes de janela. Não definir essas flags globalmente no
  manifest ou na entrada normal de Ajustes. Nunca dispensar keyguard/desbloquear,
  mostrar saldo real ou abrir outras telas privadas sobre o bloqueio.
  Exibir somente dados sintéticos e botão grande “Parar som”, que usa a mesma
  parada por ID da notificação. Finalizar apresentação após parada/expiração;
  reentrada não publica som e intenção velha não cancela nova sessão.
- [ ] **Step 8:** Para observar app em background/tela bloqueada sem FCM,
  `ProbeDelayedTrigger` oferece “Tentar em 5 segundos”, somente após toque.
  Uma tarefa in-process cancelável, sem serviço/alarme/job, chama o mesmo
  controller; sessão sonora inicia na publicação, não no agendamento.
  Permitir cancelar a tentativa pendente; ID anterior não dispara após nova
  tentativa. Processo morto perde o disparo e não o restaura. Atraso não é
  garantia, nem implementação da cadência de 10 minutos.
- [ ] **Step 9:** Testar com fakes concessão/negação/revogação entre consulta e
  envio, retorno de configurações, tentativa cancelada, ID antigo, expiração,
  reentrada sem novo som e parada equivalente na tela/notificação. Registrar
  separadamente “solicitado”, “Activity recebeu intenção” e “exibição observada”.
  Não inferir tela cheia de uma Activity aberta manualmente por instrumentation.
- [ ] **Step 10:** Rodar JVM/builds/lint e instrumentados autorizados, verificar
  ausência de permissão/capacidade FSI no release. Commit:
  `feat(debug): investigate optional full-screen critical alert presentation`.

Fontes oficiais novamente consultadas ao ampliar o plano:
[Android 14 — full-screen intent](https://developer.android.com/about/versions/14/behavior-changes-14#secure-fsi-notifications),
[notificações urgentes](https://developer.android.com/develop/ui/views/notifications/time-sensitive).
Durante uso ativo, Android pode preferir heads-up mesmo com acesso concedido.
Concessão manual não equivale a elegibilidade/política de distribuição na Play.

## Task 7: Evidência e gate antes da integração

**Files:**
- Create: `docs/testing/critical-alert-probe-checklist.md`
- Modify: `docs/testing/remote-mobile-foundation-results.md`
- Modify: `docs/superpowers/plans/2026-11-18-remote-monitor-roadmap.md`
- Modify: `README.md`

- [ ] **Step 1:** Rodar novamente todos JVM, instrumentados em emulador
  autorizado, builds debug/test/release e lint. Se não houver emulador autorizado,
  declarar etapa bloqueada/parcial; build do test APK não é execução de testes.
- [ ] **Step 2:** Registrar comandos, SHAs, contagens XML, lint novo versus base,
  arquivos/IDs debug isolados e evidência de ausência de INTERNET e componentes
  probe no release. Não copiar relatório antigo como resultado atual.
- [ ] **Step 3:** Escrever checklist manual para eventual consentimento:
  aparelho/API identificados sem serial privado no Git; volume baixo inicial;
  aviso sonoro consentido; iniciar, parar antes de 30s, aguardar expiração,
  abrir painel, tela bloqueada, app em background, canal silenciado, DND.
  Incluir matriz full-screen opcional desligado/ligado × acesso negado/concedido
  × aparelho em uso/bloqueado. Quando indisponível, confirmar alternativa pela
  notificação sem abrir o app nem aguardar 30 segundos; observar se o Android
  exige desbloqueio para alguma ação e registrar sem prometer contorná-lo.
  Comparar heads-up, Activity recebida e tela realmente visível, não só flag do
  payload. Conceder/revogar acesso especial e bloquear/acordar o aparelho são
  ações físicas separadas, consentidas para cada ensaio.
  Por cenário: configuração, expectativa, observado e limite/não observado.
  Não alterar volume/DND, reboot ou force-stop sem autorização específica.
- [ ] **Step 4:** Checklist separa stop de ciência: após o teste, o saldo,
  ciclo, pausa, preferências e histórico reais não mudaram. O probe não agenda
  repetição; 10 minutos e escalonamento seguem responsabilidade futura.
- [ ] **Step 5:** Atualizar README só como estado de desenvolvimento/debug.
  Manter verdade “versão atual não usa internet/backend”; não dizer que apoios,
  login ou som prolongado de produção estão entregues.
- [ ] **Step 6:** Revisão de spec e qualidade com agente principal, corrigir
  problemas e repetir evidência afetada. Commit:
  `docs(testing): record remote foundation evidence and critical audio gate`.
- [ ] **Step 7:** Apresentar ao usuário o que foi estabelecido e o que falta.
  Sem ensaio físico, áudio fica “não comprovado”; sem backend, remoto não funciona.
  Não avançar silenciosamente para código Laravel: avisar e aguardar pasta.

## Critérios de aceite desta etapa

- [ ] Regras puras de faixa/contato/sessão cobertas, sem overflow.
- [ ] Fixtures sintéticas coerentes com a spec e sem dados privados.
- [ ] Probe isolado da persistência/alertas financeiros atuais.
- [ ] Parada por identidade; intenção antiga não cancela teste novo.
- [ ] Canal/permissões do usuário respeitados e release sem capacidade de probe.
- [ ] Investigação full-screen opcional e fallback cobertos; tela cheia/áudio
  físico não comprovados ficam explicitamente marcados, sem garantia de abertura.
- [ ] Nenhum Firebase, internet, backend, conta ou configuração de infraestrutura criado.
- [ ] Regressões locais verificadas e limites de áudio explicitados.
- [ ] Revisão humana do gate antes de prometer áudio ou começar outra etapa.
