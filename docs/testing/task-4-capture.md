# Tarefa 4 — listener, fotografia inicial e composição

## Implementação

- `C6NotificationListener` registra conexão/desconexão pelo caminho de lifecycle dedicado. Só consulta `activeNotifications` após `onListenerConnected`; falhas de consulta geram código de diagnóstico, não uma fotografia vazia.
- O `NotificationCaptureCoordinator` filtra o pacote exato pelo metadado `packageName` antes de enfileirar o `StatusBarNotification`. O alvo vazio desativa a captura. Um único worker serial mapeia/classifica e envia eventos ao recorder, mantendo ordem e timestamps do callback sem bloquear a thread principal.
- `NotificationSnapshotMapper` copia apenas pacote, chave, id, tag, agrupamento, horários, motivo de remoção e extras de título/texto/texto longo/linhas. CharSequences são convertidas em String; lifecycle não pode ser mapeado como notificação.
- `CaptureStatus` é process-local: começa `UNKNOWN`, mantém evidência temporal e códigos de falha seguros. Configuração do alvo e estado do listener são separados; autorização não é inferida nem representada.
- `CollectorApplication` compõe status, coordinator e recorder como singletons. O helper SQLite é privado/lazy; operações de banco permanecem no worker IO do recorder.

## RED / GREEN

Fixtures instrumentadas usam `Notification.Builder` e `StatusBarNotification` sintéticos. Nenhuma notificação é publicada no sistema.

- RED real, com o mapper temporário mínimo: seis testes instrumentados executaram e falharam por assertions de campos/tipo/classificação ausentes. Uma tentativa anterior só revelou incompatibilidades de compilação no fixture do construtor Android e **não** foi contada como execução de testes.
- GREEN: `tools/gradle.ps1 :app:testDebugUnitTest :app:connectedDebugAndroidTest --console=plain` — **32 testes JVM e 16 instrumentados passaram**. O relatório instrumentado cobre 6 testes do banco, 7 do mapper e 3 do coordinator.
- Build/lint: `tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain` — BUILD SUCCESSFUL, lint com **0 erros e 9 avisos**. Os avisos são versões mais recentes disponíveis, `allowBackup` (sem adicionar XML de backup, fora do escopo) e ícone ausente.
- Testes instrumentados executados no AVD `Medium_Phone_API_36.1` em modo `-read-only`, sem conceder acesso de listener e sem aparelho físico.

## Manifest mesclado

`app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml` contém `.capture.C6NotificationListener` com a action `android.service.notification.NotificationListenerService`, `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` e `android:exported="false"`. A activity launcher continua `exported="true"`. Não há permissões INTERNET, POST_NOTIFICATIONS, armazenamento, foreground service, wakelock, boot receiver ou RECEIVE_SENSITIVE_NOTIFICATIONS. A permissão `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` observada no manifest mesclado é gerada por dependência AndroidX e tem proteção `signature`.

## Limites

Os testes provam mapeamento e serialização com dados sintéticos, não binding ou leitura de notificações C6 reais. O pacote alvo continua vazio por padrão e precisa ser configurado explicitamente para ativar captura. A autorização real do listener permanece uma etapa manual futura; não foi alterada no emulador nem em dispositivo físico. O Android pode ocultar conteúdo de notificações (incluindo em versões Android 15+); o collector não solicita a permissão privilegiada para contornar isso.

## Fontes principais

- [NotificationListenerService — Android Developers](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
- [Notification — Android Developers](https://developer.android.com/reference/android/app/Notification)
- [StatusBarNotification — Android Developers](https://developer.android.com/reference/android/service/notification/StatusBarNotification)
