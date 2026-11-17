# Pesquisa: alertas locais para saldo estimado

**Data:** 2026-11-16  
**Escopo:** viabilidade de alertas locais para saldo estimado, com reavaliação periódica e sem serviço em primeiro plano permanente, alarmes exatos, push/backend ou promessa de horário exato. Fontes consultadas: documentação e código oficiais Android/AndroidX.

## Resumo executivo

Usar o fluxo que recebe a atualização de saldo para persistir o resultado e, após o commit no SQLite, avaliar e publicar imediatamente um alerta quando a regra de negócio for satisfeita. Um `Worker` periódico pode reavaliar o estado como contingência ou para cobrir atualizações que não gerem callback, mas não deve ser o caminho do alerta imediato: o menor intervalo de um `PeriodicWorkRequest` é 15 minutos e a execução pode atrasar por otimizações do sistema. [PeriodicWorkRequest](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest) · [SQLiteDatabase](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase)

Tratar o mecanismo como **aproximado e oportunista**. Doze, restrições do fabricante, force-stop, falta da permissão de notificações ou configurações feitas pelo usuário podem impedir ou atrasar a entrega. Notificações locais não garantem som, visibilidade, prioridade ou que o usuário as veja. [Doze e App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby) · [Permissão de notificações](https://developer.android.com/develop/ui/compose/notifications/notification-permission) · [Canais de notificação](https://developer.android.com/develop/ui/views/notifications/channels)

## Agendamento e frequência

- `PeriodicWorkRequest` é adequado para trabalho persistente que pode ocorrer repetidamente, mas é inexato: o intervalo efetivo pode ser maior que o solicitado, por otimizações, constraints ou estado do dispositivo. O intervalo periódico mínimo é 15 minutos. Portanto, não representar esse agendamento como um relógio nem prometer execução em um minuto específico. [WorkManager: trabalho periódico](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started#periodic) · [PeriodicWorkRequest](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest)
- Como recomendação de produto, limitar a frequência configurável e qualquer pausa dependente exclusivamente da reavaliação periódica a pelo menos 15 minutos. Esse é um limite coerente com a API, não uma garantia de que o worker rodará nesse prazo. A lógica do worker deve ser idempotente, tolerar repetição e conferir o estado persistido antes de emitir novamente. [PeriodicWorkRequest](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest) · [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent)
- O worker deve ser uma reavaliação complementar. Para o evento recebido enquanto o app está ativo, gravar e avaliar no fluxo do callback, sem esperar pelo próximo ciclo do worker. Executar a avaliação depois do commit evita alertar sobre estado que não foi efetivamente persistido; essa é uma recomendação de arquitetura baseada no uso transacional do SQLite, não uma exigência do Android. [SQLiteDatabase: transações](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase#beginTransaction())
- Doze e App Standby podem adiar trabalho em segundo plano. WorkManager oferece trabalho persistente gerenciado pelo sistema, não uma promessa de execução imediata nem uma forma de contornar esses modos. [Doze e App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby) · [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent)
- Não usar `AlarmManager` com alarmes exatos para tentar contornar a cadência: alarmes exatos têm controles e requisitos próprios, e são desnecessários para uma necessidade explicitamente aproximada. Não manter um foreground service contínuo apenas para observar o saldo. Essas alternativas não mudariam as limitações de visibilidade e configuração das notificações. [Agendamento de alarmes](https://developer.android.com/develop/background-work/services/alarms/schedule) · [Serviços em primeiro plano](https://developer.android.com/develop/background-work/services/foreground-services)
- Se o usuário fizer force-stop, não prometer que o worker ou a avaliação imediata continuarão funcionando até que ele volte a abrir o app. A execução depende do sistema e do estado do pacote; validar também o comportamento do fabricante/dispositivo, sem generalizar garantias de um teste em um único aparelho. [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent) · [Doze e App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)

## Permissões, canais e experiência do alerta

- No Android 13 (API 33) e superior, notificações não isentas dependem da permissão de runtime `POST_NOTIFICATIONS`. Solicitar em contexto, explicar o benefício e tratar recusa sem bloquear o restante do app; quando não concedida, não contar com a exibição do alerta. [Permissão de notificações](https://developer.android.com/develop/ui/compose/notifications/notification-permission) · [Manifest.permission.POST_NOTIFICATIONS](https://developer.android.com/reference/android/Manifest.permission#POST_NOTIFICATIONS)
- No Android 8.0 (API 26) e superior, cada notificação precisa pertencer a um canal. Criar canais antes de publicar notificações e usar um identificador estável para o tipo de alerta. O usuário controla a importância, som e outras opções do canal; depois de criado, o app não pode restaurar silenciosamente configurações que o usuário alterou. [Canais de notificação](https://developer.android.com/develop/ui/views/notifications/channels)
- Não prometer alerta sonoro: permissão, canal, volume, modo Não Perturbe e preferências do usuário influenciam o resultado. O app não deve tentar contornar DND nem declarar acesso de política de notificações como requisito para um aviso comum. [Canais de notificação](https://developer.android.com/develop/ui/views/notifications/channels) · [NotificationManager](https://developer.android.com/reference/android/app/NotificationManager)
- Quando a permissão ou canal estiverem indisponíveis, manter o estado do saldo e do alerta no app e explicar a limitação na interface. Uma notificação publicada não prova que foi vista; a ausência de confirmação não deve ser interpretada como ciência do usuário.

## Ação de ciência (`PendingIntent`)

- Uma ação como **“Entendi”** pode ser ligada a um `PendingIntent` explícito para um receiver ou activity do próprio app. Ao receber a ação, persistir a ciência do alerta com uma identidade estável (por exemplo, o identificador do alerta/estado) e atualizar ou remover a notificação. `PendingIntent` permite que o sistema ou outro componente autorizado execute a operação em nome do app; não é confirmação de que a pessoa leu o conteúdo. [PendingIntent](https://developer.android.com/reference/android/app/PendingIntent) · [Ações em notificações](https://developer.android.com/develop/ui/views/notifications/build-notification#addAction)
- Para apps que miram Android 12 (API 31) ou superior, especificar explicitamente se o `PendingIntent` é mutável ou imutável. Para uma ação simples de ciência, preferir `FLAG_IMMUTABLE`, a menos que haja uma necessidade documentada de o chamador preencher partes do intent. [PendingIntent](https://developer.android.com/reference/android/app/PendingIntent#FLAG_IMMUTABLE)
- Não diferenciar ações apenas por extras do `Intent`: a identidade usada para comparar `PendingIntent`s não considera extras. Usar intents/componentes/request codes distintos ou outra identidade documentada para evitar que ações de alertas diferentes sejam confundidas. [PendingIntent](https://developer.android.com/reference/android/app/PendingIntent)

## Manifesto e compatibilidade

- Declarar `POST_NOTIFICATIONS` no manifesto para dispositivos aplicáveis e implementar o pedido em runtime. Não solicitar `SCHEDULE_EXACT_ALARM` se a solução não usa alarmes exatos. A presença de permissões e componentes de bibliotecas deve ser conferida no manifesto mesclado gerado pelo build, em vez de remover declarações da dependência por suposição. [Permissão de notificações](https://developer.android.com/develop/ui/compose/notifications/notification-permission) · [Manifest.permission](https://developer.android.com/reference/android/Manifest.permission) · [Manifest merger](https://developer.android.com/build/manage-manifests)
- A versão estável mais recente localizada na pesquisa foi WorkManager **2.12.0**, publicada em 2026-09-23. A documentação geral informa `compileSdk` 33 ou superior, mas a inspeção do AAR exato `work-runtime:2.12.0` encontrou limites mais específicos: `minCompileSdk=35`, `minAndroidGradlePluginVersion=8.6.0` e `minSdkVersion=24`. Para compatibilidade desta versão, prevalecem os metadados do artefato; `compileSdk 36` e AGP `9.0.1` superam esses pisos. Isso não substitui validar o build completo do projeto. [Versões do AndroidX Work](https://developer.android.com/jetpack/androidx/releases/work) · [AAR WorkManager 2.12.0](https://dl.google.com/dl/android/maven2/androidx/work/work-runtime/2.12.0/work-runtime-2.12.0.aar)
- A tabela oficial associa Android 17 a API 37, mas a compatibilidade explícita de WorkManager 2.12.0 com execução em runtime no Android 17 não foi confirmada nesta pesquisa. Não inferir suporte de runtime apenas porque uma dependência tem determinado `compileSdk`; validar quando SDK/dispositivo compatível estiver disponível. [Níveis de API](https://developer.android.com/guide/topics/manifest/uses-sdk-element#ApiLevels) · [Versões do AndroidX Work](https://developer.android.com/jetpack/androidx/releases/work)

## Testes e validação

- Testar a regra de negócio separadamente: atualização que cruza o limite, estado que não cruza, repetição do mesmo saldo, recuperação após reinício e persistência da ciência. Verificar que o commit precede a publicação imediata e que a reavaliação do worker não duplica alertas.
- Para testes de integração do worker, o WorkManager fornece `TestDriver`, que permite simular passagem de tempo para atrasos/intervalos e satisfazer constraints. Isso testa a lógica e o agendamento sob o ambiente de teste; **não** prova a pontualidade real nem o comportamento de um fabricante específico. [Testes de workers do WorkManager](https://developer.android.com/develop/background-work/background-tasks/testing/persistent/worker-impl)
- Validar manualmente permissão concedida/negada, canal silenciado ou bloqueado, DND, aparelho em Doze, reinício, force-stop e retorno ao app. Registrar o horário previsto e o horário observado sem transformar o resultado de um aparelho em garantia geral. [Permissão de notificações](https://developer.android.com/develop/ui/compose/notifications/notification-permission) · [Canais de notificação](https://developer.android.com/develop/ui/views/notifications/channels) · [Doze e App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)

## Limites que devem ser comunicados

- O worker periódico não é um alarme de horário exato; sua menor cadência solicitável é 15 minutos, e a execução pode atrasar.
- O alerta imediato depende de o app receber/processar a atualização. O worker é apenas uma reavaliação complementar, não substituto de push ou de um serviço permanentemente ativo.
- O Android ou o usuário podem impedir a exibição ou o som por permissão, canal, DND, volume, estado do dispositivo ou restrições do fabricante.
- A ação “Entendi” registra uma interação explícita, não comprova leitura ou compreensão.
- Não há garantia de execução durante force-stop, de entrega em horário específico ou de contorno das restrições do sistema.

## Complemento: metadados e manifesto do artefato 2.12.0 (2026-11-17)

### Artefato Android e requisitos de build

- O POM oficial `androidx.work:work-runtime:2.12.0` retorna packaging `aar` e declara que Gradle deve preferir o Gradle Module Metadata. O arquivo `.module` oferece variantes de API/runtime cujo arquivo é `work-runtime-2.12.0.aar`. As URLs tentadas para `androidx.work:work-runtime-android:2.12.0` (POM, `.module` e AAR) retornaram HTTP 404; o artefato Android publicado e inspecionado é `work-runtime`. [POM 2.12.0](https://dl.google.com/dl/android/maven2/androidx/work/work-runtime/2.12.0/work-runtime-2.12.0.pom) · [Gradle Module Metadata 2.12.0](https://dl.google.com/dl/android/maven2/androidx/work/work-runtime/2.12.0/work-runtime-2.12.0.module) · [AAR 2.12.0](https://dl.google.com/dl/android/maven2/androidx/work/work-runtime/2.12.0/work-runtime-2.12.0.aar)
- Dentro do AAR, `META-INF/com/android/build/gradle/aar-metadata.properties` contém exatamente:

  ```properties
  aarFormatVersion=1.0
  aarMetadataVersion=1.0
  minCompileSdk=35
  minCompileSdkExtension=0
  minAndroidGradlePluginVersion=8.6.0
  coreLibraryDesugaringEnabled=false
  ```

- O `AndroidManifest.xml` do mesmo AAR declara `uses-sdk android:minSdkVersion="24"`. Portanto, a informação geral da página de WorkManager (`compileSdk` 33+) é menos específica que a exigência registrada no artefato binário 2.12.0. Para o cenário considerado, `compileSdk 36` e AGP `9.0.1` passam os limites mínimos declarados pelo AAR; isso não prova compatibilidade de todas as demais dependências ou configuração do projeto, pois nenhum build foi executado nesta pesquisa. O AAR não exige `compileSdk 37`, então a pesquisa condicional de uma versão estável alternativa para API 36 não se aplica. [Documentação geral de WorkManager](https://developer.android.com/jetpack/androidx/releases/work) · [AAR 2.12.0](https://dl.google.com/dl/android/maven2/androidx/work/work-runtime/2.12.0/work-runtime-2.12.0.aar)
- O AAR de 1.264.436 bytes foi lido em memória; o SHA-256 calculado (`a967c776d2c2ff9a7a541679d2fb51cefdc78d4010fcf68f3fdd4c57c29e89dc`) confere com o valor publicado no `.module`. Nenhum artefato foi gravado no projeto, SDK ou cache.

### Manifesto exato do `work-runtime:2.12.0`

As declarações abaixo aparecem no `AndroidManifest.xml` contido no AAR e podem ser incorporadas ao manifesto final pelo merger quando a dependência é usada. A lista descreve o manifesto do artefato WorkManager, não o manifesto final do app nem todas as outras dependências:

- **Permissões declaradas:** `android.permission.WAKE_LOCK`; `android.permission.ACCESS_NETWORK_STATE`; `android.permission.RECEIVE_BOOT_COMPLETED`; `android.permission.FOREGROUND_SERVICE`.
- **`INTERNET`:** ausente do `AndroidManifest.xml` do AAR WorkManager 2.12.0. Isso não garante ausência no app final: o app ou outra dependência ainda pode declará-la.
- **Provider:** `androidx.startup.InitializationProvider`, `exported="false"`, authority `${applicationId}.androidx-startup`, `tools:node="merge"`, com metadata `androidx.work.WorkManagerInitializer` = `androidx.startup`.
- **Services:** `androidx.work.impl.background.systemjob.SystemJobService` (`directBootAware="false"`, `enabled="@bool/enable_system_job_service_default"`, `exported="true"`, protegido por `android.permission.BIND_JOB_SERVICE`, `tools:targetApi="n"`); `androidx.work.impl.foreground.SystemForegroundService` (`directBootAware="false"`, `enabled="@bool/enable_system_foreground_service_default"`, `exported="false"`, `tools:ignore="MissingServiceExportedEqualsTrue"`, `tools:targetApi="n"`).
- **Receivers:** `androidx.work.impl.utils.ForceStopRunnable$BroadcastReceiver` (`directBootAware="false"`, `enabled="true"`, `exported="false"`, `tools:targetApi="n"`); `androidx.work.impl.background.systemalarm.RescheduleReceiver` (`directBootAware="false"`, `enabled="false"`, `exported="false"`, `tools:targetApi="n"`, filtro para `android.intent.action.BOOT_COMPLETED`); `androidx.work.impl.diagnostics.DiagnosticsReceiver` (`directBootAware="false"`, `enabled="true"`, `exported="true"`, protegido por `android.permission.DUMP`, `tools:targetApi="n"`, filtro para `androidx.work.diagnostics.REQUEST_DIAGNOSTICS`).
- `BOOT_COMPLETED` é uma **ação de intent** no filtro de `RescheduleReceiver`; a permissão declarada é `RECEIVE_BOOT_COMPLETED`. A presença de `SystemForegroundService` e da permissão `FOREGROUND_SERVICE` no manifesto da biblioteca não significa que o app mantenha um serviço em primeiro plano permanente. Não remover permissões ou componentes mesclados sem fonte oficial, necessidade demonstrada e validação específica.
- A inspeção acima foi feita diretamente no AAR do Google Maven e seu `AndroidManifest.xml`; o caminho interno do metadado e o manifesto podem ser conferidos no próprio [AAR 2.12.0](https://dl.google.com/dl/android/maven2/androidx/work/work-runtime/2.12.0/work-runtime-2.12.0.aar). O usuário informa que o Galaxy S23 com API 37 está disponível e autorizado, mas não foi acessado nesta pesquisa: não houve uso de ADB, teste de runtime ou build. A validação de runtime do WorkManager nessa API permanece futura.

## Complemento de implementação — observabilidade e TestDriver

- A referência oficial de [WorkInfo](https://developer.android.com/reference/androidx/work/WorkInfo)
  confirma que `initialDelayMillis`, `periodicityInfo`, `constraints`,
  `nextScheduleTimeMillis` e `generation` são APIs públicas desde 2.8/2.9.
  Foram usadas para testar período/atraso/identidade, sem acoplar os testes ao
  WorkDatabase interno. `nextScheduleTimeMillis` é somente a primeira
  elegibilidade prevista, não promessa de execução.
- [TestDriver](https://developer.android.com/reference/androidx/work/testing/TestDriver)
  oferece `setInitialDelayMet` e `setPeriodDelayMet`; os testes exercitaram a
  primeira execução atrasada e períodos seguintes sem esperar tempo real.
- WorkManager 2.12.0 foi compilado/executado no AVD 36.1 com WorkerFactory de
  teste; a validação temporal no Samsung continua pendente. Evidência executável:
  `docs/testing/monitor-local-results.md`, Tarefa 6.
