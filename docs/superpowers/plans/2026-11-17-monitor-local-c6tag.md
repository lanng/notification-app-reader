# Monitor local C6 Tag — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Evoluir o diagnóstico instalado para acompanhamento local de saldo estimado, histórico financeiro, pendências e alertas, com a interface clara/escura aprovada.

**Architecture:** Preservar captura e diagnóstico e acrescentar decisões financeiras transacionais no mesmo SQLite. Um ponto serial de comandos ordena conferências, resolução de pendências e efeitos de alerta; a atualização persistida solicita o primeiro aviso, e WorkManager reavalia ciclos existentes. Compose apresenta o estado, sem executar regras financeiras.

**Tech Stack:** Kotlin integrado 2.2.10, Compose BOM 2026.04.01/Material 3, SQLiteOpenHelper, coroutines 1.10.2, WorkManager 2.12.0, JUnit/AndroidX/Compose tests. AGP 9.0.1, Gradle 9.1.0, compile/target SDK 36, minSdk 26, JDK 21 e bytecode 17.

---

**Status:** plano concluído e aprovado em revisão independente após tornar
obrigatório o backfill de identidades v1 e o teste do primeiro aviso direto.
Nenhum bloqueio ou recomendação remanescente. Usuário escolheu execução direta
com checkpoints. Tarefas 1–8 concluídas e revisadas; Tarefa 9 com verificação
técnica aprovada, ainda pendente de leitura/foco manuais do TalkBack.

**Atualização de autorização:** após o checkpoint T7, o usuário autorizou
subagents de implementação, com conferência pelo agente principal, inicialização
do Git local e commits separados do trabalho existente e futuro. Conventional
Commits e identificadores/comentários de código em inglês; interface em pt-BR.
Essa autorização substitui as restrições históricas abaixo sobre Git/commits e
execução sem agentes. Não autoriza push, criação de remoto ou atualização do S23.

## 1. Referências e autorização

- Spec aprovada: `docs/superpowers/specs/2026-11-17-monitor-local-c6tag-design.md`.
- Vocabulário: `CONTEXT.md`; decisões: `docs/plano-app-local.md`.
- Fontes Android e artefato verificado: `docs/research/2026-11-16-alertas-locais-monitor.md`.
- Evidência anterior: `docs/testing/verification-results.md`.
- Referências visuais auxiliares: arquivos `.superpowers/brainstorm/109706-1794883145/`
  citados na spec. Tokens e regras estão na spec mesmo se o servidor tiver encerrado.
- Usar @test-driven-development, @systematic-debugging e
  @verification-before-completion durante a execução.
- Git local inicializado com autorização posterior à T7. Registrar checkpoints
  em Markdown e commits por etapa; não descartar arquivos não produzidos nesta
  execução. Usar branch de trabalho e um único implementador por vez para evitar
  conflitos no workspace Windows/Gradle existente.
- Planejar/executar builds não autoriza nova instalação ou mudança de permissões
  no S23. Solicitar autorização antes da atualização física.
- Nenhuma transação bancária será automatizada ou exigida para testar.

## 2. Base e fronteiras de mudança

A base tem 32 testes JVM e 47 instrumentados previamente executados no emulador.
O S23 SM-S911B, Android 17/API 37, recebeu o diagnóstico com autorização.
O alvo confirmado é `com.c6bank.app`, configurado em `local.properties` privado.
Não imprimir nem editar outras propriedades locais.

Preservar `applicationId=br.com.c6tag.diagnostico`, dados e acesso especial.
Só alterar o nome visível para o nome de trabalho “Monitor de tags”.

`DiagnosticDatabase` hoje tem esquema 1, tabela `events` e payload JSON 1.
O novo esquema SQLite será 2; **não** confundir isso com versão do payload
diagnóstico nem com o schemaVersion do documento exportado.

Não acrescentar internet, servidor, login, recargas, gestão por veículo,
gráficos, notificações de outros aparelhos ou alarmes exatos.

## 3. Evidence path

**Alegação:** uma observação elegível pode alterar a estimativa uma vez, de modo
recuperável, e o estado persistido conduz avisos locais compatíveis com as
preferências do usuário e as limitações do Android.

Alternativas: só teste no banco é pouco repetível e expõe dados; só teste puro
não valida SQLite, canais ou Android. Escolhido: domínio determinístico + migração/
integração em emulador + observações assistidas no S23.

| Risco | Entrada controlável | Evidência |
|---|---|---|
| Passado contado novamente | Base v1 e saldo de referência | Fixtures SQL, histórico sem reaplicação |
| Atualização contada como nova | Chave/postTime/valor repetidos ou conflitantes | Testes de identidade e reprocessamento |
| Aviso durante conferência absorvido indevidamente | Sessão aberta + observação concorrente | Pendência persiste após o novo marco |
| Perda de saldo após interrupção | Falha antes/depois do commit | Reabertura e invariantes transacionais |
| Alerta duplicado/antigo | Tick, crédito e ciência concorrentes | Clock fake, ciclo/versionamento, notifier fake |
| Promessa falsa de aviso | Permissão/canal bloqueado, Doze/force-stop | UI explícita e matriz física com limitações |
| Destruição dos dados instalados | Migração do esquema 1 | DB v1 sintético preservado e atualização `-r` autorizada |

Affordances duráveis: clock injetável, efeitos de alerta observáveis em fake,
snapshots de leitura e TestDriver do WorkManager. Não criar emissor de avisos
bancários falsos no APK, bypass do filtro ou superfícies debug permanentes novas.

## 4. Mapa de arquivos

Abreviações usadas nas tarefas:

- `MAIN` = `app/src/main/java/br/com/c6tag/diagnostico`
- `UNIT` = `app/src/test/java/br/com/c6tag/diagnostico`
- `ANDROID` = `app/src/androidTest/java/br/com/c6tag/diagnostico`
- `RES` = `app/src/main/res`

Cada caminho das tarefas é relativo a essas raízes exatas.

```text
MAIN/monitor/MonitorModels.kt                 modelos, resultados e estados
MAIN/monitor/MovementPolicy.kt                identidade e elegibilidade, puro
MAIN/monitor/ReferencePolicy.kt               marco/conferência, puro
MAIN/monitor/MonitorStore.kt                  interface de operações persistidas
MAIN/monitor/MonitorCoordinator.kt            comandos e efeitos ordenados
MAIN/monitor/storage/MonitorSchema.kt         tabelas/índices/migração
MAIN/monitor/storage/MonitorSql.kt            decisões financeiras + persistência
MAIN/monitor/storage/MonitorQueries.kt        leitura paginada e snapshots
MAIN/monitor/storage/SqliteMonitorStore.kt    adapter do SQLite compartilhado
MAIN/monitor/alerts/AlertModels.kt            ciclo, prazos, intenção de emissão
MAIN/monitor/alerts/AlertPolicy.kt            transições puras
MAIN/monitor/alerts/MonitorNotifier.kt        canal, capacidades e publicação
MAIN/monitor/alerts/LowBalanceScheduler.kt    trabalho único e cancelamento
MAIN/monitor/alerts/LowBalanceWorker.kt       reavaliação a partir do banco
MAIN/monitor/alerts/AlertActionReceiver.kt    ciência explícita do ciclo atual
MAIN/ui/theme/MonitorTheme.kt                esquemas e tipografia
MAIN/ui/monitor/MonitorViewModel.kt           estado e ações de uso diário
MAIN/ui/monitor/MonitorRoot.kt                abas, diálogos e diagnóstico
MAIN/ui/monitor/OverviewScreen.kt             saldo e alerta
MAIN/ui/monitor/BalanceReferenceScreen.kt     configuração/correção confirmada
MAIN/ui/monitor/FinancialHistoryScreen.kt     histórico e filtros
MAIN/ui/monitor/PendingReviewScreen.kt        evidência e decisão explícita
MAIN/ui/monitor/MonitorSettingsScreen.kt      limites, tempos, aparência e acessos
```

Modificar, preservando comportamento compatível:
`MAIN/diagnostic/DiagnosticDatabase.kt`, `DiagnosticRecorder.kt`,
`MAIN/CollectorApplication.kt`, `MainActivity.kt`, manifest, strings, Gradle e
testes de privacidade/versionamento pertinentes.

As dependências de `DiagnosticViewModel`, `DiagnosticScreen`, `EventDetail` e
`DiagnosticExporter` continuam funcionando. Não transformar esses arquivos
em calculadoras de saldo.

Testes novos seguem as mesmas raízes/pacotes: `MovementPolicyTest`,
`ReferencePolicyTest`, `AlertPolicyTest`, `MonitorMigrationTest`,
`MonitorStoreTest`, `MonitorCoordinatorTest`, `LowBalanceSchedulerTest`,
`LowBalanceWorkerTest`, `MonitorNotifierTest`, `AlertActionReceiverTest`,
`MonitorScreensTest` e `MonitorViewModelTest`.

Se `MonitorSql.kt` exceder uma responsabilidade coesa, separar internamente
`MonitorReferenceSql.kt` e `MonitorAlertSql.kt`, sem inventar outro módulo público.

## 5. Contratos que evitam decisões implícitas

### 5.1 Identidade e efeitos financeiros

Identidade de publicação: pacote confirmado + chave Android + postTime.
Não é identidade bancária oficial. Registrar tipo, valor, hash do conteúdo
financeiro integral e primeira/última observação.

Normalização: Unicode NFC e espaços equivalentes; não remover dígitos, datas,
locais ou fragmentos que diferenciem cobranças. Usar hash para comparar conteúdo,
não alegar anonimização.

Aplicar exatamente a política da seção 6 da spec:
mesma publicação/tipo/valor não soma novamente; conflito fica pendente; chave
reutilizada ou nova chave com conteúdo integral idêntico fica pendente; mesmo
valor em publicações com evidência diferente não é duplicidade por si só.

Recebimento anterior ao marco, resumo, remoção e snapshots nunca geram movimento
automático. Título financeiro conhecido sem quantia válida é pendência;
propaganda/unknown genérico permanece diagnóstico.

### 5.2 Conferência com avisos concorrentes

Abrir conferência cria sessão persistida com ID, instante, cursor inicial e estado.
Enquanto ativa, candidatos novos são preservados como provisórios e não recebem
impacto automático; o saldo anterior não é recalculado parcialmente durante o
formulário. Isso evita aplicar um movimento no marco anterior e reaplicá-lo ao
confirmar o novo.

Confirmar recebe saldo, limite e **IDs específicos** que o usuário declarou já
incluídos. Não usar um booleano genérico que cubra também avisos que chegaram
depois de renderizar o formulário.

Na transação de confirmação:

1. Validar sessão/revisão e criar novo marco.
2. Cobrir observações anteriores à sessão e as explicitamente incluídas.
3. Preservar demais candidatos recebidos durante a sessão como pendências do
   novo marco, inclusive IDs abaixo da fronteira serial.
4. Definir a estimativa pelo valor informado, sem aplicar pendências.
5. Registrar uma correção/referência, não crédito.

Observações copiadas na captura antes da confirmação, mas persistidas depois,
usam também `observedAtMs`/sessão para não escapar dessa proteção.

Cancelar a conferência retoma a avaliação dos candidatos contra o marco anterior
quando a continuidade estiver preservada; antes do primeiro marco, não aplicar.
Sessão abandonada expira após 15 minutos: verificar expiração ao receber comando/
observação ou voltar ao app, sem timer exato. Preservar candidatos, rejeitar
confirmação de sessão expirada e pedir nova conferência. Não congelar o cálculo
indefinidamente por um formulário abandonado.

### 5.3 Interface do acompanhamento

Definir modelos Kotlin puros de entrada/resultado; operações inválidas retornam
um resultado rejeitado com código seguro, não uma exceção com conteúdo bancário.
Assinaturas orientadoras:

```kotlin
internal interface MonitorStore {
    val revisions: StateFlow<MonitorRevision>
    fun overview(): MonitorOverview
    fun history(beforeId: Long?, limit: Int, filter: HistoryFilter): List<FinancialEntry>
    fun pending(beforeId: Long?, limit: Int): List<PendingMovement>
    fun beginReference(nowMs: Long): ReferenceSession
    fun confirmReference(command: ConfirmReference): MonitorResult
    fun cancelReference(sessionId: String, nowMs: Long): MonitorResult
    fun resolvePending(command: ResolvePending): MonitorResult
    fun changeSettings(command: ChangeSettings): MonitorResult
    fun acknowledge(cycleId: String, nowMs: Long): MonitorResult
    fun resume(cycleId: String, nowMs: Long): MonitorResult
    fun evaluateAlert(nowMs: Long): AlertEvaluation
    fun recordSubmission(command: RecordSubmission): MonitorResult
    fun startProcessSession(sessionId: String, nowMs: Long): MonitorResult
}
```

Leituras não mudam revisão nem disparam novas leituras. Comandos validam revisão/
marco/ciclo atual e retornam o novo estado. UI chama `MonitorCoordinator`, não SQL.
Não expor `SQLiteDatabase` à apresentação.

### 5.4 Transação, serialização e recuperação

No esquema 2, `DiagnosticDatabase.append` grava observação e chama a projeção
financeira dentro da mesma transação. A projeção usa regras puras, não publica
notificações nem chama WorkManager. Erros esperados de interpretação viram
pendências; falha real de armazenamento não finge sucesso.

Compartilhar a fila já existente do `DiagnosticRecorder` para comandos financeiros:
acrescentar uma operação interna suspensa `runSerialized { ... }`, com resposta
completada também em cancelamento/fechamento, seguindo o padrão dos comandos
Page/Export. Não criar um executor genérico nem chamar esse método da UI.
`MonitorCoordinator` usa esse ponto para confirmação, ciência, ticks e efeitos.

Um marcador de revisão do monitor muda após commits financeiros relevantes,
não após consultas/páginas. Ele acorda a apresentação e a reavaliação do alerta.
Revisão em memória não substitui estado persistido ou recuperação no início.
`MonitorRevision` identifica a causa: observação/conferência/configuração relevante,
ciência/pausa, aparência ou resultado de emissão. A UI pode recarregar a leitura;
o coordinator não dispara nova tentativa por resultado de emissão ou aparência.
Falha/permissão bloqueada repetida não pode criar ciclo de feedback, tentativas
contínuas ou revisões ilimitadas: só reavaliar por nova entrada externa, retorno
ao app, ação explícita ou tick periódico.

No início de processo com marco existente, continuidade começa não confirmada.
Registrar sessão de execução sem disco na main e não apagar incerteza apenas
porque o listener reconectou. Ciclos/pausas sobrevivem; notificações ativas não
reconstroem automaticamente movimentos ausentes.

### 5.5 Esquema SQLite 2

Além de `events` intacta, criar:

| Tabela | Responsabilidade e invariantes |
|---|---|
| `monitor_state` | Linha única: marco atual, saldo em centavos, revisão/causa, continuidade, sessão de processo e sessão de conferência atual |
| `monitor_settings` | Linha única: limite, repetição, pausa e tema, com validação de valores suportados |
| `monitor_references` | Marcos imutáveis: saldo, instante, fronteira de observações e sessão |
| `monitor_reference_sessions` | ID, abertura/cursor, expiração e estado; índice parcial permite uma ACTIVE |
| `monitor_sources` | Publicação, valor/tipo/hash/evidência mínima e IDs de observação; identidade de publicação única |
| `monitor_source_evidence` | Hashes das variantes financeiras observadas por fonte, preservando evidência de reapresentação sem duplicar texto pessoal |
| `monitor_entries` | Crédito/débito ou referência; fonte financeira aplicada única; referência não é receita |
| `monitor_pending` | Razão, fonte, marco/sessão, estado e chave de deduplicação da própria pendência |
| `monitor_alert_cycles` | Ciclo, estado, pausa, último envio, próximo prazo, versão/intenção de emissão e falha segura |

Usar INTEGER para quantias/prazos, IDs locais monotônicos e índices de paginação.
Não usar FLOAT/REAL para dinheiro. Restrições UNIQUE impedem segunda aplicação
de uma mesma fonte mesmo se um comando for repetido.

Pendências guardam evidência mínima suficiente para sobreviver à limpeza dos
registros brutos. Se o detalhe bruto foi apagado, mostrar que está indisponível,
sem impedir correção manual. Não manter CPF/nome em novas tabelas por conveniência.
Não criar DELETE CASCADE de `events` para informações financeiras.

Migração 1→2 adiciona tabelas, mantém payload diagnóstico 1 e saldo não configurado.
É obrigatório preencher as identidades e evidências financeiras já observadas no
esquema 1 antes de concluir a migração. Elas ficam não acompanhadas/cobertas pelo
passado, sem criar referência, receita, despesa, saldo ou ciclo de alerta.
Preservar chave/postTime, tipo/valor quando conhecidos, conflitos e hashes de
variantes de conteúdo relevantes; não guardar só a última variante e esquecer as
anteriores. Identidade incompleta continua sendo evidência de incerteza.

Processar o histórico por cursor dentro da migração transacional, não lista
integral. Falha de leitura/integridade aborta a migração sem apagar os registros
nem transformar falha em histórico vazio. Durante a operação normal, também
registrar identidades de novos avisos financeiros recebidos antes do primeiro
saldo, sem aplicá-los.

`clear()` só pode remover as observações brutas, nunca essa evidência de identidade.
A proteção precisa funcionar mesmo se o usuário limpar o diagnóstico antes de
informar a primeira referência.

## Tarefa 1 — Baseline, dependência e infraestrutura de verificação

**Status:** concluída. Grafo compatível, 32 JVM/47 instrumentados aprovados,
lint 0 erros/8 avisos; manifesto auditado. Nenhum aparelho físico usado.

**Files:** `gradle/libs.versions.toml`, `app/build.gradle.kts`,
`docs/testing/monitor-local-results.md`.

- [x] 1. Ler spec, vocabulário e resultados existentes; registrar baseline sem
  alterar `local.properties` ou o pacote confirmado.
- [x] 2. Executar:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

  Esperado: exit 0 e os 32 testes JVM anteriores. Os 47 instrumentados anteriores
  não contam como reexecutados nesta etapa.
- [x] 3. Fixar `androidx.work:work-runtime:2.12.0` e
  `androidx.work:work-testing:2.12.0`; usar o segundo apenas em testes.
  Não incluir WorkManager em versão dinâmica nem artefato `work-runtime-android`.
- [x] 4. Rodar `:app:checkDebugAarMetadata :app:assembleDebugAndroidTest`.
  Se o grafo transitivo incompatibilizar a base, investigar e documentar uma
  versão estável equivalente; não elevar SDK/Kotlin/Compose silenciosamente.
- [x] 5. Inspecionar manifesto mesclado e separar as permissões WorkManager
  declaradas das usadas pelo nosso código. INTERNET continua proibida.
- [x] 6. Atualizar testes existentes de privacidade para permitir apenas as
  declarações justificadas de WorkManager e, na Tarefa 6, POST_NOTIFICATIONS.
  Manter asserções de binding privado do listener e exclusões de backup.
- [x] 7. Registrar baseline/versões/comandos sem agendar trabalho do monitor ou
  acessar o S23. Regressões de privacidade foram executadas por ADB direcionado
  somente ao AVD read-only próprio, conforme operação segura da seção 6.

Checkpoint: build compatível e baseline preservado, ainda sem função financeira.

## Tarefa 2 — Regras financeiras, identidade e conferência

**Status:** concluída. 42 testes novos de domínio; suíte completa de 74 JVM
aprovada. Decisões puras ainda não ligadas à captura/SQLite.

**Create:** `MAIN/monitor/{MonitorModels,MovementPolicy,ReferencePolicy}.kt`.
**Tests:** `UNIT/monitor/{MovementPolicyTest,ReferencePolicyTest}.kt`.

- [x] 1. Escrever teste de candidato novo após marco usando fixtures sanitizadas.
- [x] 2. Criar o mínimo para compilar com comportamento não implementado e
  executar `:app:testDebugUnitTest --tests "*MovementPolicyTest"`.
  Registrar RED por asserção, não chamar erro de compilação de teste executado.
- [x] 3. Implementar decisão pura `Apply`, `Ignore` ou `Pending(reason)`,
  recebendo publicação, evidência e estado persistido relevante.
- [x] 4. Adicionar testes para cada linha da política da seção 5.1, inclusive
  mesmo valor em fontes distintas, chave reutilizada e conteúdo idêntico em
  chave nova. Cada caso precede seu comportamento.
- [x] 5. Implementar soma/subtração com `Math.addExact`/`subtractExact` ou
  equivalente verificado. Overflow produz rejeição/pendência, nunca wrap.
- [x] 6. Escrever testes da sessão de conferência e avisos concorrentes. Exemplo:

```kotlin
@Test fun concurrentCandidateRemainsPendingAfterReference() {
    val review = referenceFixture.open(nowMs = 1_000L, cursor = 10L)
    referenceFixture.observeDebit(id = 11L, cents = 1_691L, observedAtMs = 1_050L)
    val confirmed = referenceFixture.confirm(
        review = review, cents = 10_000L, nowMs = 1_100L, explicitlyIncluded = emptySet()
    )
    assertEquals(10_000L, confirmed.balanceCents)
    assertEquals(listOf(11L), confirmed.pendingObservationIds)
}
```

  `referenceFixture` é helper de teste, não método de produção.
- [x] 7. Cobrir inclusão explícita por IDs, aviso que chega depois da renderização,
  persistência tardia, cancelamento, expiração e confirmação repetida.
- [x] 8. Cobrir pendência antiga após marco novo e valor não legível sem edição
  arbitrária do movimento. Modelos não importam Android.
- [x] 9. Rodar suíte completa JVM, revisar clareza e registrar cenários.

Checkpoint: decisões financeiras determinísticas, sem SQL ou UI.

## Tarefa 3 — Ciclo de alerta puro

**Status:** concluída. 21 testes de alerta; total de 95 JVM aprovados.
Build/APKs aprovados e lint 0 erros/8 avisos. Sem efeitos Android habilitados.

**Create:** `MAIN/monitor/alerts/{AlertModels,AlertPolicy}.kt`.
**Test:** `UNIT/monitor/alerts/AlertPolicyTest.kt`.

- [x] 1. Escrever RED: saldo 20.000, limite 30.000 abre ciclo e uma intenção
  de primeiro aviso; igualdade ao limite não abre.
- [x] 2. Implementar estados sem plataforma, com `nowMs` controlável.
- [x] 3. Cobrir novo débito no mesmo ciclo sem reiniciar prazo nem criar ciclo.
- [x] 4. Cobrir ciência/pausa 30 min, ausência de lembrete durante pausa,
  elegibilidade posterior e retomada manual sem recriar ciclo.
- [x] 5. Cobrir crédito insuficiente e recuperação ao limite; recuperação
  enquanto continuidade/pendências relevantes exigem conferência não encerra
  silenciosamente o alerta.
- [x] 6. Cobrir ciência antiga, ação repetida, descarte sem ciência, configuração
  inicial baixa e alteração de limite/intervalo. Alterar duração preferida não
  muda pausa já em andamento.
- [x] 7. Cobrir falha/permissão negada: preservar ciclo e intenção segura sem
  marcar aviso como visto ou ouvido. Cada avaliação em atraso emite no máximo
  uma intenção, não uma sequência de lembretes “perdidos”.
- [x] 8. Separar `EmissionRequested` de `SubmissionSucceeded`. Somente a segunda
  avança o último envio; ela significa chamada aceita, não som entregue.
- [x] 9. Rodar JVM completa e registrar testes/limites.

Checkpoint: relógio e regras de alerta independentes do Android.

## Tarefa 4 — Migração e store transacional

**Status:** concluída, com correções temporais/de motivo e revalidação independente.
Verificação conjunta atual: 109 JVM/81 instrumentados aprovados, lint 0 erros/8
avisos. Sem efeitos Android habilitados ou atualização física.

**Create:** `MAIN/monitor/MonitorStore.kt`,
`MAIN/monitor/storage/{MonitorSchema,MonitorSql,MonitorQueries,SqliteMonitorStore}.kt`.
**Modify:** `MAIN/diagnostic/DiagnosticDatabase.kt`.
**Tests:** `ANDROID/monitor/{MonitorMigrationTest,MonitorStoreTest}.kt`,
`ANDROID/diagnostic/DiagnosticDatabaseTest.kt`.

- [x] 1. Construir fixture SQLite v1 manualmente com o schema original e
  observações sanitizadas; escrever teste que abre a versão 2 sem perder registros.
- [x] 2. Rodar no AVD read-only para RED; não executar testes no S23 instalado.
- [x] 3. Implementar DDL/índices da seção 5.5 e migração 1→2 aditiva.
  Teste de versão incompatível existente deve usar versão realmente futura,
  não confundir o agora válido upgrade 1→2 com corrupção.
- [x] 3a. Preencher obrigatoriamente `monitor_sources`/`monitor_source_evidence`
  a partir dos avisos financeiros v1, sem movimentos/marco/saldo. Testar variantes
  de conteúdo, chave reutilizada, conflito e metadados incompletos; histórico
  financeiro deve continuar vazio e saldo não configurado.
- [x] 3b. Testar sequência completa: fixture v1 → migrar → limpar diagnóstico →
  confirmar primeiro saldo → reapresentar publicação antiga com mesmo postTime
  e com postTime novo. Não pode haver impacto financeiro automático; o caso de
  chave reutilizada é pendência. Nova chave com conteúdo antigo idêntico também
  é pendência, mesmo após a limpeza. Provar as variantes preservadas, não só
  a forma mais recente do texto.
- [x] 4. Implementar consultas de overview, histórico/filtros e pendências:
  ID DESC exclusivo, limite 1..100; queries nunca mudam revisão.
- [x] 5. Envolver inserção bruta e projeção em transação, usando a política pura.
  StateFlow/revisão só avança após commit; nenhum aviso externo dentro da transação.
- [x] 6. Implementar abrir/confirmar/cancelar/expirar conferência com todas as
  proteções de concorrência da seção 5.2, sem cobrir candidatos só pelo cursor.
- [x] 7. Implementar resolução idempotente e correção que absorve explicitamente
  o passado, preservando histórico e recusando comandos de marco antigo.
- [x] 8. Persistir ciclo/configurações/pausa/intenção de emissão no mesmo domínio
  transacional, aplicando a política da Tarefa 3.
- [x] 9. Testar rollback: falha antes de commit não muda saldo/registro financeiro;
  após commit, reabrir recupera o mesmo efeito único. Usar dados inválidos ou
  fixtures controladas, não destruir banco do usuário.
- [x] 10. Testar limpar diagnóstico sem perder identidade, saldo, marco, pausa,
  pendências ou proteção contra reprocessar o que já foi aplicado.
- [x] 11. Testar payload nulo/Unicode, saldo negativo/overflow, cursores e
  reabertura. Não usar migração destrutiva em falha.
- [x] 12. Rodar JVM, APKs, lint e instrumentados. Registrar contagem real.

Checkpoint: transação/migração comprovadas, sem notificação real do app.

## Tarefa 5 — Comandos, captura e recuperação integrados

**Status:** concluída e aprovada em revisão independente somente leitura.
109 JVM/81 instrumentados aprovados, lint 0 erros/8 avisos. AVD próprio encerrado;
efeitos Android desabilitados e S23 inalterado.

**Create:** `MAIN/monitor/MonitorCoordinator.kt`.
**Modify:** `MAIN/diagnostic/DiagnosticRecorder.kt`, `MAIN/CollectorApplication.kt`.
**Tests:** `UNIT/monitor/MonitorCoordinatorTest.kt` e integração em
`ANDROID/monitor/MonitorStoreTest.kt`.

- [x] 1. Escrever RED para comando financeiro ordenado entre duas observações,
  sem supplier de pacote alheio executado.
- [x] 2. Acrescentar operação interna serial ao recorder, com propagação segura
  de resultado e finalização de solicitantes em cancel/close.
- [x] 3. Implementar coordinator como Interface de comandos para UI/worker,
  usando o mesmo ponto serial. Não expor DB ou regras financeiras à captura.
- [x] 4. Compor helper/store/coordinator uma vez na Application; manter criação
  leve na main e bootstrap persistido enfileirado antes do uso do monitor.
- [x] 5. Inicializar sessão de processo/continuidade, recuperar intenção de
  emissão e conferência abandonada. Reconexão não limpa incerteza.
- [x] 6. Observar apenas revisão financeira/IDs de escrita, não versão de
  consultas do recorder. Provar que leitura de overview não realimenta refresh.
- [x] 7. Criar seam interno para efeitos de alerta (fake nesta tarefa); executar
  depois do commit e revalidar ciclo/versionamento antes de publicar.
- [x] 8. Serializar ciência/tick/recuperação; uma tentativa de emissão pendente
  não pode recriar ciclo encerrado. Recuperar intenção após falha entre commit
  e publicação; reconhecer que efeito externo não oferece exactly-once.
- [x] 8a. Provar que resultado de emissão/permissão bloqueada não realimenta
  a própria tentativa; aparência também não publica ou reagenda alerta.
- [x] 9. Testar filas encerradas, falha seguida de sucesso e início sem
  referência/sem captura autorizada, sem dar falsa indicação de continuidade.
- [x] 10. Rodar regressões de captura/diagnóstico e verificar que os dados
  anteriores continuam sendo observações, não dinheiro.

Checkpoint: acompanhamento integrado sem efeitos Android habilitados ainda.

## Tarefa 6 — Avisos Android, ações e WorkManager

**Status:** concluída e aprovada em revisão independente somente leitura,
sem achados Critical/Important/Minor. 109 JVM/103 instrumentados aprovados;
lint 0 erros/8 avisos. Primeiro aviso real comprovado no AVD sem executar
Worker/TestDriver. AVD próprio encerrado; S23 inalterado.

**Create:** `MAIN/monitor/alerts/{MonitorNotifier,LowBalanceScheduler,
LowBalanceWorker,AlertActionReceiver}.kt`.
**Modify:** manifest, `CollectorApplication.kt`,
`ANDROID/capture/ManifestPrivacyTest.kt`.
**Tests:** `ANDROID/monitor/alerts/{MonitorNotifierTest,LowBalanceSchedulerTest,
LowBalanceWorkerTest,AlertActionReceiverTest}.kt`.

- [x] 1. Escrever RED para construir notificação do ciclo atual, com ação
  explícita de ciência e identidade estável; usar notifier fake nos testes de política.
- [x] 2. Criar canal `monitor_low_balance_v1` e ID estável de notificação.
  Importance/som inicial apropriados, sem tentar restabelecer escolhas do usuário.
- [x] 2a. Substituir o seam fake da Tarefa 5 pela composição real
  `MonitorCoordinator → MonitorNotifier + LowBalanceScheduler` na Application.
  Após commit que abre ciclo, publicar por esse caminho direto; não delegar o
  primeiro aviso ao Worker. Atualizar o resultado persistido da submissão.
- [x] 3. Declarar POST_NOTIFICATIONS e tratar capacidade de emissão separada
  de autorização à captura; pedido em contexto será ligado pela UI.
- [x] 4. Criar PendingIntent explícito imutável: identidade inclui ciclo
  em `data` ou request code, não só extras. Receiver próprio não exportado.
  Validar ação/ciclo; ciência antiga não afeta o atual.
- [x] 5. Persistir ciência antes de cancelar/atualizar o aviso. Tocar para abrir
  o app não marca ciência; usar a ação explícita.
- [x] 6. Implementar scheduler com trabalho único, sem constraints de rede,
  período mínimo 15 min. Não reinscrever a cada débito; atualizar em mudança
  de ciclo/intervalo e cancelar na recuperação. Após solicitar o primeiro aviso
  diretamente, usar atraso inicial apropriado ao período para a reavaliação,
  sem tratar a primeira execução de Worker como o primeiro alerta.
- [x] 7. Worker deve consultar estado persistido por coordinator. Sem acesso
  bancário, sem inferir capture completa e sem publicar com dados de `inputData`
  obsoletos. Se não houver ciclo elegível, não emitir.
- [x] 8. Instanciar worker por constructor padrão/adapter pequeno, sem Hilt.
  Permitir WorkerFactory de teste com gateway fake para TestDriver.
- [x] 9. TestDriver: satisfazer atraso/intervalo sem esperar 15 min reais,
  provar trabalho único, cancelamento, ciência/pausa e corrida com crédito.
- [x] 9a. Teste de integração específico do primeiro aviso: com capacidade de
  notificação permitida no AVD próprio, fazer commit de condição abaixo do limite,
  não disparar Worker/TestDriver e verificar que o adapter real recebeu/publicou
  a notificação do ciclo. Capturar a evidência no NotificationManager ou seam
  Android correspondente, não apenas assertar uma intenção da regra pura.
  Esse é um aviso do nosso app com fixture, não uma falsa notificação do C6.
- [x] 10. Testar canal ausente/bloqueado, permissão negada e falha de notify,
  sem afirmar que chamada aceita significa som ou ciência.
- [x] 11. Auditar manifesto final: INTERNET e alarmes exatos/DND/full-screen
  ausentes; permissões WorkManager justificadas. Listener continua privado e
  job service da biblioteca só exportado sob BIND_JOB_SERVICE.
- [x] 12. Rodar suites no AVD. Não emitir avisos no S23 nesta tarefa.

Checkpoint: alertas demonstrados com fakes/emulador, não garantia temporal no Samsung.

## Tarefa 7 — Temas, navegação, saldo inicial e visão geral

**Status:** concluída e aprovada em revalidação independente somente leitura.
Achados de entrada negativa/cancelamento expirado corrigidos por RED → GREEN.
112 JVM/126 instrumentados aprovados; build/APKs/lint exit 0, lint 0 erros/8 avisos.
Capturas sintéticas claro/escuro conferidas; AVD próprio encerrado e S23 inalterado.

**Create:** `MAIN/ui/theme/MonitorTheme.kt`,
`MAIN/ui/monitor/{MonitorViewModel,MonitorRoot,OverviewScreen,BalanceReferenceScreen}.kt`.
**Modify:** `MAIN/MainActivity.kt`, `RES/values/strings.xml`, `app/build.gradle.kts`.
**Tests:** `ANDROID/ui/monitor/{MonitorScreensTest,MonitorViewModelTest}.kt`.

- [x] 1. Escrever testes Compose de estados não configurado, normal, baixo,
  pausado, saldo a conferir, permissão de avisos negada e captura sem autorização.
- [x] 2. Executar RED com UI mínima; implementar tema claro/escuro/sistema com
  tokens aprovados, sem fonte remota, imagem gerada ou cor dinâmica que anule a paleta.
- [x] 3. Implementar raiz com abas e diagnóstico acessível por Ajustes; manter
  os fluxos atuais de SAF/settings/erros sem misturar estados de autorização.
- [x] 4. Ligar saldo de referência ao formulário, validação de dinheiro, sessão
  aberta, confirmação explícita, novos avisos concorrentes e cancelamento/expiração.
- [x] 5. Exibir primeira referência e correções sem chamá-las de recarga.
  Sem referência, não exibir zero como se fosse saldo calculado.
- [x] 6. Solicitar POST_NOTIFICATIONS após explicação e ação do usuário.
  Recusa não trava saldo/histórico. Nunca conceder por shell no aparelho físico.
- [x] 7. Overview mostra estimativa, limite, última observação, estado real,
  pendências e ações ciência/retomada associadas ao ciclo atual.
- [x] 8. Coletar estado com lifecycle; evitar loading/refresh infinito,
  resultado antigo após alteração de marco ou referências de Activity em tarefas IO.
- [x] 9. Atualizar nome visível e versionCode para 2/versionName 1.1,
  sem mudar applicationId. Não instalar ainda.
- [x] 10. Rodar tests/AVD/lint e capturar somente telas sintéticas nos dois
  temas para comparar à proposta, sem screens de dados bancários do usuário.

Checkpoint: fluxo principal completo, histórico e ajustes detalhados na tarefa seguinte.

## Tarefa 8 — Histórico, pendências, ajustes e diagnóstico

**Status:** concluída, com implementação delegada e revisões spec/qualidade
aprovadas após correções de evidência. Verificação independente: 112 JVM e
144 instrumentados aprovados, build/APKs/lint exit 0, lint 0 erros/8 avisos.
Tema e link nativo da Tarefa 7 reaproveitados. Capturas somente sintéticas;
nenhuma atualização física autorizada ou realizada.

**Create:** `MAIN/ui/monitor/{FinancialHistoryScreen,PendingReviewScreen,
MonitorSettingsScreen}.kt`.
**Modify:** `MonitorRoot.kt`, `MonitorViewModel.kt`, strings.
**Tests:** ampliar `ANDROID/ui/monitor/{MonitorScreensTest,MonitorViewModelTest}.kt`.

- [x] 1. Escrever RED para histórico paginado, filtros e referência diferenciada
  de receita; não listar propagandas ou resumos como dinheiro.
- [x] 2. Implementar detalhe financeiro e pendências com evidência mínima,
  motivo e confirmação “nova” versus “já incluída/não contar”.
- [x] 3. Testar toque duplo, estado antigo após nova correção, valor ilegível,
  observação bruta removida e retorno ao saldo com decisão persistida.
- [x] 4. Implementar limite e escolhas de repetição 15/30/60 e pausa
  15/30/60/120 min; impedir valores não suportados ou entrada inválida.
- [x] 5. Implementar tema local Sistema/Claro/Escuro e persistência. As preferências
  do aparelho não exigem conta nem se propagam remotamente.
- [x] 6. Linkar som/canal às configurações do Android, com falha de abertura
  separada da capacidade de notificar e da autorização de captura.
- [x] 7. Conservar diagnóstico, exportação sensível e limpeza, rotulada para
  deixar explícito que não apaga o acompanhamento financeiro.
- [x] 8. Testar navegação/voltar/rotação, paginação concorrente, novas entradas,
  filtros, pending/clear/exports e ausência de informações pessoais desnecessárias.
- [x] 9. Rodar suites completas e registrar screenshots apenas sintéticas.

Checkpoint: todas as telas aprovadas disponíveis, sem implementar fase remota.

## Tarefa 9 — Integração final, acessibilidade e evidências

**Status:** verificação técnica e revisão final de código aprovadas.
112 JVM/144 instrumentados, zero falhas/erros/ignorados, lint 0 erros/8 avisos.
Fonte 200%, restrições retrato/paisagem, alvos e contraste verificados de forma
sintética. Leitura/foco reais do TalkBack permanecem pendentes; por isso a tarefa
não está integralmente concluída nem autoriza instalação no S23.

**Files:** `docs/testing/monitor-local-results.md`,
`docs/testing/monitor-local-s23-checklist.md`, `README.md`, testes pertinentes.

- [x] 1. Rodar o comando completo de build/JVM/APKtest/lint. Conferir XMLs e
  contagens, sem assumir que os totais continuam 32/47.
- [x] 2. No AVD temporário read-only, executar a suíte instrumentada completa
  e casos fim a fim: v1→v2, referência, dois débitos, crédito, pausa, pendência,
  correção e processo recriado. Nenhuma publicação falsa de C6 na produção.
- [x] 3. Provar invariantes no banco sintético: uma fonte aplicada uma vez,
  saldo reconstituível pelo marco atual, pendência sem impacto, cleanup seguro.
- [ ] 4. Conferir font scale até 200%, TalkBack, landscape, touch targets,
  contraste e ausência de corte/ações inacessíveis nos dois temas.
  Parte automatizada aprovada; pendente verificação manual do TalkBack.
- [x] 5. Inspecionar manifest/permissões e APK empacotado, não só a fonte.
  Confirmar backup e INTERNET ausente.
- [x] 6. Solicitar revisão final do código contra spec e plano com
  @requesting-code-review; corrigir bloqueios e repetir verificações.
- [x] 7. Documentar conhecidos/limitados/pendentes e instruções que distinguem
  saldo estimado, ciência, pausa e recuperação. Não prometer som/horário.

## Tarefa 10 — Atualização e validação assistida no S23

**Files:** checklist físico e resultados do monitor, `README.md`.

- [ ] 1. Apresentar o estado e solicitar conexão/autorização para atualizar.
  Não assumir que a autorização da instalação anterior vale para esta rodada.
- [ ] 2. Com consentimento, confirmar exatamente um alvo autorizado e o
  modelo/API/pacote por consultas de metadados; não gravar serial em documentos.
- [ ] 3. Verificar BuildConfig `com.c6bank.app`, build e SHA do APK. Instalar
  com `adb -s ... install -r` sem desinstalar, limpar dados ou puxar banco privado.
- [ ] 4. Conferir registros anteriores e orientar saldo atual/limite/configuração.
  Acesso à captura e POST_NOTIFICATIONS são decisões manuais separadas.
- [ ] 5. Aguardar crédito/débito naturais para conferir, sem exigir Pix.
  Para teste de alerta sem transação, o usuário pode optar por elevar
  temporariamente o limite acima da estimativa atual e restaurá-lo depois.
  Não informar saldo falso nem alterar configurações sem consentimento.
- [ ] 6. Testar ciência, retomada, recuperação e permissão/canal quando o usuário
  autorizar. Testes de tempo podem permanecer pendentes; não acelerar produção
  para burlar o intervalo mínimo.
- [ ] 7. Tela bloqueada, reboot, revogar/restaurar e force-stop são ensaios
  separados consentidos; não mudar bateria/DND do aparelho automaticamente.
- [ ] 8. Registrar previsão versus observação de cada lembrete, sem generalizar
  pontualidade de um caso. Não executar instrumentados contra o banco instalado
  do usuário e não usar dumpsys de notificações ou exports brutos para diagnóstico.
- [ ] 9. Encerrar com resultado estabelecido/limitado/refutado, APK e pendências,
  sem iniciar comunicação remota ou declarar saldo bancário garantido.

## 6. Comandos e operação segura dos testes

JVM/compilação/lint:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

Filtrar testes por nome de classe:
`:app:testDebugUnitTest --tests "*MovementPolicyTest"`; o mesmo padrão serve
às classes JVM das tarefas. Para instrumentados, usar
`-Pandroid.testInstrumentationRunnerArguments.class=br.com.c6tag.diagnostico.monitor.MonitorStoreTest`
com `:app:connectedDebugAndroidTest`.

Emulador existente: `Medium_Phone_API_36.1`. Executar em porta livre com
`-read-only -no-snapshot-save -no-snapshot-load -no-window -no-audio`.
ADB sempre direcionado ao `emulator-PORT` criado nesta rodada; testes Gradle com
`ANDROID_SERIAL` explícito. Não selecionar aparelho físico por conveniência.
Não alterar o AVD base, instalar imagens/drivers ou matar emuladores do usuário.
Encerrar somente o emulador próprio com `emu kill`.

Não usar sleep/loops de polling para afirmar resultado. Usar barreiras/flows/
TestDriver e notificações de conclusão dos comandos. RED e GREEN são comandos
executados com resultados lidos, não uma promessa do implementador.

## 7. Critérios de saída e handoff

- Artefato preserva a instalação e migra dados sem reaplicar o passado.
- Visual/fluxos aprovados presentes; diagnóstico continua acessível.
- Regras financeiras e ciclos testados em domínio e SQLite/Android.
- Avisos dependem das permissões/preferências reais e não prometem pontualidade.
- Relatórios distinguem emulador de S23 e o que permanece pendente.
- Documentação e revisão final aprovadas; commits locais por etapa com
  Conventional Commits em inglês, sem push automático.

Executar por agentes por tarefa ou diretamente na sessão, conforme escolha do
usuário após a revisão deste plano. Nenhuma tarefa de implementação foi iniciada
durante a elaboração deste documento.
